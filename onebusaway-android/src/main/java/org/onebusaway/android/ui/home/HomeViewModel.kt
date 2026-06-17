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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.map.MapCommand
import org.onebusaway.android.map.MapInteractionBus
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.region.RegionStatus
import org.onebusaway.android.ui.home.chrome.homeNavItems
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val wideAlertsRepo: WideAlertsRepository,
    private val startupRepo: StartupPreferencesRepository,
    // Supplies the region/preference-derived gating inputs for the drawer items, so the VM rebuilds its
    // own (region-gated) nav list rather than the host pushing it in.
    private val navItemsRepo: NavItemsRepository,
    // The observable current region + its resolution action (refresh / manual-pick). The VM
    // self-subscribes to [region] for alerts / regionReady / nav items, and drives [refresh]/[choose]
    // for the resolve action (Campaign A: resolution moved into the repository).
    private val regionRepo: RegionRepository,
    // The shared Home->Map command bus (sheet padding, recenter, route mode, clear focus). Replaces the
    // old HomeMapController VM-on-VM reference, so this is a plain @HiltViewModel; faked in tests.
    private val bus: MapInteractionBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    // Raw inputs the gated [HomeUiState] is derived from.
    private var navItems: List<HomeNavItem> = emptyList()
    // Restored from SavedStateHandle so the tab survives process death (config change survives via the
    // ViewModel itself); the cross-session remembered tab is the activity's enum-name preference.
    private var selectedItem: HomeNavItem = readNavItem(savedState) ?: HomeNavItem.NEARBY
    // Whether the first nav selection has been applied — so [selectNav] reports the first selection as
    // fresh even when it matches the default/restored tab (the host posts it after onCreate).
    private var navApplied = false
    // Whether the map has been shown at least once (a latch; see HomeUiState.mapComposed). Survives a
    // configuration change in the ViewModel, so the map doesn't flash off + back on across rotation.
    private var mapComposed = false
    // Whether a region has resolved (currentRegion available); see HomeUiState.regionReady. A latch
    // surfaced as state so SurveyFeature self-triggers its (region-gated) request when NEARBY is shown.
    private var regionReady = false
    private var environment = HomeEnvironment()
    private var dialog: HomeDialog = HomeDialog.None

    /** True while a refresh kicked off by the experimental-regions toggle is in flight, so its
     *  completion (and only its completion) raises the OTP-reset host effect. */
    private var settingsTogglePending = false
    private var mapLoading: Boolean = false
    // The sheet's last resting position, reported up from the screen; drives the map padding/recenter
    // side-effects + the tutorial gate. Pure coordination state (no Compose reads it), so it's a plain
    // property rather than a HomeUiState field — see [lastSettledSheet].
    private var settledSheet: ArrivalsSheetState = ArrivalsSheetState.Hidden

    /** The sheet's last resting position, for the activity's imperative map/tutorial side-effects. */
    val lastSettledSheet: ArrivalsSheetState get() = settledSheet
    // A restored/deep-linked focus the imperative map hasn't been told about yet (re-derived by the
    // host on each create from the restored focusedStop, so it needn't be persisted).
    private var pendingMapFocus: Boolean = false
    // Seed the peek at the two-arrivals height so the first sheet reveal doesn't flash undersized
    // (matches the legacy setupSlidingPanel default); onPreferredHeight refines it.
    private var peekArrivalCount: Int = 2
    private var routeFiltering: Boolean = false
    // Focus state — seeded from SavedStateHandle so it survives process death (the data class itself
    // isn't Parcelable, so the fields are stored individually).
    private var focusedStop: FocusedStop? = readFocusedStop(savedState)
    private var focusedBikeStationId: String? = savedState[KEY_BIKE_STATION]

    // The wide-alert stream for the current region (cancelled + restarted when the region changes).
    private var alertsJob: Job? = null
    // The region-wide GTFS alert currently surfaced to the user (rendered as a Compose dialog by
    // HomeScreen), or null when none. Plain state rather than a one-shot event so the dialog survives
    // recomposition / config change and is driven declaratively.
    private var wideAlert: WideAlert? = null
    // The just-auto-selected region's name to announce in a snackbar (the old "Found X region" toast),
    // or null. Set on an auto-select resolve; cleared by [onRegionFoundShown] once HomeScreen shows it.
    private var regionFoundName: String? = null

    init {
        // Build the initial (region-gated) drawer items + reflect any SavedStateHandle-restored focus.
        refreshNavItems()
        // Self-subscribe to the current region (keyed on id; the StateFlow replays its seed): drive the
        // wide-alert stream, the survey/help gate (regionReady), and the region-gated nav items —
        // replacing the host's onRegionValid push + the regionReady/refreshNavItems in resolvedRegion.
        viewModelScope.launch {
            regionRepo.region.distinctUntilChangedBy { it?.id }.collect(::applyRegion)
        }
    }

    /** A region became current (or null): refresh the region-derived state the VM owns. */
    private fun applyRegion(region: ObaRegion?) {
        regionReady = region != null
        streamWideAlerts(region?.id)
        refreshNavItems() // recomputes with the new regionReady + nav items
    }

    /**
     * Rebuilds the region-gated drawer item list from the current environment (region capabilities +
     * the reminder preference), via the pure [homeNavItems] policy. Called at init and on every region
     * resolve (region capabilities may have changed). Idempotent: an unchanged list is deduped by the
     * StateFlow.
     */
    fun refreshNavItems() {
        val availability = navItemsRepo.availability()
        navItems = homeNavItems(
            showReminders = availability.showReminders,
            planTripAvailable = availability.planTripAvailable,
            payFareAvailable = availability.payFareAvailable,
        )
        recompute()
    }

    /**
     * A drawer item was selected. Updates the in-place selection (launcher items don't change it) and
     * returns whether this is a *fresh* selection — the host should run the per-item work (showMap /
     * analytics) — vs. a re-tap of the already-active in-place tab, which suppresses the redundant
     * work. The first selection is always fresh, even when it matches the default/restored tab.
     */
    fun selectNav(item: HomeNavItem): Boolean {
        val reselect = navApplied && !item.launchesActivity && selectedItem == item
        navApplied = true
        if (!item.launchesActivity) {
            selectedItem = item
            savedState[KEY_SELECTED_ITEM] = item.name
            recompute()
        }
        return !reselect
    }

    /** The map was shown (NEARBY first selected). Latches [HomeUiState.mapComposed] true so it stays composed. */
    fun onMapShown() {
        if (!mapComposed) {
            mapComposed = true
            recompute()
        }
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

    /**
     * The arrivals sheet settled at [state] (reported from the screen's live SheetState). Tracks the
     * resting position and drives the map's bottom padding + (on Expanded) a recenter on the focused
     * stop. The initial reveal (from Hidden) is skipped, matching the legacy behavior. The map host
     * null-check lives at the apply site (the Activity's `mMapHost?.`), so emitting unconditionally is
     * safe.
     */
    fun onSheetSettled(state: ArrivalsSheetState, peekPx: Int) {
        val previous = settledSheet
        settledSheet = state
        if (previous == ArrivalsSheetState.Hidden) {
            return
        }
        when (state) {
            ArrivalsSheetState.Expanded -> {
                bus.setBottomPadding(peekPx)
                focusedStop?.let { bus.send(MapCommand.RecenterOnFocusedStop(it.lat, it.lon)) }
            }
            ArrivalsSheetState.Collapsed -> bus.setBottomPadding(peekPx)
            ArrivalsSheetState.Hidden -> bus.setBottomPadding(0)
        }
    }

    /** Chevron tap — ask the screen to toggle the sheet (it holds the live SheetState). */
    fun requestToggleSheet() = emit(HomeEvent.ToggleSheet)

    /**
     * The host has a restored / deep-linked focus the imperative map hasn't been told about yet;
     * complete it once the arrivals load (see [onArrivalsLoaded]). A fresh map tap already centers the
     * stop, so it does not call this.
     */
    fun markPendingMapFocus() {
        pendingMapFocus = true
    }

    /**
     * Establishes the map's initial focus on create. A restored focus (SavedStateHandle) is kept as-is;
     * otherwise the deep-linked [intentFocus] (if any) is adopted. Either way, if there's now a focus it
     * is marked pending so the map recenters + adds the marker once arrivals load. No focus → nothing.
     */
    fun applyInitialFocus(intentFocus: FocusedStop?) {
        if (focusedStop == null) {
            intentFocus?.let { onStopFocused(it) }
        }
        if (focusedStop != null) {
            markPendingMapFocus()
        }
    }

    /**
     * Arrivals loaded for the focused stop. If a restore/deep-link focus is pending, consume the latch
     * and return the overlay-expanded flag (true iff the sheet settled expanded) so the activity can
     * call MapViewModel.focusStop with the io/elements payload it holds; null if not pending.
     */
    fun onArrivalsLoaded(): Boolean? {
        if (!pendingMapFocus) {
            return null
        }
        pendingMapFocus = false
        return settledSheet == ArrivalsSheetState.Expanded
    }

    /** "Show vehicles on map" — collapse the sheet (screen), then switch the map to route mode. */
    fun requestShowRouteOnMap(routeId: String) {
        emit(HomeEvent.CollapseSheet)
        bus.send(MapCommand.ShowRoute(routeId))
    }

    /**
     * Back-press from a peeking sheet — clear the focus. The VM owns the focused stop, so clearing it
     * here hides the sheet (recompute); the bus's ClearFocus clears the map's render focus. (The old path
     * only told the map, relying on a focus-listener round-trip the host's setFocusStop(null) doesn't
     * make — so the sheet never hid; clearing the VM state directly is both correct and the
     * declarative source of truth.)
     */
    fun requestClearMapFocus() {
        onStopFocused(null)
        bus.send(MapCommand.ClearFocus)
    }

    private fun emit(event: HomeEvent) {
        viewModelScope.launch { _events.emit(event) }
    }

    /** The host re-snapshotted preferences / app-global flags (onResume, nav change, layer toggle). */
    fun onEnvironmentRefreshed(env: HomeEnvironment) {
        environment = env
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
            when (val status = regionRepo.refresh()) {
                is RegionStatus.Changed -> {
                    resolvedRegion(true, status.region.name)
                    fireToggleEffect()
                }
                RegionStatus.Unchanged -> {
                    resolvedRegion(false, null)
                    settingsTogglePending = false
                }
                is RegionStatus.NeedsManualSelection -> {
                    dialog = HomeDialog.ChooseRegion(status.regions)
                    recompute()
                    // Leave settingsTogglePending set; onRegionChosen fires the effect after the pick.
                }
                // No region change: just clear the toggle flag (the legacy callback did nothing more on
                // Skipped/Fixed, and nothing at all on a catastrophic Failed load).
                RegionStatus.Skipped, is RegionStatus.Fixed, RegionStatus.Failed ->
                    settingsTogglePending = false
            }
        }
    }

    /**
     * The Advanced-settings "experimental regions" toggle: re-resolve, then on a real change have the
     * host reset the OTP API version ([HomeEvent.RegionToggleChanged]). The region-found announcement is
     * the shared [HomeUiState.regionFoundName] snackbar.
     */
    fun onExperimentalRegionsToggled() {
        settingsTogglePending = true
        refreshRegions()
    }

    /** When a toggle-initiated refresh actually changed the region, raise the OTP-reset effect; then
     *  clear the flag. */
    private fun fireToggleEffect() {
        if (settingsTogglePending) {
            emit(HomeEvent.RegionToggleChanged)
        }
        settingsTogglePending = false
    }

    /**
     * Home was created. On the very first launch ever we defer the region check until the map's
     * location-permission result (so an auto-select has a location to work with); otherwise — or once
     * permission is already granted — check now. [hasLocationPermission] is read by the activity
     * (it needs a Context); the decision lives here.
     */
    fun onHomeStarted(hasLocationPermission: Boolean) {
        if (startupRepo.isInitialStartup() && !hasLocationPermission) {
            return
        }
        refreshRegions()
    }

    /**
     * The map host reported the first-launch location-permission result (granted or denied). Complete
     * the deferred first launch: mark it done and check the region (a denial leads to the manual picker).
     */
    fun onLocationPermissionResult() {
        if (startupRepo.isInitialStartup()) {
            startupRepo.clearInitialStartup()
            refreshRegions()
        }
    }

    /** The user picked a region in the forced-choice dialog (old haveUserChooseRegion onClick). */
    fun onRegionChosen(region: ObaRegion) {
        viewModelScope.launch {
            regionRepo.choose(region)
            dialog = HomeDialog.None
            recompute()
            // regionName null: the legacy manual-pick path logged no analytics.
            resolvedRegion(true, null)
            fireToggleEffect()
        }
    }

    /**
     * A region *resolve action* completed. The region-derived *state* (alerts, regionReady, nav items)
     * is driven reactively by the region collector ([applyRegion]), and the map re-zoom by the map's own
     * region collector; this handles only the remaining resolve-action outcomes: announce an
     * auto-selected region via a snackbar ([name] is non-null only for an auto-select change), and raise
     * the activity's analytics event.
     */
    private fun resolvedRegion(changed: Boolean, name: String?) {
        // The map re-zoom is now driven by the map's own region collector (it observes RegionRepository).
        regionFoundName = name
        recompute() // surface regionFoundName for the snackbar
        emit(HomeEvent.RegionResolved(changed, name))
    }

    /** HomeScreen has shown the region-found snackbar; clear it so it isn't re-shown on recomposition. */
    fun onRegionFoundShown() {
        regionFoundName = null
        recompute()
    }

    /**
     * (Re)starts the wide-alert stream for [regionId] (cancelling the prior region's), or stops it when
     * null. Driven by the region collector, which already dedups by id. (Weather is its own feature
     * module — see WeatherViewModel.)
     */
    private fun streamWideAlerts(regionId: Long?) {
        alertsJob?.cancel()
        alertsJob = if (regionId == null) {
            null
        } else {
            viewModelScope.launch {
                wideAlertsRepo.wideAlerts(regionId.toString()).collect { alert ->
                    wideAlert = alert
                    recompute()
                }
            }
        }
    }

    /** The user dismissed the region-wide alert dialog (Dismiss, or after following "More info"). */
    fun dismissWideAlert() {
        wideAlert = null
        recompute()
    }

    private fun recompute() {
        _uiState.value = buildState(
            selectedItem, navItems, environment, dialog,
            focusedStop, focusedBikeStationId, mapLoading, peekArrivalCount, routeFiltering, mapComposed,
            wideAlert, regionReady, regionFoundName
        )
    }

    private companion object {
        const val KEY_STOP_ID = "home.focusedStop.id"
        const val KEY_STOP_NAME = "home.focusedStop.name"
        const val KEY_STOP_CODE = "home.focusedStop.code"
        const val KEY_STOP_LAT = "home.focusedStop.lat"
        const val KEY_STOP_LON = "home.focusedStop.lon"
        const val KEY_BIKE_STATION = "home.focusedBikeStation.id"
        const val KEY_SELECTED_ITEM = "home.selectedItem"

        fun readNavItem(s: SavedStateHandle): HomeNavItem? = navItemByName(s[KEY_SELECTED_ITEM])

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
 * gates: chrome shows only on NEARBY; the layers FAB additionally needs bikeshare. (The weather chip
 * and the donation card are their own feature modules — see WeatherViewModel / DonationViewModel — so
 * their gates are no longer here.)
 */
internal fun buildState(
    selectedItem: HomeNavItem,
    navItems: List<HomeNavItem>,
    environment: HomeEnvironment,
    dialog: HomeDialog,
    focusedStop: FocusedStop? = null,
    focusedBikeStationId: String? = null,
    mapLoading: Boolean = false,
    peekArrivalCount: Int = 0,
    routeFiltering: Boolean = false,
    mapComposed: Boolean = false,
    wideAlert: WideAlert? = null,
    regionReady: Boolean = false,
    regionFoundName: String? = null,
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
        mapComposed = mapComposed,
        regionReady = regionReady,
        mapLoading = nearby && mapLoading,
        fabsVisible = nearby,
        zoomControlsVisible = nearby && environment.zoomControlsPref,
        leftHandMode = environment.leftHandMode,
        layersFabVisible = nearby && environment.bikeshareEnabled,
        bikeshareActive = environment.bikeshareActive,
        dialog = dialog,
        wideAlert = wideAlert,
        regionFoundName = regionFoundName,
        showListSortMenu = listTab,
        showListClearMenu = starredTab,
    )
}
