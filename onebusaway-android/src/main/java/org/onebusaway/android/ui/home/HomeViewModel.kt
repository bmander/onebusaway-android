/*
 * Copyright (C) 2026 Open Transit Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.android.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.onebusaway.android.io.elements.ObaRegion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the home screen's chrome/overlay/dialog/nav state as a single [HomeUiState], and drives the
 * weather + GTFS-wide-alert fetches through [viewModelScope] (replacing HomeActivity's
 * WeatherRequestTask and the Handler-based GTFS callback). The visibility gates are computed here
 * from the selected nav item plus the host-supplied [HomeEnvironment]; the activity feeds in the
 * inputs and renders the result via [HomeScreen].
 *
 * The focused stop is owned here and persisted through [SavedStateHandle] (replacing the activity's
 * `onSaveInstanceState`). The arrivals sheet, drawer-open command, and fragment management remain
 * imperative in the activity until the map + arrivals fragments are dissolved (P10b/P11).
 */
class HomeViewModel(
    private val savedState: SavedStateHandle,
    private val weatherRepo: WeatherRepository,
    private val wideAlertsRepo: WideAlertsRepository,
    private val regionRepo: RegionStatusRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    // Raw inputs the gated [HomeUiState] is derived from.
    private var navItems: List<HomeNavItem> = emptyList()
    private var selectedItem: HomeNavItem = HomeNavItem.NEARBY
    private var environment = HomeEnvironment()
    private var weatherData: WeatherData? = null
    private var dialog: HomeDialog = HomeDialog.None
    private var helpShowContactUs: Boolean = true
    private var mapLoading: Boolean = false
    // Seed the peek at the two-arrivals height so the first sheet reveal doesn't flash undersized
    // (matches the legacy setupSlidingPanel default); onPreferredHeight refines it.
    private var peekArrivalCount: Int = 2
    private var routeFiltering: Boolean = false
    // Focus state — seeded from SavedStateHandle so it survives process death (the data class itself
    // isn't Parcelable, so the fields are stored individually).
    private var focusedStop: FocusedStop? = readFocusedStop(savedState)
    private var focusedBikeStationId: String? = savedState[KEY_BIKE_STATION]

    // Guards so weather + alerts are fetched once per region (not on every region-valid callback).
    private var weatherRegionId: Long? = null
    private var alertsJob: Job? = null

    init {
        // Reflect any SavedStateHandle-restored focus in the initial rendered state.
        recompute()
    }

    /** Updates the (already region-gated) drawer item list. */
    fun setNavItems(items: List<HomeNavItem>) {
        navItems = items
        recompute()
    }

    /** A selectable (in-place) nav item became the current destination. */
    fun onNavItemSelected(item: HomeNavItem) {
        if (item.launchesActivity) return
        selectedItem = item
        recompute()
    }

    /** A map stop gained focus (non-null) or focus was cleared (null). Persists across process death. */
    fun onStopFocused(stop: FocusedStop?) {
        focusedStop = stop
        savedState[KEY_STOP_ID] = stop?.id
        savedState[KEY_STOP_NAME] = stop?.name
        savedState[KEY_STOP_CODE] = stop?.code
        savedState[KEY_STOP_LAT] = stop?.lat
        savedState[KEY_STOP_LON] = stop?.lon
        if (stop != null) {
            // Focusing a stop clears any bike-station focus (mirrors the legacy onFocusChanged).
            focusedBikeStationId = null
            savedState[KEY_BIKE_STATION] = null
        }
        recompute()
    }

    fun onBikeStationFocused(id: String?) {
        focusedBikeStationId = id
        savedState[KEY_BIKE_STATION] = id
        recompute()
    }

    /** The map fragment started/stopped loading data (drives the map-loading indicator on NEARBY). */
    fun onMapLoading(loading: Boolean) {
        mapLoading = loading
        recompute()
    }

    /** The arrivals panel reported its preferred peek size (row count + whether filtering is on). */
    fun onPreferredHeight(arrivalCount: Int, filtering: Boolean) {
        peekArrivalCount = arrivalCount
        routeFiltering = filtering
        recompute()
    }

    /** Chevron tap — ask the screen to toggle the sheet (it holds the live SheetState). */
    fun requestToggleSheet() = emit(HomeEvent.ToggleSheet)

    /** Collapse the sheet to peek (after "show vehicles on map"). */
    fun requestCollapseSheet() = emit(HomeEvent.CollapseSheet)

    private fun emit(event: HomeEvent) {
        viewModelScope.launch { _events.emit(event) }
    }

    /** The host re-snapshotted preferences / app-global flags (onResume, nav change, layer toggle). */
    fun onEnvironmentRefreshed(env: HomeEnvironment) {
        environment = env
        recompute()
    }

    fun showHelp(showContactUs: Boolean) {
        helpShowContactUs = showContactUs
        dialog = HomeDialog.Help
        recompute()
    }

    fun showWhatsNew() {
        dialog = HomeDialog.WhatsNew
        recompute()
    }

    /** The arrival-color legend (Help menu). */
    fun showLegend() {
        dialog = HomeDialog.Legend
        recompute()
    }

    /** The "are you sure?" confirmation when the user closes the donation card. */
    fun showDismissDonation() {
        dialog = HomeDialog.DismissDonation
        recompute()
    }

    fun dismissDialog() {
        dialog = HomeDialog.None
        recompute()
    }

    /**
     * Refreshes/resolves the current region (replaces HomeActivity.checkRegionStatus + ObaRegionsTask).
     * The repository performs the region model writes on Dispatchers.IO; this maps the outcome to the
     * activity's side effects (map re-zoom, toast, analytics) via a one-shot event, or raises the
     * forced-choice picker when no region can be auto-selected. No 100ms callback delay is needed — the
     * region is already set inside the suspend call before we emit.
     */
    fun refreshRegions() {
        viewModelScope.launch {
            when (val status = regionRepo.refreshRegions()) {
                is RegionStatus.Changed -> emit(HomeEvent.RegionResolved(true, status.region.name))
                RegionStatus.Unchanged -> emit(HomeEvent.RegionResolved(false, null))
                is RegionStatus.NeedsManualSelection -> {
                    dialog = HomeDialog.ChooseRegion(status.regions)
                    recompute()
                }
                // Parity: the legacy callback did nothing further in these cases.
                RegionStatus.Skipped, is RegionStatus.Fixed, RegionStatus.Failed -> Unit
            }
        }
    }

    /** The user picked a region in the forced-choice dialog (old haveUserChooseRegion onClick). */
    fun onRegionChosen(region: ObaRegion) {
        viewModelScope.launch {
            regionRepo.selectRegion(region)
            dialog = HomeDialog.None
            recompute()
            // regionName null: the legacy manual-pick path logged no analytics.
            emit(HomeEvent.RegionResolved(true, null))
        }
    }

    /**
     * The map fragment reported region validity. A non-null [regionId] fetches the weather forecast
     * and starts streaming wide alerts (once per region); null clears the weather.
     */
    fun onRegionValid(regionId: Long?) {
        if (regionId == null) {
            weatherData = null
            weatherRegionId = null
            alertsJob?.cancel()
            alertsJob = null
            recompute()
            return
        }
        if (weatherRegionId == regionId) {
            return
        }
        weatherRegionId = regionId

        viewModelScope.launch {
            weatherRepo.currentForecast(regionId).onSuccess { data ->
                weatherData = data
                recompute()
            }
        }

        alertsJob?.cancel()
        alertsJob = viewModelScope.launch {
            wideAlertsRepo.wideAlerts(regionId.toString()).collect { alert ->
                _events.emit(HomeEvent.ShowWideAlert(alert))
            }
        }
    }

    private fun recompute() {
        _uiState.value = buildState(
            selectedItem, navItems, environment, weatherData, dialog, helpShowContactUs,
            focusedStop, focusedBikeStationId, mapLoading, peekArrivalCount, routeFiltering
        )
    }

    private companion object {
        const val KEY_STOP_ID = "home.focusedStop.id"
        const val KEY_STOP_NAME = "home.focusedStop.name"
        const val KEY_STOP_CODE = "home.focusedStop.code"
        const val KEY_STOP_LAT = "home.focusedStop.lat"
        const val KEY_STOP_LON = "home.focusedStop.lon"
        const val KEY_BIKE_STATION = "home.focusedBikeStation.id"

        fun readFocusedStop(s: SavedStateHandle): FocusedStop? {
            val id = s.get<String>(KEY_STOP_ID) ?: return null
            return FocusedStop(
                id = id,
                name = s[KEY_STOP_NAME],
                code = s[KEY_STOP_CODE],
                lat = s.get<Double>(KEY_STOP_LAT) ?: 0.0,
                lon = s.get<Double>(KEY_STOP_LON) ?: 0.0,
            )
        }
    }
}

