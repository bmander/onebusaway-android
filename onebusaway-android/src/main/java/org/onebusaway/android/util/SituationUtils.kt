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

package org.onebusaway.android.util

import org.onebusaway.android.io.client.ArrivalsForStop
import org.onebusaway.android.io.client.EntryWithReferences
import org.onebusaway.android.io.client.SituationReference
import org.onebusaway.android.io.elements.ObaArrivalInfo
import org.onebusaway.android.io.elements.ObaSituation
import org.onebusaway.android.io.request.ObaArrivalInfoResponse
import java.util.concurrent.TimeUnit

/**
 * A class containing utility methods related to situations (service alerts).
 */
object SituationUtils {

    /**
     * Returns a list of all situations (service alerts) that are specific to the stop, routes, and
     * agency for the provided arrivals-and-departures-for-stop response.  For route-specific alerts, this
     * involves looping through the routes and checking the references element to see if there are
     * any route-specific alerts, and adding them to the list to be shown above the list of
     * arrivals for a stop.  See #700.
     *
     * @param response response from arrivals-and-departures-for-stop API
     * @param filter   list of route_ids to retrieve service alerts for, or null to retrieve service
     *                 alerts for all routes. Note that this filter only affects alerts scoped to
     *                 routes - it does not affect alerts scoped to stops or agencies
     * @return a list of all situations (service alerts) that are specific to the stop, routes, and
     * agency. If a route filter list is provided, situations for all stops and agencies are included
     * in the returned list, but only situations scoped for route_ids in the provided filter list are
     * included in the returned list (i.e., situations specified for route_ids that aren't in the
     * filter list are excluded).
     */
    @JvmStatic
    fun getAllSituations(
        data: EntryWithReferences<ArrivalsForStop>,
        filter: List<String>?
    ): List<SituationReference> {
        val refs = data.references
        val allSituations = mutableListOf<SituationReference>()
        // Track seen ids in a HashSet for O(1) retrieval (mutated below as route alerts are added).
        val allIds = HashSet<String>()

        // Add agency-wide and stop-specific alerts.
        for (id in data.entry.situationIds) {
            refs.situation(id)?.let { if (allIds.add(it.id)) allSituations.add(it) }
        }

        // The filter route-ids as a set (empty == no filter).
        val filterIds = filter.orEmpty().toHashSet()

        // Scan the arrivals; add a route-specific situation if not already added, unless a filter
        // list exists and the route_id isn't in it (route-scoped alerts only; stop/agency unaffected).
        for (arrival in data.entry.arrivalsAndDepartures) {
            if (filterIds.isEmpty() || filterIds.contains(arrival.routeId)) {
                for (situationId in arrival.situationIds) {
                    if (situationId !in allIds) {
                        refs.situation(situationId)?.let { allIds.add(it.id); allSituations.add(it) }
                    }
                }
            }
        }
        return allSituations
    }

    /**
     * Legacy overload over [ObaArrivalInfoResponse]/[ObaSituation], kept for the still-legacy fetch
     * paths (e.g. instrumented tests built on raw-response fixtures). Same logic as the modernized
     * overload above.
     */
    @JvmStatic
    fun getAllSituations(response: ObaArrivalInfoResponse?, filter: List<String>?): List<ObaSituation> {
        val allSituations: MutableList<ObaSituation> = ArrayList()
        if (response == null) {
            return allSituations
        }
        allSituations.addAll(response.situations)
        val allIds = allSituations.mapTo(HashSet()) { it.id }
        val filterIds = filter.orEmpty().toHashSet()
        val info: Array<ObaArrivalInfo> = response.arrivalInfo ?: return allSituations
        for (i in info) {
            val situationIds = i.situationIds ?: continue
            if (filterIds.isEmpty() || filterIds.contains(i.routeId)) {
                for (situationId in situationIds) {
                    if (!allIds.contains(situationId)) {
                        allIds.add(situationId)
                        allSituations.add(response.getSituation(situationId))
                    }
                }
            }
        }
        return allSituations
    }

    /**
     * Returns true if the provided currentTime falls within the situation's (i.e., alert's) active
     * windows or if the situation does not provide an active window, and false if the currentTime
     * falls outside of the situation's active windows
     *
     * @param currentTime the time to compare to the situation's windows, in milliseconds between
     *                    the current time and midnight, January 1, 1970 UTC
     * @return true if the provided currentTime falls within the situation's (i.e., alert's) active
     * windows or if the situation does not provide an active window, and false if the currentTime
     * falls outside of the situation's active windows
     */
    @JvmStatic
    fun isActiveWindowForSituation(situation: SituationReference, currentTime: Long): Boolean {
        if (situation.activeWindows.isEmpty()) {
            // We assume a situation is active if it doesn't contain any active window information
            return true
        }
        // Active window times are in seconds or milliseconds since epoch
        var currentTimeConverted = TimeUnit.MILLISECONDS.toSeconds(currentTime)
        var isActiveWindowForSituation = false
        for (activeWindow in situation.activeWindows) {
            val from = activeWindow.from
            val to = activeWindow.to

            if (!isTimestampInSeconds(from)) {
                currentTimeConverted = TimeUnit.MILLISECONDS.toMillis(currentTime)
            }
            // 0 is a valid end time that means no end to the window - see #990
            if (from <= currentTimeConverted && (to == 0L || currentTimeConverted <= to)) {
                isActiveWindowForSituation = true
                break
            }
        }
        return isActiveWindowForSituation
    }

    /** Legacy overload over [ObaSituation], kept for the raw-response fetch paths. */
    @JvmStatic
    fun isActiveWindowForSituation(situation: ObaSituation, currentTime: Long): Boolean {
        if (situation.activeWindows.isEmpty()) {
            return true
        }
        var currentTimeConverted = TimeUnit.MILLISECONDS.toSeconds(currentTime)
        for (activeWindow in situation.activeWindows) {
            val from = activeWindow.from
            val to = activeWindow.to
            if (!isTimestampInSeconds(from)) {
                currentTimeConverted = currentTime
            }
            if (from <= currentTimeConverted && (to == 0L || currentTimeConverted <= to)) {
                return true
            }
        }
        return false
    }

    /**
     * Checks if the given timestamp is in seconds.
     *
     * @param timestamp the timestamp to check
     * @return true if the timestamp is in seconds, false if it is in milliseconds
     */
    private fun isTimestampInSeconds(timestamp: Long): Boolean {
        // Get the current time in milliseconds
        val currentTimeMillis = System.currentTimeMillis()

        // If the timestamp is smaller than the current time divided by 1000, it's likely in seconds
        return timestamp < currentTimeMillis / 1000L
    }
}
