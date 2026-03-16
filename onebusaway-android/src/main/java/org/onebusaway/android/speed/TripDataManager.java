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

import android.location.Location;

import org.onebusaway.android.io.elements.ObaTripSchedule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton that owns all per-trip data storage: vehicle position history,
 * route shapes, schedules, service dates, route types, and active trip ID
 * tracking. Pure passive cache — callers push data in, readers pull it out.
 * Thread-safe via synchronized methods.
 */
public final class TripDataManager {

    private static final TripDataManager INSTANCE = new TripDataManager();

    private final AvlRepository repository = AvlRepository.getInstance();
    private final Map<String, ObaTripSchedule> scheduleCache = new HashMap<>();
    private final Map<String, Long> serviceDateCache = new HashMap<>();
    private final Map<String, List<Location>> shapeCache = new HashMap<>();
    private final Map<String, double[]> shapeCumDistCache = new HashMap<>();
    private final Map<String, Integer> routeTypeCache = new HashMap<>();
    /** Last active trip ID reported by the server for each queried trip. */
    private final Map<String, String> mLastActiveTripId = new HashMap<>();

    private TripDataManager() {
    }

    public static TripDataManager getInstance() {
        return INSTANCE;
    }

    // --- Vehicle history ---

    /**
     * Records a vehicle state snapshot into the history for the given key (activeTripId).
     * Delegates to {@link AvlRepository} for storage.
     */
    public void recordState(String key, VehicleState state) {
        repository.record(key, state, null);
    }

    /**
     * Records a vehicle state snapshot with block ID information.
     * Delegates to {@link AvlRepository} for storage.
     */
    public void recordState(String key, VehicleState state, String blockId) {
        repository.record(key, state, blockId);
    }

    /**
     * Returns a defensive copy of the history for the given key.
     */
    public List<VehicleHistoryEntry> getHistory(String key) {
        return repository.getHistoryForTrip(key);
    }

    /**
     * Returns a read-only view of the history for the given key.
     * Zero-allocation; suitable for per-frame hot-path use.
     */
    public List<VehicleHistoryEntry> getHistoryReadOnly(String key) {
        return repository.getHistoryForTripReadOnly(key);
    }

    /**
     * Returns the number of history entries for the given key, without copying.
     */
    public int getHistorySize(String key) {
        return repository.getHistorySizeForTrip(key);
    }

    /**
     * Returns the last recorded VehicleState for the given key, or null.
     */
    public VehicleState getLastState(String key) {
        return repository.getLastState(key);
    }

    // --- Schedule cache ---

    /**
     * Stores a trip schedule in the cache.
     */
    public synchronized void putSchedule(String tripId, ObaTripSchedule schedule) {
        if (tripId != null && schedule != null) {
            scheduleCache.put(tripId, schedule);
        }
    }

    /**
     * Returns the cached schedule for the given trip, or null if not cached.
     */
    public synchronized ObaTripSchedule getSchedule(String tripId) {
        return scheduleCache.get(tripId);
    }

    /**
     * Returns true if a schedule is cached for the given trip.
     */
    public synchronized boolean isScheduleCached(String tripId) {
        return scheduleCache.containsKey(tripId);
    }

    // --- Service date cache ---

    /**
     * Stores the service date for a trip.
     */
    public synchronized void putServiceDate(String tripId, long serviceDate) {
        if (tripId != null && serviceDate > 0) {
            serviceDateCache.put(tripId, serviceDate);
        }
    }

    /**
     * Returns the cached service date for the given trip, or null if not cached.
     */
    public synchronized Long getServiceDate(String tripId) {
        return serviceDateCache.get(tripId);
    }

    // --- Shape cache ---

    /**
     * Atomically readable shape data: polyline points and precomputed cumulative distances.
     */
    public static final class ShapeData {
        public final List<Location> points;
        public final double[] cumulativeDistances;

        ShapeData(List<Location> points, double[] cumulativeDistances) {
            this.points = points;
            this.cumulativeDistances = cumulativeDistances;
        }
    }

    /**
     * Stores the decoded polyline points for a trip's shape, and precomputes
     * cumulative distances for fast interpolation.
     */
    public synchronized void putShape(String tripId, List<Location> points) {
        if (tripId != null && points != null && !points.isEmpty()) {
            shapeCache.put(tripId, points);
            shapeCumDistCache.put(tripId, DistanceExtrapolator.buildCumulativeDistances(points));
        }
    }

    /**
     * Returns the cached shape polyline points for the given trip, or null if not cached.
     */
    public synchronized List<Location> getShape(String tripId) {
        return shapeCache.get(tripId);
    }

    /**
     * Returns the precomputed cumulative distance array for the trip's shape,
     * or null if not cached.
     */
    public synchronized double[] getShapeCumulativeDistances(String tripId) {
        return shapeCumDistCache.get(tripId);
    }

    /**
     * Returns both the shape points and cumulative distances atomically,
     * or null if neither is cached. Prevents callers from seeing inconsistent
     * shape/cumDist pairs across two separate calls.
     */
    public synchronized ShapeData getShapeWithDistances(String tripId) {
        List<Location> points = shapeCache.get(tripId);
        double[] cumDist = shapeCumDistCache.get(tripId);
        if (points == null || cumDist == null) return null;
        return new ShapeData(points, cumDist);
    }

    // --- Route type cache ---

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

    // --- Active trip ID tracking ---

    /**
     * Stores the last active trip ID reported by the server for the given
     * queried trip ID. Callers set this when they process API responses.
     */
    public synchronized void putLastActiveTripId(String polledTripId, String activeTripId) {
        if (polledTripId != null) {
            mLastActiveTripId.put(polledTripId, activeTripId);
        }
    }

    /**
     * Returns the last active trip ID the server reported for a queried trip,
     * or null if no response has been processed yet.
     */
    public synchronized String getLastActiveTripId(String polledTripId) {
        return mLastActiveTripId.get(polledTripId);
    }

    // --- Clear ---

    /**
     * Clears all data caches.
     */
    public synchronized void clearAll() {
        repository.clearAll();
        scheduleCache.clear();
        serviceDateCache.clear();
        shapeCache.clear();
        shapeCumDistCache.clear();
        routeTypeCache.clear();
        mLastActiveTripId.clear();
    }
}
