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

import org.onebusaway.android.io.elements.ObaTripSchedule;

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
    private double mLastScheduleSpeed;

    @Override
    public Double estimateSpeed(String vehicleId, VehicleState state,
                                VehicleTrajectoryTracker tracker) {
        double velPrior = getSpeedPrior(vehicleId, state, tracker);
        mLastScheduleSpeed = velPrior;

        Double kalmanSpeed = kalmanEstimator.estimateSpeed(
                vehicleId, state, tracker, velPrior);

        if (kalmanSpeed != null) {
            return kalmanSpeed;
        }
        // Fall back to schedule speed (velPrior is 0 before trip start)
        return velPrior > 0 ? velPrior : null;
    }

    private double getSpeedPrior(String vehicleId, VehicleState state,
                                 VehicleTrajectoryTracker tracker) {
        Double scheduleSpeed = scheduleEstimator.estimateSpeed(vehicleId, state, tracker);
        if (scheduleSpeed == null) {
            return 0.0;
        }

        // Before the trip's scheduled start, the vehicle is at layover
        String tripId = state.getActiveTripId();
        if (tripId != null) {
            Long serviceDate = tracker.getServiceDate(tripId);
            ObaTripSchedule schedule = tracker.getSchedule(tripId);
            ObaTripSchedule.StopTime[] stopTimes = schedule != null
                    ? schedule.getStopTimes() : null;
            if (serviceDate != null && stopTimes != null && stopTimes.length > 0) {
                long tripStartMs = serviceDate
                        + stopTimes[0].getDepartureTime() * 1000L;
                if (System.currentTimeMillis() < tripStartMs) {
                    return 0.0;
                }
            }
        }

        return scheduleSpeed;
    }

    @Override
    public double getLastPredictedVelVariance() {
        return kalmanEstimator.getLastPredictedVelVariance();
    }

    @Override
    public double getLastScheduleSpeed() {
        return mLastScheduleSpeed;
    }

    @Override
    public void clearState() {
        kalmanEstimator.clearState();
    }
}
