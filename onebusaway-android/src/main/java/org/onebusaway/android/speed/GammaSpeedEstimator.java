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
                                TripDataManager dataManager) {
        mLastGammaParams = null;
        mLastScheduleSpeed = 0;

        Double scheduleSpeed = scheduleEstimator.estimateSpeed(vehicleId, state, dataManager);
        double vSched = scheduleSpeed != null ? scheduleSpeed : 0;
        mLastScheduleSpeed = vSched;

        String tripId = state.getActiveTripId();
        if (isTripNotYetStarted(tripId, vSched, dataManager)) {
            return null;
        }

        double vPrev = computePreviousAvlSpeed(tripId, dataManager);

        GammaSpeedModel.GammaParams params = GammaSpeedModel.fromSpeeds(vSched, vPrev);
        if (params != null) {
            mLastGammaParams = params;
            return GammaSpeedModel.medianSpeedMps(params);
        }

        // Fall back to schedule speed
        return vSched > 0 ? vSched : null;
    }

    private boolean isTripNotYetStarted(String tripId, double vSched,
                                         TripDataManager dataManager) {
        if (tripId == null || vSched <= 0) return false;
        Long serviceDate = dataManager.getServiceDate(tripId);
        ObaTripSchedule schedule = dataManager.getSchedule(tripId);
        ObaTripSchedule.StopTime[] stopTimes = schedule != null
                ? schedule.getStopTimes() : null;
        if (serviceDate != null && stopTimes != null && stopTimes.length > 0) {
            long tripStartMs = serviceDate + stopTimes[0].getDepartureTime() * 1000L;
            return System.currentTimeMillis() < tripStartMs;
        }
        return false;
    }

    private double computePreviousAvlSpeed(String tripId,
                                            TripDataManager dataManager) {
        if (tripId == null) return 0;
        List<VehicleHistoryEntry> history = dataManager.getHistoryReadOnly(tripId);
        if (history.size() < 2) return 0;

        VehicleHistoryEntry newer = null;
        VehicleHistoryEntry older = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            VehicleHistoryEntry e = history.get(i);
            if (e.getBestDistanceAlongTrip() != null
                    && e.getLastLocationUpdateTime() > 0) {
                if (newer == null) {
                    newer = e;
                } else {
                    older = e;
                    break;
                }
            }
        }
        if (older == null || newer == null) return 0;

        long dtMs = newer.getLastLocationUpdateTime() - older.getLastLocationUpdateTime();
        if (dtMs <= 0) return 0;

        double dd = newer.getBestDistanceAlongTrip() - older.getBestDistanceAlongTrip();
        return Math.max(0, dd / (dtMs / 1000.0));
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
