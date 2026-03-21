/*
 * Copyright (C) 2024-2026 Open Transit Software Foundation
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
package org.onebusaway.android.extrapolation

import android.location.Location
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.prob.ProbDistribution
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.util.LocationUtils

/**
 * Singleton extrapolation facade. Manages per-trip [Extrapolator] instances,
 * creating the appropriate type based on route type (gamma for buses,
 * schedule replay for grade-separated transit).
 */
object VehicleTrajectoryTracker {

    /** Max age of the newest AVL entry before we consider extrapolation unreliable. */
    const val MAX_EXTRAPOLATION_AGE_MS = 5L * 60 * 1000

    @JvmStatic fun getInstance() = this

    private val dataManager = TripDataManager
    private val extrapolators = HashMap<String, Extrapolator>()

    private fun getOrCreateExtrapolator(tripId: String): Extrapolator =
            extrapolators.getOrPut(tripId) {
                val routeType = dataManager.getRouteType(tripId)
                if (routeType != null && ObaRoute.isGradeSeparated(routeType))
                    ScheduleReplayExtrapolator(tripId, dataManager)
                else
                    GammaExtrapolator(tripId, dataManager)
            }

    /**
     * Extrapolates a distribution over distance along the trip at [queryTimeMs].
     * Returns null if extrapolation is not possible.
     */
    @Synchronized
    fun extrapolate(tripId: String, queryTimeMs: Long): ProbDistribution? =
            getOrCreateExtrapolator(tripId).extrapolate(queryTimeMs)

    /**
     * Extrapolates a vehicle's lat/lng position, writing into [out].
     * Returns true if a valid position was computed.
     */
    @Synchronized
    fun extrapolatePosition(tripId: String, queryTimeMs: Long, out: Location): Boolean {
        val sd = dataManager.getShapeWithDistances(tripId) ?: return false
        if (sd.points.isEmpty()) return false
        val dist = extrapolate(tripId, queryTimeMs) ?: return false
        return LocationUtils.interpolateAlongPolyline(
                sd.points, sd.cumulativeDistances, dist.median(), out)
    }

    /** Returns true if the tracker has recent enough AVL data to extrapolate this trip. */
    fun canExtrapolate(tripId: String, queryTimeMs: Long): Boolean {
        val last = dataManager.getLastState(tripId) ?: return false
        return queryTimeMs - last.lastLocationUpdateTime <= MAX_EXTRAPOLATION_AGE_MS
    }

    /** Clears all per-trip extrapolator instances. */
    @Synchronized
    fun clearAll() {
        extrapolators.clear()
    }
}
