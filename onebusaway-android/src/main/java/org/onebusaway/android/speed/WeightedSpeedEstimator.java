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
 * Combines schedule and Kalman speed estimation. The schedule speed is used as the
 * velocity prior for the Kalman filter's mean-reversion: fresh data tracks actual speed,
 * stale data decays toward the schedule speed.
 *
 * If no schedule is available, the Kalman filter reverts toward 0 (stopped) when stale.
 * If no history is available, falls back to schedule speed alone.
 */
public class WeightedSpeedEstimator implements SpeedEstimator {

    private final ScheduleSpeedEstimator scheduleEstimator = new ScheduleSpeedEstimator();
    private final KalmanSpeedEstimator kalmanEstimator = new KalmanSpeedEstimator();

    @Override
    public Double estimateSpeed(String vehicleId, VehicleState state,
                                VehicleTrajectoryTracker tracker) {
        Double scheduleSpeed = scheduleEstimator.estimateSpeed(vehicleId, state, tracker);
        double velPrior = scheduleSpeed != null ? scheduleSpeed : 0.0;

        Double kalmanSpeed = kalmanEstimator.estimateSpeed(
                vehicleId, state, tracker, velPrior);

        if (kalmanSpeed != null) {
            return kalmanSpeed;
        }
        return scheduleSpeed;
    }

    @Override
    public double getLastPredictedVelVariance() {
        return kalmanEstimator.getLastPredictedVelVariance();
    }

    @Override
    public void clearState() {
        kalmanEstimator.clearState();
    }
}
