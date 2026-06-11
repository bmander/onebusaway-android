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

/** The stop the user tapped on the map, decoupled from the io/elements types. */
data class FocusedStop(val id: String, val name: String?, val code: String?)

/** Arrivals sheet position. The legacy 50% half-anchor is intentionally dropped (peek <-> full). */
enum class ArrivalsSheetState { Hidden, Collapsed, Expanded }

/** Complete UI state for the home screen. */
data class HomeUiState(
    val selectedItem: HomeNavItem = HomeNavItem.NEARBY,
    val focusedStop: FocusedStop? = null,
    val sheetState: ArrivalsSheetState = ArrivalsSheetState.Hidden,
    /** Arrival rows previewed in the collapsed peek (drives the sheet peek height). */
    val peekArrivalCount: Int = 0,
    val routeFiltering: Boolean = false,
    val mapLoading: Boolean = false
)

/** One-shot effects the host carries out (start an Activity, show a dialog, drive the map). */
sealed interface HomeEvent {
    /** A drawer item that opens a separate screen (Settings, Help, Plan Trip, …). */
    data class LaunchNavItem(val item: HomeNavItem) : HomeEvent

    /** "Show vehicles on map" from the arrivals panel — collapse the sheet and enter route mode. */
    data class ShowRouteOnMap(val routeId: String) : HomeEvent
}
