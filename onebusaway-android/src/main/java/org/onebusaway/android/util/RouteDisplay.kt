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
@file:JvmName("RouteDisplay")

package org.onebusaway.android.util

import org.onebusaway.android.io.elements.ObaArrivalInfo
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.util.comparators.AlphanumComparator

/** A route's two display lines: the prominent short name and an optional secondary line. */
data class RouteDisplayNames(val shortName: String, val longName: String?)

/**
 * Resolves a route's display names with the same short→long→description fallbacks the legacy
 * UIUtils.setRouteView applied: the short name falls back to the long name, and the secondary
 * line is the long name (or the description when the long name is missing or equals the short
 * name). Shared by the Compose route repositories.
 */
fun routeDisplayNames(route: ObaRoute): RouteDisplayNames = RouteDisplayNames(
    shortName = MyTextUtils.formatDisplayText(getRouteDisplayName(route)).orEmpty(),
    longName = getRouteDescription(route)?.takeIf { it.isNotEmpty() }
)

fun getRouteDisplayName(routeShortName: String?, routeLongName: String?): String {
    if (!routeShortName.isNullOrEmpty()) {
        return routeShortName
    }
    if (!routeLongName.isNullOrEmpty()) {
        return routeLongName
    }
    // Just so we never return null.
    return ""
}

fun getRouteDisplayName(route: ObaRoute): String {
    return getRouteDisplayName(route.shortName, route.longName)
}

fun getRouteDisplayName(arrivalInfo: ObaArrivalInfo): String {
    return getRouteDisplayName(arrivalInfo.shortName, arrivalInfo.routeLongName)
}

fun getRouteDescription(route: ObaRoute): String? {
    var shortName = route.shortName
    var longName = route.longName

    if (shortName.isNullOrEmpty()) {
        shortName = longName
    }
    if (longName.isNullOrEmpty() || shortName == longName) {
        longName = route.description
    }
    return MyTextUtils.formatDisplayText(longName)
}

/**
 * Returns a comma-delimited list of route display names that serve a stop
 *
 * For example, if a stop was served by "14" and "54", this method will return "14,54"
 *
 * @param stop   the stop for which the route display names should be serialized
 * @param routes a HashMap containing all routes that serve this stop, with the routeId as the
 *               key.
 *               Note that for efficiency this routes HashMap may contain routes that don't
 *               serve this stop as well -
 *               the routes for the stop are referenced via stop.getRouteDisplayNames()
 * @return comma-delimited list of route display names that serve a stop
 */
fun serializeRouteDisplayNames(stop: ObaStop, routes: HashMap<String, ObaRoute>?): String =
    stop.routeIds.joinToString(",") { routeId ->
        // Use the route's display name when we have the mapping, else fall back to the routeId.
        if (routes != null) getRouteDisplayName(routes[routeId]!!) else routeId
    }

/**
 * Returns a list of route display names from a serialized list of route display names
 *
 * See [serializeRouteDisplayNames]
 *
 * @param serializedRouteDisplayNames comma-separate list of routeIds from serializeRouteDisplayNames()
 * @return list of route display names
 */
fun deserializeRouteDisplayNames(serializedRouteDisplayNames: String): List<String> =
    serializedRouteDisplayNames.split(",")

/**
 * Returns a formatted and sorted list of route display names for presentation in a single line
 *
 * For example, the following list:
 *
 * 11,1,15, 8b
 *
 * ...would be formatted as:
 *
 * 4, 8b, 11, 15
 *
 * @param routeDisplayNames          list of route display names
 * @param nextArrivalRouteShortNames the short route names of the next X arrivals at the stop
 *                                   that are the same.  These will be highlighted in the
 *                                   results.
 * @return a formatted and sorted list of route display names for presentation in a single line
 */
fun formatRouteDisplayNames(
    routeDisplayNames: List<String>,
    nextArrivalRouteShortNames: List<String>
): String = routeDisplayNames.sortedWith(AlphanumComparator()).joinToString(", ") { name ->
    // Highlight (with "*") names that match one of the next X identical arrivals.
    if (nextArrivalRouteShortNames.any { it.equals(name, ignoreCase = true) }) "$name*" else name
}
