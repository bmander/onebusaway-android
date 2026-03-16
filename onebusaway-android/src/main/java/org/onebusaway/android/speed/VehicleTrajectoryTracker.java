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

import org.onebusaway.android.io.elements.ObaRoute;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton speed-estimation facade. Delegates trip data access to
 * {@link TripDataManager} and owns only speed estimation logic
 * (gamma model, schedule speed, route type routing).
 */
public final class VehicleTrajectoryTracker {

    private static final VehicleTrajectoryTracker INSTANCE = new VehicleTrajectoryTracker();

    private final TripDataManager dataManager = TripDataManager.getInstance();
    private final Map<String, Integer> routeTypeCache = new HashMap<>();
    private final ScheduleSpeedEstimator scheduleEstimator = new ScheduleSpeedEstimator();
    private SpeedEstimator estimator = new GammaSpeedEstimator();

    private VehicleTrajectoryTracker() {
    }

    public static VehicleTrajectoryTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the estimated speed in m/s for the given key and current state.
     */
    public synchronized Double getEstimatedSpeed(String key, VehicleState state) {
        if (key == null || state == null) {
            return null;
        }
        Integer routeType = routeTypeCache.get(key);
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
     * Stores the route type for a trip ID.
     */
    public synchronized void putRouteType(String tripId, int type) {
        if (tripId != null) {
            routeTypeCache.put(tripId, type);
        }
    }

    /**
     * Returns the cached route type for the given trip, or null if not cached.
     */
    public synchronized Integer getRouteType(String tripId) {
        return routeTypeCache.get(tripId);
    }

    /**
     * Clears speed estimation state and route type cache.
     */
    public synchronized void clearAll() {
        routeTypeCache.clear();
        estimator.clearState();
        scheduleEstimator.clearState();
    }
}
