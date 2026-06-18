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

import androidx.annotation.StringRes

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

/** Parses a stored [HomeNavItem] name, returning null for an absent or unrecognized value. */
internal fun navItemByName(name: String?): HomeNavItem? =
    name?.let { runCatching { HomeNavItem.valueOf(it) }.getOrNull() }

/**
 * The remembered nav tab, read from the enum-name preference with a fallback to the legacy int
 * `selected_navigation_drawer_position` (only ever 0..3, the in-place items) so existing installs
 * keep their tab. Unknown names fall back to the legacy position; anything else is NEARBY.
 */
internal fun persistedNavItem(name: String?, legacyPosition: Int): HomeNavItem =
    navItemByName(name)
        ?: when (legacyPosition) {
            1 -> HomeNavItem.STARRED_STOPS
            2 -> HomeNavItem.STARRED_ROUTES
            3 -> HomeNavItem.MY_REMINDERS
            else -> HomeNavItem.NEARBY
        }

/**
 * The initial nav tab for a fresh launch: a deep link into a route/stop forces [HomeNavItem.NEARBY] (so
 * the map shows it); otherwise the remembered tab via [persistedNavItem]. (Process-death restore uses
 * the ViewModel's SavedStateHandle instead — this is the cross-session / deep-link path.)
 */
internal fun initialNavItem(
    persistedName: String?,
    legacyPosition: Int,
    deepLinksToMap: Boolean,
): HomeNavItem =
    if (deepLinksToMap) HomeNavItem.NEARBY else persistedNavItem(persistedName, legacyPosition)

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
 * Builds a [FocusedStop] from launch-intent extras, or null when they don't carry a usable stop — a
 * stop id plus a real (non-zero) location. Mirrors HomeActivity.makeIntent's STOP_ID + CENTER_LAT/LON.
 */
internal fun focusedStopFromExtras(
    stopId: String?,
    stopName: String?,
    stopCode: String?,
    lat: Double,
    lon: Double,
): FocusedStop? =
    if (stopId != null && lat != 0.0 && lon != 0.0) {
        FocusedStop(stopId, stopName, stopCode, lat, lon)
    } else {
        null
    }

/**
 * The target of a "send feedback / report a problem" launch, derived by [HomeViewModel.reportTarget]:
 * the focused stop, else the last-known location, else nothing. Choosing the variant is VM logic; the
 * host just opens `ReportActivity` for whichever it gets.
 */
sealed interface ReportTarget {
    /** Report against the currently focused stop. */
    data class Stop(val stop: FocusedStop) : ReportTarget

    /** No focused stop; report against the last-known device location. */
    data class Location(val lat: Double, val lon: Double) : ReportTarget

    /** No focused stop and no known location; open the generic report screen. */
    object Generic : ReportTarget
}

/**
 * The chrome-gate inputs (preferences + region-derived flags) the ViewModel collects reactively from
 * the preference seam (see HomeViewModel's init{} combine collector), grouped so [buildState] can
 * compute the gated chrome/overlay visibility from a single named value.
 */
data class HomeEnvironment(
    val bikeshareEnabled: Boolean = false,
    val bikeshareActive: Boolean = false,
    val zoomControlsPref: Boolean = false,
    val leftHandMode: Boolean = false,
)

/**
 * Complete render state for the home screen's chrome, overlays, dialogs, and nav drawer — the single
 * source of truth the [HomeScreen] collects and renders. The visibility gates are *derived* in the
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
    // arrivals sheet peek size inputs (the screen maps these to a peek height)
    val peekArrivalCount: Int = 0,
    val routeFiltering: Boolean = false,
    // Whether the focused stop's arrivals have loaded (so [peekArrivalCount] reflects real content,
    // not the loading skeleton). The screen waits for this before animating the peek open, so the
    // sheet opens straight to its final height instead of opening to a stale height and then jumping
    // when the count resolves (which strands the BottomSheetScaffold's open animation). Reset on focus
    // change. See HomeScreen's sheet reconcile.
    val arrivalsReady: Boolean = false,
    // Whether the map has been shown at least once (NEARBY selected). A latch: once true it stays so,
    // so list tabs draw over a still-composed map rather than tearing it down (defers SDK init).
    val mapComposed: Boolean = false,
    // Whether a region has resolved (so currentRegion is available). The survey needs a region to build
    // its study URL, so SurveyFeature self-triggers its request once this + NEARBY are both true.
    val regionReady: Boolean = false,
    // chrome — derived from selectedItem + environment
    val mapLoading: Boolean = false,
    val fabsVisible: Boolean = true,
    val zoomControlsVisible: Boolean = false,
    val leftHandMode: Boolean = false,
    val layersFabVisible: Boolean = false,
    val bikeshareActive: Boolean = false,
    // dialogs (HomeDialog lives in HomeDialogs.kt)
    val dialog: HomeDialog = HomeDialog.None,
    // A region-wide GTFS alert to surface in a (non-dismissible) dialog; null when none is showing.
    // Concurrent alerts collapse to the most recent — they're rare (usually zero or one per region).
    val wideAlert: WideAlert? = null,
    // The just-auto-selected region's name, to announce once in a snackbar (the old "Found X region"
    // toast), or null. Cleared by the VM once the snackbar has been shown (onRegionFoundShown).
    val regionFoundName: String? = null,
    // toolbar menu groups — derived from selectedItem. Sort shows on any list tab; clear only on the
    // two starred tabs (recents/reminders aren't user-clearable from here).
    val showListSortMenu: Boolean = false,
    val showListClearMenu: Boolean = false,
)

/** The arrivals sheet's resting position, reported from the screen back to the activity. */
enum class ArrivalsSheetState { Hidden, Collapsed, Expanded }

/**
 * A toolbar list-menu action the active list overlay should carry out against its own VM. The host's
 * sort/clear menu items emit these (via [HomeViewModel]); only the active overlay collects them, so the
 * action resolves against that destination's list rather than the host dispatching by selected tab.
 */
enum class ListMenuRequest { Sort, Clear }

/**
 * A telemetry event the ViewModel emits ([HomeViewModel.analyticsEvents]) for the host's single
 * [HomeAnalyticsEffect] to report — keeping the imperative `ObaAnalytics` calls out of the activity
 * (mirroring `AccessibilityAnalyticsEffect`), since dispatch needs a `Context` but the decision doesn't.
 */
sealed interface HomeAnalyticsEvent {
    /** An auto-selected region change (a manual pick logs none, matching the legacy behavior). */
    data class RegionSelected(val regionName: String) : HomeAnalyticsEvent

    /** A nav-drawer / help-menu selection identified by its analytics label string resource. */
    data class MenuItem(@StringRes val labelRes: Int) : HomeAnalyticsEvent
}

/**
 * One-shot sheet commands driven from the ViewModel, consumed by [HomeScreen] (which alone holds the
 * live `SheetState`) off its own [HomeViewModel.sheetCommands] flow. (The drawer is opened directly by
 * [org.onebusaway.android.ui.home.chrome.HomeTopBar]'s hamburger, so it needs no command.)
 */
sealed interface SheetCommand {
    /** The arrivals-sheet chevron was tapped — toggle peek <-> full. */
    object ToggleSheet : SheetCommand

    /** Collapse the sheet to its peek (e.g. after "show vehicles on map"). */
    object CollapseSheet : SheetCommand
}
