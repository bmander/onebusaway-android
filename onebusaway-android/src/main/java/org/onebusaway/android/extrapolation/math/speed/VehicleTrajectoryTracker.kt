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

import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.data.VehicleState
import org.onebusaway.android.extrapolation.math.SpeedDistribution
import org.onebusaway.android.io.elements.ObaRoute

/**
 * Singleton speed-estimation facade. Delegates trip data access to
 * [TripDataManager] and owns only speed estimation logic
 * (gamma model, schedule speed, route type routing).
 */
object VehicleTrajectoryTracker {

    @JvmStatic
    fun getInstance() = this

    private val dataManager = TripDataManager
    private val scheduleEstimator = ScheduleSpeedEstimator()
    private var estimator: SpeedEstimator = GammaSpeedEstimator()
    private var lastDistribution: SpeedDistribution? = null

    /**
     * Returns the estimated speed distribution for the given key and current state.
     * Uses the route type from TripDataManager to select the appropriate estimator.
     */
    @Synchronized
    fun getEstimatedDistribution(key: String?, state: VehicleState?, timestampMs: Long): SpeedDistribution? {
        if (key == null || state == null) return null
        val routeType = dataManager.getRouteType(key)
        val est = if (routeType != null && ObaRoute.isGradeSeparated(routeType)) {
            scheduleEstimator
        } else {
            estimator
        }
        val result = est.estimateSpeed(state, timestampMs, dataManager)
        val dist = when (result) {
            is SpeedEstimateResult.Success -> result.distribution
            is SpeedEstimateResult.Failure -> null
        }
        lastDistribution = dist
        return dist
    }

    /**
     * Returns the estimated speed in m/s for the given key and current state.
     */
    @Synchronized
    fun getEstimatedSpeed(key: String?, state: VehicleState?, timestampMs: Long): Double? =
        getEstimatedDistribution(key, state, timestampMs)?.median()

    /**
     * Returns the distribution from the last speed estimate.
     */
    @Synchronized
    fun getLastDistribution(): SpeedDistribution? = lastDistribution

    /**
     * Sets the active speed estimator.
     */
    @Synchronized
    fun setEstimator(estimator: SpeedEstimator?) {
        if (estimator != null) {
            this.estimator = estimator
        }
    }

    /**
     * Clears estimation state.
     */
    @Synchronized
    fun clearAll() {
        lastDistribution = null
    }
}
