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
 * Combines schedule and history speed estimators using weighted averaging.
 * Schedule weight: 0.3, History weight: 0.7.
 * Falls back to whichever estimator has data when only one returns non-null.
 */
public class WeightedSpeedEstimator implements SpeedEstimator {

    private static final double SCHEDULE_WEIGHT = 0.3;
    private static final double HISTORY_WEIGHT = 0.7;

    private final ScheduleSpeedEstimator scheduleEstimator = new ScheduleSpeedEstimator();
    private final HistorySpeedEstimator historyEstimator = new HistorySpeedEstimator();

    @Override
    public Double estimateSpeed(String vehicleId, VehicleState state, VehicleTrajectoryTracker tracker) {
        Double scheduleSpeed = scheduleEstimator.estimateSpeed(vehicleId, state, tracker);
        Double historySpeed = historyEstimator.estimateSpeed(vehicleId, state, tracker);

        if (scheduleSpeed != null && historySpeed != null) {
            return SCHEDULE_WEIGHT * scheduleSpeed + HISTORY_WEIGHT * historySpeed;
        } else if (historySpeed != null) {
            return historySpeed;
        } else if (scheduleSpeed != null) {
            return scheduleSpeed;
        }

        return null;
    }
}
