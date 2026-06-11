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

/**
 * JVM-pure models for the Compose home screen, replacing the int NAVDRAWER_ITEM_* constants and the
 * scattered map/panel state HomeActivity tracked in fields. The selectable items render content
 * in-place; [launchesActivity] items start a separate Activity instead (handled by the host).
 */
enum class HomeNavItem(val launchesActivity: Boolean) {
    NEARBY(false),
    STARRED_STOPS(false),
    STARRED_ROUTES(false),
    MY_REMINDERS(false),
    PLAN_TRIP(true),
    PAY_FARE(true),
    SETTINGS(true),
    HELP(true),
    SEND_FEEDBACK(true),
    OPEN_SOURCE(true)
}

/**
 * The stop the user tapped on the map, decoupled from the io/elements `ObaStop`. Carries lat/lon so
 * the host can recenter the map and launch feedback without holding the `ObaStop` object, and so the
 * focus survives process death via the ViewModel's `SavedStateHandle`.
 */
data class FocusedStop(
    val id: String,
    val name: String?,
    val code: String?,
    val lat: Double,
    val lon: Double,
)

/**
 * The current weather forecast, decoupled from the io/elements response. The raw icon string and
 * Fahrenheit temperature are kept so the WeatherCard can map them to a drawable + formatted string
 * (via [org.onebusaway.android.ui.weather.WeatherUtils]) at render time, leaving the ViewModel free
 * of resource/preference lookups and unit-testable.
 */
data class WeatherData(val icon: String, val temperatureF: Double, val summary: String?)

/**
 * The non-reactive environment the host snapshots (preferences + app-global flags) so the ViewModel
 * can compute the gated chrome/overlay visibility without reaching into Android statics itself.
 */
data class HomeEnvironment(
    val bikeshareEnabled: Boolean = false,
    val bikeshareActive: Boolean = false,
    val zoomControlsPref: Boolean = false,
    val leftHandMode: Boolean = false,
    val weatherHidden: Boolean = false,
    val donationAvailable: Boolean = false,
)

/**
 * Complete render state for the home screen's chrome, overlays, dialogs, and nav drawer — the single
 * source of truth the [HomeShellHost] collects and renders. The visibility gates are *derived* in the
 * ViewModel from [selectedItem] + the last [HomeEnvironment], replacing HomeActivity's scattered
 * updateLayersFab()/setWeatherData()/updateDonationsUIVisibility() recomputation at call sites.
 *
 * The arrivals sheet, drawer-open command, and focused-stop tracking remain imperative for now
 * (HomeShellHost / HomeActivity); they migrate when the map + arrivals fragments do (P10/P11).
 */
data class HomeUiState(
    val navItems: List<HomeNavItem> = emptyList(),
    val selectedItem: HomeNavItem = HomeNavItem.NEARBY,
    // map focus (survives config change + process death via SavedStateHandle)
    val focusedStop: FocusedStop? = null,
    val focusedBikeStationId: String? = null,
    // chrome — derived from selectedItem + environment
    val fabsVisible: Boolean = true,
    val zoomControlsVisible: Boolean = false,
    val leftHandMode: Boolean = false,
    val layersFabVisible: Boolean = false,
    val bikeshareActive: Boolean = false,
    // overlays
    val weather: WeatherData? = null,
    val donationVisible: Boolean = false,
    // dialogs (HomeDialog lives in HomeDialogs.kt)
    val dialog: HomeDialog = HomeDialog.NONE,
    val helpShowContactUs: Boolean = true,
    // toolbar menu groups — derived from selectedItem
    val showStarredStopsMenu: Boolean = false,
    val showStarredRoutesMenu: Boolean = false,
)

/** One-shot effects the host carries out (driven from the ViewModel, collected by the activity). */
sealed interface HomeEvent {
    /** A region-wide GTFS alert arrived; the activity shows it in a dialog. */
    data class ShowWideAlert(val alert: WideAlert) : HomeEvent
}
