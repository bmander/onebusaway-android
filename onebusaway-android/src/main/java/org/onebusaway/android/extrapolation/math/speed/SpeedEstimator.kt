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

/**
 * Interface for estimating the speed of a transit vehicle.
 */
interface SpeedEstimator {

    /**
     * Estimates the current speed of a vehicle.
     *
     * @param state       the current vehicle state snapshot
     * @param dataManager the manager holding trip data (history, schedule, etc.)
     * @return estimated speed in meters per second, or null if insufficient data
     */
    fun estimateSpeed(state: VehicleState, dataManager: TripDataManager): Double?

    /**
     * Clears all internal estimator state.
     */
    fun clearState() {}

    /**
     * Returns the schedule-derived speed from the last estimateSpeed call.
     * @return schedule speed in m/s, or 0 if not available
     */
    fun getLastScheduleSpeed(): Double = 0.0

    /**
     * Returns the GammaDistribution from the last estimateSpeed call, or null if not available.
     */
    fun getLastGammaDistribution(): GammaDistribution? = null
}
