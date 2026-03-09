/*
 * Copyright (C) 2024 Open Transit Software Foundation
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
package org.onebusaway.android.speed;

/**
 * Interface for estimating the speed of a transit vehicle.
 */
public interface SpeedEstimator {

    /**
     * Estimates the current speed of a vehicle.
     *
     * @param vehicleId the vehicle identifier
     * @param state     the current vehicle state snapshot
     * @param tracker   the tracker holding vehicle history
     * @return estimated speed in meters per second, or null if insufficient data
     */
    Double estimateSpeed(String vehicleId, VehicleState state, VehicleTrajectoryTracker tracker);

    /**
     * Clears all internal estimator state.
     */
    default void clearState() {
    }

    /**
     * Returns the predicted velocity variance from the last estimateSpeed call.
     * Used to compute confidence intervals on the trajectory graph.
     * @return velocity variance in (m/s)², or 0 if not applicable
     */
    default double getLastPredictedVelVariance() {
        return 0;
    }

    /**
     * Returns the schedule-derived speed from the last estimateSpeed call.
     * Used as the velocity prior and to bound the Beta distribution for the position PDF.
     * @return schedule speed in m/s, or 0 if not available
     */
    default double getLastScheduleSpeed() {
        return 0;
    }
}
