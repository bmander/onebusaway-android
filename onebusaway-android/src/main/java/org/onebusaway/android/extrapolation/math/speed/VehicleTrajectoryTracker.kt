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
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.ProbDistribution
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.bestDistanceAlongTrip
import org.onebusaway.android.util.LocationUtils

/**
 * Singleton speed-estimation facade. Delegates trip data access to [TripDataManager] and owns only
 * speed estimation logic (gamma model, schedule speed, route type routing).
 */
object VehicleTrajectoryTracker {

    /** Max age of the newest AVL entry before we consider extrapolation unreliable. */
    const val MAX_EXTRAPOLATION_AGE_MS = 5L * 60 * 1000

    @JvmStatic fun getInstance() = this

    private val dataManager = TripDataManager
    private val scheduleEstimator = ScheduleSpeedEstimator(dataManager)
    private var estimator: SpeedEstimator = GammaSpeedEstimator(dataManager)
    private var lastDistribution: ProbDistribution? = null

    /**
     * Returns the estimated speed distribution for the given trip. Uses the route type from
     * TripDataManager to select the appropriate estimator.
     */
    @Synchronized
    fun getEstimatedDistribution(tripId: String, queryTime: Long): ProbDistribution? {
        val routeType = dataManager.getRouteType(tripId)
        val est =
                if (routeType != null && ObaRoute.isGradeSeparated(routeType)) {
                    scheduleEstimator
                } else {
                    estimator
                }
        val result = est.estimateSpeed(tripId, queryTime)
        val dist =
                when (result) {
                    is SpeedEstimateResult.Success -> result.distribution
                    is SpeedEstimateResult.Failure -> null
                }
        lastDistribution = dist
        return dist
    }

    /** Returns the estimated speed in m/s for the given trip. */
    @Synchronized
    fun getEstimatedSpeed(tripId: String, timestampMs: Long): Double? =
            getEstimatedDistribution(tripId, timestampMs)?.median()

    /** Convenience overload that uses the current system time. */
    @Synchronized
    fun getEstimatedSpeed(tripId: String): Double? =
            getEstimatedSpeed(tripId, System.currentTimeMillis())

    /** Returns true if the tracker has recent enough AVL data to extrapolate this trip. */
    fun isSpeedEstimable(tripId: String, queryTimeMs: Long): Boolean {
        val last = dataManager.getLastState(tripId) ?: return false
        return queryTimeMs - last.lastLocationUpdateTime <= MAX_EXTRAPOLATION_AGE_MS
    }

    /** Returns the distribution from the last speed estimate. */
    @Synchronized fun getLastDistribution(): ProbDistribution? = lastDistribution

    /** Sets the active speed estimator. */
    @Synchronized
    fun setEstimator(estimator: SpeedEstimator?) {
        if (estimator != null) {
            this.estimator = estimator
        }
    }

    /**
     * Extrapolates a vehicle's position along its route polyline. Looks up shape, history, and
     * speed internally, then combines distance extrapolation with polyline interpolation.
     *
     * @param tripId the trip to extrapolate
     * @param currentTimeMs current time in milliseconds
     * @param out reusable Location to receive the interpolated position
     * @return true if a valid position was computed and written to [out]
     */
    @Synchronized
    fun extrapolatePosition(tripId: String, currentTimeMs: Long, out: Location): Boolean {
        val sd = dataManager.getShapeWithDistances(tripId) ?: return false
        if (sd.points.isEmpty()) return false
        val speed = getEstimatedSpeed(tripId) ?: return false
        val history = dataManager.getHistory(tripId)
        val dist = extrapolateDistance(history, speed, currentTimeMs) ?: return false
        return LocationUtils.interpolateAlongPolyline(sd.points, sd.cumulativeDistances, dist, out)
    }

    /** Clears estimation state. */
    @Synchronized
    fun clearAll() {
        lastDistribution = null
    }
}

/**
 * Extrapolates the current distance along the trip based on the newest valid history entry and
 * estimated speed. Returns null if extrapolation is not possible.
 *
 * @param history vehicle history entries for the trip
 * @param speedMps estimated speed in meters per second
 * @param currentTimeMs current time in milliseconds
 * @return extrapolated distance in meters, or null
 */
fun extrapolateDistance(
        history: List<ObaTripStatus>?,
        speedMps: Double,
        currentTimeMs: Long
): Double? {
    if (speedMps <= 0 || history == null) return null
    val newest =
            history.findLast { it.bestDistanceAlongTrip != null && it.lastLocationUpdateTime > 0 }
                    ?: return null
    val lastTime = newest.lastLocationUpdateTime
    if (currentTimeMs - lastTime > VehicleTrajectoryTracker.MAX_EXTRAPOLATION_AGE_MS) return null
    val lastDist = newest.bestDistanceAlongTrip ?: return null
    return lastDist + speedMps * (currentTimeMs - lastTime) / 1000.0
}
