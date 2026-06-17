/*
 * Copyright (C) 2010-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South  Florida (sjbarbeau@gmail.com), Microsoft Corporation
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
package org.onebusaway.android.ui.arrivals.dialogs

import android.content.Context
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.Occupancy
import org.onebusaway.android.io.elements.OccupancyState

/**
 * Builds the list of Strings that should be shown for a given trip "Bus Options" menu,
 * provided the arguments for that trip
 *
 * @param c                 Context
 * @param isRouteFavorite   true if this route is a user favorite, false if it is not
 * @param hasUrl            true if the route provides a URL for schedule data, false if it does
 *                          not
 * @param isReminderVisible true if the reminder is currently visible for a trip, false if it
 *                          is
 *                          not
 * @param occupancy occupancy of this trip
 * @param occupancyState occupanceState of this trip
 * @return the list of Strings that should be shown for a given trip, provided the arguments for
 * that trip
 */
fun buildTripOptions(
    c: Context,
    isRouteFavorite: Boolean,
    hasUrl: Boolean,
    isReminderVisible: Boolean,
    hasRouteFilter: Boolean,
    occupancy: Occupancy?,
    occupancyState: OccupancyState?
): List<String> {
    val list = ArrayList<String>()
    if (!isRouteFavorite) {
        list.add(c.getString(R.string.bus_options_menu_add_star))
    } else {
        list.add(c.getString(R.string.bus_options_menu_remove_star))
    }

    list.add(c.getString(R.string.bus_options_menu_show_vehicles_on_map))
    list.add(c.getString(R.string.bus_options_menu_show_trip_details))

    if (!isReminderVisible) {
        list.add(c.getString(R.string.bus_options_menu_set_reminder))
    } else {
        list.add(c.getString(R.string.bus_options_menu_edit_reminder))
    }

    if (!hasRouteFilter) {
        list.add(c.getString(R.string.bus_options_menu_show_only_this_route))
    } else {
        list.add(c.getString(R.string.bus_options_menu_show_all_routes))
    }

    if (hasUrl) {
        list.add(c.getString(R.string.bus_options_menu_show_route_schedule))
    }

    list.add(c.getString(R.string.bus_options_menu_report_trip_problem))

    if (occupancy != null) {
        if (occupancyState == OccupancyState.HISTORICAL) {
            list.add(c.getString(R.string.menu_title_about_historical_occupancy))
        } else {
            list.add(c.getString(R.string.menu_title_about_occupancy))
        }
    }

    return list
}
