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

import java.util.List;

/**
 * Estimates speed using the two most recent history entries.
 * Prefers distanceAlongTrip deltas; falls back to geographic distance.
 */
public class HistorySpeedEstimator implements SpeedEstimator {

    @Override
    public Double estimateSpeed(String vehicleId, VehicleState state, VehicleSpeedTracker tracker) {
        List<VehicleHistoryEntry> history = tracker.getHistory(
                state.getActiveTripId());
        if (history.size() < 2) {
            return null;
        }

        VehicleHistoryEntry prev = history.get(history.size() - 2);
        VehicleHistoryEntry curr = history.get(history.size() - 1);

        long timeDeltaMs = curr.getTimestamp() - prev.getTimestamp();
        if (timeDeltaMs < 1000) {
            return null;
        }

        double timeDeltaSec = timeDeltaMs / 1000.0;

        // Prefer distanceAlongTrip deltas
        if (prev.getDistanceAlongTrip() != null && curr.getDistanceAlongTrip() != null) {
            double distDelta = Math.abs(curr.getDistanceAlongTrip() - prev.getDistanceAlongTrip());
            return distDelta / timeDeltaSec;
        }

        // Fall back to geographic distance
        if (prev.getPosition() != null && curr.getPosition() != null) {
            float distMeters = prev.getPosition().distanceTo(curr.getPosition());
            return (double) distMeters / timeDeltaSec;
        }

        return null;
    }
}