/**
 * Pure projection of the raw inputs onto the rendered [HomeUiState] — the home screen's
 * visibility-gating rules, lifted out of HomeActivity so they can be unit-tested. Mirrors the legacy
 * gates: chrome shows only on NEARBY; the layers FAB additionally needs bikeshare; the weather chip
 * needs data and a non-hidden preference; the donation card needs the DonationsManager flag.
 */
internal fun buildState(
    selectedItem: HomeNavItem,
    navItems: List<HomeNavItem>,
    environment: HomeEnvironment,
    weatherData: WeatherData?,
    dialog: HomeDialog,
    helpShowContactUs: Boolean,
    focusedStop: FocusedStop? = null,
    focusedBikeStationId: String? = null,
    mapLoading: Boolean = false,
    peekArrivalCount: Int = 0,
    routeFiltering: Boolean = false,
): HomeUiState {
    val nearby = selectedItem == HomeNavItem.NEARBY
    val starredTab = selectedItem == HomeNavItem.STARRED_STOPS ||
        selectedItem == HomeNavItem.STARRED_ROUTES
    val listTab = starredTab || selectedItem == HomeNavItem.MY_REMINDERS
    return HomeUiState(
        navItems = navItems,
        selectedItem = selectedItem,
        focusedStop = focusedStop,
        focusedBikeStationId = focusedBikeStationId,
        peekArrivalCount = peekArrivalCount,
        routeFiltering = routeFiltering,
        mapLoading = nearby && mapLoading,
        fabsVisible = nearby,
        zoomControlsVisible = nearby && environment.zoomControlsPref,
        leftHandMode = environment.leftHandMode,
        layersFabVisible = nearby && environment.bikeshareEnabled,
        bikeshareActive = environment.bikeshareActive,
        weather = if (nearby && !environment.weatherHidden) weatherData else null,
        donationVisible = nearby && environment.donationAvailable,
        dialog = dialog,
        helpShowContactUs = helpShowContactUs,
        showListSortMenu = listTab,
        showListClearMenu = starredTab,
    )
}
