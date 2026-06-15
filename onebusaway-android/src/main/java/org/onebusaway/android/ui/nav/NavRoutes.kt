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
package org.onebusaway.android.ui.nav

import android.net.Uri

/**
 * Central registry of Navigation-Compose route ids and nav-arg keys (Campaign C). The single
 * NavHost backbone lives in [org.onebusaway.android.ui.HomeActivity]; each screen converted from an
 * Activity to a destination adds its route here.
 *
 * Nav-arg keys deliberately reuse the existing launch-extra constant names (e.g. `MapParams.*`,
 * `ArrivalsIntents.*`) verbatim so the deep-link / shortcut / FCM contracts are preserved when a
 * thin exported entry-point activity translates its intent into one of these routes.
 *
 * C0 introduces only the start destination; C1+ append the converted screens.
 */
object NavRoutes {

    /** The map-centric home screen — the NavHost start destination. */
    const val HOME = "home"

    // --- Route info (C-a) ---
    // Clean nav-arg name (not the dotted intent-extra key): external contracts (the route data URI)
    // are translated to this route at the entry boundary; the destination VM reads this key from
    // SavedStateHandle. See RouteInfoViewModel.
    const val ARG_ROUTE_ID = "routeId"
    const val ROUTE_INFO = "routeInfo/{$ARG_ROUTE_ID}"

    /** Builds a navigable [ROUTE_INFO] route, encoding the id (route ids can contain `/`, spaces). */
    fun routeInfo(routeId: String): String = "routeInfo/${Uri.encode(routeId)}"

    // --- Arrivals (C-b) ---
    // stopId is the clean nav-arg; stopName is an optional pre-load title (the screen replaces it with
    // the loaded header). The standalone ArrivalsListActivity keeps its data-URI contract; this route
    // is the in-app destination. Direction/routes come from the loaded response, so they're not args.
    const val ARG_STOP_ID = "stopId"
    const val ARG_STOP_NAME = "stopName"
    const val ARRIVALS = "arrivals/{$ARG_STOP_ID}?$ARG_STOP_NAME={$ARG_STOP_NAME}"

    /** Builds a navigable [ARRIVALS] route (stop ids can contain `/`, `_`; encode them). */
    fun arrivals(stopId: String, stopName: String? = null): String =
        "arrivals/${Uri.encode(stopId)}" +
            if (stopName != null) "?$ARG_STOP_NAME=${Uri.encode(stopName)}" else ""

    // --- Trip details (C-d) ---
    // Clean nav-arg keys read by TripDetailsViewModel from SavedStateHandle (TripDetailsActivity's
    // Builder writes the same keys for the standalone path). stopId reuses ARG_STOP_ID above.
    const val ARG_TRIP_ID = "tripId"
    const val ARG_SCROLL_MODE = "scrollMode"
    const val ARG_DEST_ID = "destinationId"
    const val TRIP_DETAILS = "tripDetails/{$ARG_TRIP_ID}?$ARG_STOP_ID={$ARG_STOP_ID}&$ARG_SCROLL_MODE={$ARG_SCROLL_MODE}"

    /** Builds a navigable [TRIP_DETAILS] route (ids can contain `/`, `_`; encode them). */
    fun tripDetails(tripId: String, stopId: String? = null, scrollMode: String? = null): String {
        val query = buildList {
            if (stopId != null) add("$ARG_STOP_ID=${Uri.encode(stopId)}")
            if (scrollMode != null) add("$ARG_SCROLL_MODE=${Uri.encode(scrollMode)}")
        }.joinToString("&")
        return "tripDetails/${Uri.encode(tripId)}" + if (query.isNotEmpty()) "?$query" else ""
    }
}
