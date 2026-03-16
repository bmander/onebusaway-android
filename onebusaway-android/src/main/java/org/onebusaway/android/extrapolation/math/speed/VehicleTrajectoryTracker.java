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
package org.onebusaway.android.extrapolation.math.speed;

import org.onebusaway.android.extrapolation.data.TripDataManager;
import org.onebusaway.android.extrapolation.data.VehicleState;
import org.onebusaway.android.io.elements.ObaRoute;

/**
 * Singleton speed-estimation facade. Delegates trip data access to
 * {@link TripDataManager} and owns only speed estimation logic
 * (gamma model, schedule speed, route type routing).
 */
public final class VehicleTrajectoryTracker {

    private static final VehicleTrajectoryTracker INSTANCE = new VehicleTrajectoryTracker();

    private final TripDataManager dataManager = TripDataManager.getInstance();
    private final ScheduleSpeedEstimator scheduleEstimator = new ScheduleSpeedEstimator();
    private SpeedEstimator estimator = new GammaSpeedEstimator();

    private VehicleTrajectoryTracker() {
    }

    public static VehicleTrajectoryTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the estimated speed in m/s for the given key and current state.
     * Uses the route type from TripDataManager to select the appropriate estimator.
     */
    public synchronized Double getEstimatedSpeed(String key, VehicleState state) {
        if (key == null || state == null) {
            return null;
        }
        Integer routeType = dataManager.getRouteType(key);
        SpeedEstimator est = (routeType != null && ObaRoute.isGradeSeparated(routeType))
                ? scheduleEstimator : estimator;
        return est.estimateSpeed(state.getVehicleId(), state, dataManager);
    }

    /**
     * Returns the estimated speed in m/s for the given key, using the last cached VehicleState.
     */
    public synchronized Double getEstimatedSpeed(String key) {
        return getEstimatedSpeed(key, dataManager.getLastState(key));
    }

    /**
     * Returns the schedule-derived speed from the last speed estimate.
     */
    public synchronized double getLastScheduleSpeed() {
        return estimator.getLastScheduleSpeed();
    }

    /**
     * Returns the GammaParams from the last speed estimate.
     */
    public synchronized GammaSpeedModel.GammaParams getLastGammaParams() {
        return estimator.getLastGammaParams();
    }

    /**
     * Sets the active speed estimator.
     */
    public synchronized void setEstimator(SpeedEstimator estimator) {
        if (estimator != null) {
            this.estimator = estimator;
        }
    }

    /**
     * Clears speed estimation state.
     */
    public synchronized void clearAll() {
        estimator.clearState();
        scheduleEstimator.clearState();
    }
}
