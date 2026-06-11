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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * inputs and renders the result via [HomeShellHost].
 *
 * The arrivals sheet, focused stop, drawer-open command, and fragment management remain imperative
 * in the activity for now; they move here when the map + arrivals fragments are dissolved (P10/P11).
 */
class HomeViewModel(
    private val weatherRepo: WeatherRepository,
    private val wideAlertsRepo: WideAlertsRepository,
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
    private var dialog: HomeDialog = HomeDialog.NONE
    private var helpShowContactUs: Boolean = true

    // Guards so weather + alerts are fetched once per region (not on every region-valid callback).
    private var weatherRegionId: Long? = null
    private var alertsJob: Job? = null

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

    /** The host re-snapshotted preferences / app-global flags (onResume, nav change, layer toggle). */
    fun onEnvironmentRefreshed(env: HomeEnvironment) {
        environment = env
        recompute()
    }

    fun showHelp(showContactUs: Boolean) {
        helpShowContactUs = showContactUs
        dialog = HomeDialog.HELP
        recompute()
    }

    fun showWhatsNew() {
        dialog = HomeDialog.WHATS_NEW
        recompute()
    }

    fun dismissDialog() {
        dialog = HomeDialog.NONE
        recompute()
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
        _uiState.value = buildState(selectedItem, navItems, environment, weatherData, dialog, helpShowContactUs)
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
): HomeUiState {
    val nearby = selectedItem == HomeNavItem.NEARBY
    return HomeUiState(
        navItems = navItems,
        selectedItem = selectedItem,
        fabsVisible = nearby,
        zoomControlsVisible = nearby && environment.zoomControlsPref,
        leftHandMode = environment.leftHandMode,
        layersFabVisible = nearby && environment.bikeshareEnabled,
        bikeshareActive = environment.bikeshareActive,
        weather = if (nearby && !environment.weatherHidden) weatherData else null,
        donationVisible = nearby && environment.donationAvailable,
        dialog = dialog,
        helpShowContactUs = helpShowContactUs,
        showStarredStopsMenu = selectedItem == HomeNavItem.STARRED_STOPS,
        showStarredRoutesMenu = selectedItem == HomeNavItem.STARRED_ROUTES,
    )
}
