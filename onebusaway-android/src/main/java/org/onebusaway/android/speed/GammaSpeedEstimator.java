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

import java.util.List;

/**
 * Speed estimator using the H12 gamma distribution model.
 * Combines schedule speed with the most recent AVL-derived speed to produce
 * a gamma distribution over vehicle speed.
 */
public class GammaSpeedEstimator implements SpeedEstimator {

    private final ScheduleSpeedEstimator scheduleEstimator = new ScheduleSpeedEstimator();
    private GammaSpeedModel.GammaParams mLastGammaParams;
    private double mLastScheduleSpeed;

    @Override
    public Double estimateSpeed(String vehicleId, VehicleState state,
                                VehicleTrajectoryTracker tracker) {
        mLastGammaParams = null;
        mLastScheduleSpeed = 0;

        Double scheduleSpeed = scheduleEstimator.estimateSpeed(vehicleId, state, tracker);
        double vSched = scheduleSpeed != null ? scheduleSpeed : 0;
        mLastScheduleSpeed = vSched;

        // Before-trip-start check
        String tripId = state.getActiveTripId();
        if (tripId != null && vSched > 0) {
            Long serviceDate = tracker.getServiceDate(tripId);
            ObaTripSchedule schedule = tracker.getSchedule(tripId);
            ObaTripSchedule.StopTime[] stopTimes = schedule != null
                    ? schedule.getStopTimes() : null;
            if (serviceDate != null && stopTimes != null && stopTimes.length > 0) {
                long tripStartMs = serviceDate
                        + stopTimes[0].getDepartureTime() * 1000L;
                if (System.currentTimeMillis() < tripStartMs) {
                    return null;
                }
            }
        }

        // Compute v_prev from last two AVL history entries
        double vPrev = 0;
        if (tripId != null) {
            List<VehicleHistoryEntry> history = tracker.getHistoryReadOnly(tripId);
            if (history.size() >= 2) {
                VehicleHistoryEntry e2 = null;
                VehicleHistoryEntry e1 = null;
                // Find the last two entries with valid distances
                for (int i = history.size() - 1; i >= 0; i--) {
                    VehicleHistoryEntry e = history.get(i);
                    if (e.getBestDistanceAlongTrip() != null
                            && e.getLastLocationUpdateTime() > 0) {
                        if (e2 == null) {
                            e2 = e;
                        } else {
                            e1 = e;
                            break;
                        }
                    }
                }
                if (e1 != null && e2 != null) {
                    double dist1 = e1.getBestDistanceAlongTrip();
                    double dist2 = e2.getBestDistanceAlongTrip();
                    long time1 = e1.getLastLocationUpdateTime();
                    long time2 = e2.getLastLocationUpdateTime();
                    long dtMs = time2 - time1;
                    if (dtMs > 0) {
                        vPrev = Math.max(0, (dist2 - dist1) / (dtMs / 1000.0));
                    }
                }
            }
        }

        GammaSpeedModel.GammaParams params = GammaSpeedModel.fromSpeeds(vSched, vPrev);
        if (params != null) {
            mLastGammaParams = params;
            return GammaSpeedModel.meanSpeedMps(params);
        }

        // Fall back to schedule speed
        return vSched > 0 ? vSched : null;
    }

    @Override
    public GammaSpeedModel.GammaParams getLastGammaParams() {
        return mLastGammaParams;
    }

    @Override
    public double getLastScheduleSpeed() {
        return mLastScheduleSpeed;
    }

    @Override
    public void clearState() {
        mLastGammaParams = null;
        mLastScheduleSpeed = 0;
    }
}
