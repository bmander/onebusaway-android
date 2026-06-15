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

    // --- Argless content screens (former thin host Activities) ---
    /** Transit agencies supported in the current region. */
    const val AGENCIES = "agencies"

    /** Manual OBA region (server) picker. */
    const val REGIONS = "regions"

    /** Version / license / contributor info. */
    const val ABOUT = "about"

    /** "Why donate" explainer with a button out to the donations page. */
    const val DONATION_LEARN_MORE = "donationLearnMore"

    // --- Search results (system ACTION_SEARCH target + the home top-bar search field) ---
    const val ARG_QUERY = "query"
    const val SEARCH = "search?$ARG_QUERY={$ARG_QUERY}"

    /** Builds a navigable [SEARCH] route for [query]. */
    fun search(query: String): String = "search?$ARG_QUERY=${Uri.encode(query)}"

    // --- Survey web view (Campaign C) ---
    // The external-survey WebView host. The survey URL is the only nav-arg (the former
    // SurveyWebViewActivity also accepted optional stop_id / route_ids / embedded_data extras, but the
    // sole caller — the home survey overlay — passed only the URL, and the loaded request used the raw
    // URL, so only the URL is carried).
    const val ARG_URL = "url"
    const val SURVEY_WEB_VIEW = "surveyWebView?$ARG_URL={$ARG_URL}"

    /** Builds a navigable [SURVEY_WEB_VIEW] route, encoding the survey URL (it carries query params). */
    fun surveyWebView(url: String): String = "surveyWebView?$ARG_URL=${Uri.encode(url)}"

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

    // --- Trip info / reminder editor (TripInfo) ---
    // The reminder editor takes the full trip context so a brand-new reminder (from the arrivals
    // "set reminder" action) needs no DB round-trip; the edit path passes only tripId/stopId. ids in
    // the path; the rest as optional query args (stopName/routeId reuse the keys above).
    const val ARG_ROUTE_NAME = "routeName"
    const val ARG_HEADSIGN = "headsign"
    const val ARG_DEPART_TIME = "departTime"
    const val ARG_STOP_SEQUENCE = "stopSequence"
    const val ARG_SERVICE_DATE = "serviceDate"
    const val ARG_VEHICLE_ID = "vehicleId"
    const val TRIP_INFO = "tripInfo/{$ARG_TRIP_ID}/{$ARG_STOP_ID}" +
        "?$ARG_ROUTE_ID={$ARG_ROUTE_ID}&$ARG_ROUTE_NAME={$ARG_ROUTE_NAME}&$ARG_STOP_NAME={$ARG_STOP_NAME}" +
        "&$ARG_HEADSIGN={$ARG_HEADSIGN}&$ARG_DEPART_TIME={$ARG_DEPART_TIME}" +
        "&$ARG_STOP_SEQUENCE={$ARG_STOP_SEQUENCE}&$ARG_SERVICE_DATE={$ARG_SERVICE_DATE}" +
        "&$ARG_VEHICLE_ID={$ARG_VEHICLE_ID}"

    /** Builds a navigable [TRIP_INFO] route; omitted/zero context args fall back to nav-arg defaults. */
    fun tripInfo(
        tripId: String,
        stopId: String,
        routeId: String? = null,
        routeName: String? = null,
        stopName: String? = null,
        headsign: String? = null,
        departTime: Long = 0L,
        stopSequence: Int = 0,
        serviceDate: Long = 0L,
        vehicleId: String? = null,
    ): String {
        val query = buildList {
            if (routeId != null) add("$ARG_ROUTE_ID=${Uri.encode(routeId)}")
            if (routeName != null) add("$ARG_ROUTE_NAME=${Uri.encode(routeName)}")
            if (stopName != null) add("$ARG_STOP_NAME=${Uri.encode(stopName)}")
            if (headsign != null) add("$ARG_HEADSIGN=${Uri.encode(headsign)}")
            if (departTime != 0L) add("$ARG_DEPART_TIME=$departTime")
            if (stopSequence != 0) add("$ARG_STOP_SEQUENCE=$stopSequence")
            if (serviceDate != 0L) add("$ARG_SERVICE_DATE=$serviceDate")
            if (vehicleId != null) add("$ARG_VEHICLE_ID=${Uri.encode(vehicleId)}")
        }.joinToString("&")
        return "tripInfo/${Uri.encode(tripId)}/${Uri.encode(stopId)}" +
            if (query.isNotEmpty()) "?$query" else ""
    }

    // --- Feedback (Campaign C) ---
    // The post-trip destination-reminder feedback screen. Reached only from the post-trip notification's
    // Yes/No actions (see NavigationService). RESPONSE is an Int (FEEDBACK_YES / FEEDBACK_NO); the rest
    // mirror the former intent extras (logFile is read on send; tripId/notificationId are carried to
    // preserve the contract). Declared last so it can reuse ARG_TRIP_ID above.
    const val ARG_FEEDBACK_RESPONSE = "feedbackResponse"
    const val ARG_LOG_FILE = "logFile"
    const val ARG_NOTIFICATION_ID = "notificationId"
    const val FEEDBACK = "feedback?$ARG_FEEDBACK_RESPONSE={$ARG_FEEDBACK_RESPONSE}" +
        "&$ARG_LOG_FILE={$ARG_LOG_FILE}&$ARG_TRIP_ID={$ARG_TRIP_ID}" +
        "&$ARG_NOTIFICATION_ID={$ARG_NOTIFICATION_ID}"

    /** Builds a navigable [FEEDBACK] route; null id args fall back to the nav-arg defaults. */
    fun feedback(
        response: Int,
        logFile: String? = null,
        tripId: String? = null,
        notificationId: Int = 0,
    ): String {
        val query = buildList {
            add("$ARG_FEEDBACK_RESPONSE=$response")
            if (logFile != null) add("$ARG_LOG_FILE=${Uri.encode(logFile)}")
            if (tripId != null) add("$ARG_TRIP_ID=${Uri.encode(tripId)}")
            if (notificationId != 0) add("$ARG_NOTIFICATION_ID=$notificationId")
        }.joinToString("&")
        return "feedback?$query"
    }
}
