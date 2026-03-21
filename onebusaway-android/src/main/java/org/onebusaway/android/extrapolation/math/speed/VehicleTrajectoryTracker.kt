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
package org.onebusaway.android.extrapolation.math.speed

import android.location.Location
import org.onebusaway.android.extrapolation.Extrapolator
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.ProbDistribution
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.util.LocationUtils

/**
 * Singleton extrapolation facade. Dispatches to [GammaExtrapolator] for bus-like routes
 * or [ScheduleReplayExtrapolator] for grade-separated transit, based on route type.
 */
object VehicleTrajectoryTracker {

    /** Max age of the newest AVL entry before we consider extrapolation unreliable. */
    const val MAX_EXTRAPOLATION_AGE_MS = 5L * 60 * 1000

    @JvmStatic fun getInstance() = this

    private val dataManager = TripDataManager
    private val gammaExtrapolator = GammaExtrapolator(dataManager)
    private val scheduleReplayExtrapolator = ScheduleReplayExtrapolator()

    private fun selectExtrapolator(routeType: Int?): Extrapolator =
            if (routeType != null && ObaRoute.isGradeSeparated(routeType))
                scheduleReplayExtrapolator
            else
                gammaExtrapolator

    /**
     * Extrapolates a distribution over distance along the trip at [queryTimeMs].
     * Returns null if extrapolation is not possible.
     */
    @Synchronized
    fun extrapolate(tripId: String, queryTimeMs: Long,
                    snapshot: TripDataManager.TripSnapshot): ProbDistribution? {
        val newestValid = snapshot.newestValid ?: return null
        return selectExtrapolator(snapshot.routeType)
                .extrapolate(newestValid, snapshot, queryTimeMs)
    }

    /** Convenience overload that fetches the snapshot internally. */
    @Synchronized
    fun extrapolate(tripId: String, queryTimeMs: Long): ProbDistribution? =
            extrapolate(tripId, queryTimeMs, dataManager.getSnapshot(tripId))

    /**
     * Extrapolates a vehicle's lat/lng position, writing into [out].
     * Returns true if a valid position was computed.
     */
    @Synchronized
    fun extrapolatePosition(tripId: String, queryTimeMs: Long, out: Location): Boolean {
        val snapshot = dataManager.getSnapshot(tripId)
        val sd = snapshot.shapeData ?: return false
        if (sd.points.isEmpty()) return false
        val dist = extrapolate(tripId, queryTimeMs, snapshot) ?: return false
        return LocationUtils.interpolateAlongPolyline(
                sd.points, sd.cumulativeDistances, dist.median(), out)
    }

    /** Returns true if the tracker has recent enough AVL data to extrapolate this trip. */
    fun isSpeedEstimable(tripId: String, queryTimeMs: Long): Boolean {
        val last = dataManager.getLastState(tripId) ?: return false
        return queryTimeMs - last.lastLocationUpdateTime <= MAX_EXTRAPOLATION_AGE_MS
    }

    /** Clears estimation state. */
    @Synchronized
    fun clearAll() {
        gammaExtrapolator.clearCache()
    }
}
