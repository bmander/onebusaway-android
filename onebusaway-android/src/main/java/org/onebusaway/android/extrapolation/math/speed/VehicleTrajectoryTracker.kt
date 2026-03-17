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
import org.onebusaway.android.extrapolation.math.GammaDistribution
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

    /**
     * Returns the estimated speed in m/s for the given key and current state.
     * Uses the route type from TripDataManager to select the appropriate estimator.
     */
    @Synchronized
    fun getEstimatedSpeed(key: String?, state: VehicleState?): Double? {
        if (key == null || state == null) return null
        val routeType = dataManager.getRouteType(key)
        val est = if (routeType != null && ObaRoute.isGradeSeparated(routeType)) {
            scheduleEstimator
        } else {
            estimator
        }
        return est.estimateSpeed(state, dataManager)
    }

    /**
     * Returns the estimated speed in m/s for the given key, using the last cached VehicleState.
     */
    @Synchronized
    fun getEstimatedSpeed(key: String?): Double? =
        getEstimatedSpeed(key, dataManager.getLastState(key))

    /**
     * Returns the schedule-derived speed from the last speed estimate.
     */
    @Synchronized
    fun getLastScheduleSpeed(): Double = estimator.getLastScheduleSpeed()

    /**
     * Returns the GammaDistribution from the last speed estimate.
     */
    @Synchronized
    fun getLastGammaDistribution(): GammaDistribution? = estimator.getLastGammaDistribution()

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
     * Clears speed estimation state.
     */
    @Synchronized
    fun clearAll() {
        estimator.clearState()
        scheduleEstimator.clearState()
    }
}
