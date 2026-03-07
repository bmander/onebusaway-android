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
 * Estimates speed using the trip schedule: finds the two stops bracketing the vehicle's
 * current scheduled position and computes segment speed from the timetable.
 */
public class ScheduleSpeedEstimator implements SpeedEstimator {

    @Override
    public Double estimateSpeed(String vehicleId, VehicleState state, VehicleSpeedTracker tracker) {
        if (state.getScheduledDistanceAlongTrip() == null) {
            return null;
        }

        ObaTripSchedule schedule = tracker.getSchedule(state.getActiveTripId());
        if (schedule == null) {
            return null;
        }

        ObaTripSchedule.StopTime[] stopTimes = schedule.getStopTimes();
        if (stopTimes == null || stopTimes.length < 2) {
            return null;
        }

        double currentDist = state.getScheduledDistanceAlongTrip();

        // Find the two stops bracketing the current position
        int beforeIdx = -1;
        int afterIdx = -1;

        for (int i = 0; i < stopTimes.length; i++) {
            if (stopTimes[i].getDistanceAlongTrip() <= currentDist) {
                beforeIdx = i;
            } else {
                afterIdx = i;
                break;
            }
        }

        // Edge cases: before first stop or after last stop
        if (beforeIdx == -1) {
            // Before the first stop - use first segment speed
            beforeIdx = 0;
            afterIdx = 1;
        } else if (afterIdx == -1) {
            // After the last stop - use last segment speed
            beforeIdx = stopTimes.length - 2;
            afterIdx = stopTimes.length - 1;
        }

        double distDelta = stopTimes[afterIdx].getDistanceAlongTrip()
                - stopTimes[beforeIdx].getDistanceAlongTrip();
        long timeDelta = stopTimes[afterIdx].getArrivalTime()
                - stopTimes[beforeIdx].getDepartureTime();

        if (distDelta <= 0 || timeDelta <= 0) {
            return null;
        }

        return distDelta / timeDelta;
    }
}
