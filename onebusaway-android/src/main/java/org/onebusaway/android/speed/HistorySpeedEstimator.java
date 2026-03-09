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
 * Estimates speed using a hybrid window over the trajectory history.
 * Uses max(MIN_WINDOW, ageOfNewestSample) as the lookback window,
 * smoothing over stop/go cycles while still responding to genuine speed changes.
 * Prefers distanceAlongTrip deltas; falls back to geographic distance.
 */
public class HistorySpeedEstimator implements SpeedEstimator {

    static final long MIN_WINDOW_MS = 0L; // experiment: no minimum window

    @Override
    public Double estimateSpeed(String vehicleId, VehicleState state, VehicleTrajectoryTracker tracker) {
        List<VehicleHistoryEntry> history = tracker.getHistory(
                state.getActiveTripId());
        if (history.size() < 2) {
            return null;
        }

        VehicleHistoryEntry newest = history.get(history.size() - 1);
        VehicleHistoryEntry oldest = history.get(0);

        Double endDist = newest.getBestDistanceAlongTrip();

        long now = System.currentTimeMillis();
        long ageMs = now - newest.getLastLocationUpdateTime();
        long sampleTimeMs = Math.max(MIN_WINDOW_MS, ageMs);
        long targetTimestamp = newest.getLastLocationUpdateTime() - sampleTimeMs;

        // Try distance-along-trip interpolation first
        if (endDist != null) {
            Double startDist = interpolateDistanceAtTime(history, targetTimestamp);
            if (startDist != null) {
                long effectiveStart = Math.max(targetTimestamp, oldest.getLastLocationUpdateTime());
                long timeDeltaMs = newest.getLastLocationUpdateTime() - effectiveStart;
                if (timeDeltaMs < 1000) {
                    return null;
                }

                double distDelta = endDist - startDist;
                if (distDelta < 0) {
                    distDelta = 0;
                }

                return distDelta / (timeDeltaMs / 1000.0);
            }
        }

        // Fall back to geographic distance using first and last entries with positions
        if (newest.getPosition() != null && oldest.getPosition() != null) {
            long timeDeltaMs = newest.getLastLocationUpdateTime() - oldest.getLastLocationUpdateTime();
            if (timeDeltaMs < 1000) {
                return null;
            }
            float distMeters = oldest.getPosition().distanceTo(newest.getPosition());
            return (double) distMeters / (timeDeltaMs / 1000.0);
        }

        return null;
    }

    /**
     * Interpolates the distance along trip at a target timestamp using the history entries.
     * If target is before the oldest entry, returns the oldest entry's distance (clamp).
     * Skips entries with null getBestDistanceAlongTrip().
     */
    public Double interpolateDistanceAtTime(List<VehicleHistoryEntry> history, long targetTimestamp) {
        // Find entries with valid distance, keeping track of bracketing pair
        VehicleHistoryEntry beforeEntry = null;
        VehicleHistoryEntry afterEntry = null;

        for (VehicleHistoryEntry entry : history) {
            if (entry.getBestDistanceAlongTrip() == null) {
                continue;
            }
            if (entry.getLastLocationUpdateTime() <= targetTimestamp) {
                beforeEntry = entry;
            } else {
                afterEntry = entry;
                break;
            }
        }

        // Target is before or at the oldest valid entry — clamp
        if (beforeEntry == null && afterEntry != null) {
            return afterEntry.getBestDistanceAlongTrip();
        }

        // No valid entries at all
        if (beforeEntry == null) {
            return null;
        }

        // Target is at or after the last valid entry — return its distance
        if (afterEntry == null) {
            return beforeEntry.getBestDistanceAlongTrip();
        }

        // Interpolate between beforeEntry and afterEntry
        long timeBefore = beforeEntry.getLastLocationUpdateTime();
        long timeAfter = afterEntry.getLastLocationUpdateTime();
        long timeSpan = timeAfter - timeBefore;
        if (timeSpan <= 0) {
            return beforeEntry.getBestDistanceAlongTrip();
        }

        double fraction = (double) (targetTimestamp - timeBefore) / timeSpan;
        double distBefore = beforeEntry.getBestDistanceAlongTrip();
        double distAfter = afterEntry.getBestDistanceAlongTrip();
        return distBefore + fraction * (distAfter - distBefore);
    }

}
