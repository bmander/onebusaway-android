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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Singleton manager that tracks vehicle position history and estimates speed.
 * Thread-safe via synchronized methods.
 */
public final class VehicleSpeedTracker {

    private static final VehicleSpeedTracker INSTANCE = new VehicleSpeedTracker();

    private final Map<String, List<VehicleHistoryEntry>> historyMap = new HashMap<>();
    private final Map<String, ObaTripSchedule> scheduleCache = new HashMap<>();
    private final Set<String> pendingScheduleFetches = new HashSet<>();
    private SpeedEstimator estimator = new WeightedSpeedEstimator();

    private VehicleSpeedTracker() {
    }

    public static VehicleSpeedTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Records a vehicle state snapshot into the history for the given key (activeTripId).
     * Deduplicates by lastLocationUpdateTime — only records when a genuinely new AVL
     * report has arrived from the vehicle, filtering out server re-extrapolations.
     */
    public synchronized void recordState(String key, VehicleState state) {
        if (key == null || state == null) {
            return;
        }

        List<VehicleHistoryEntry> history = historyMap.get(key);
        if (history == null) {
            history = new ArrayList<>();
            historyMap.put(key, history);
        }

        // Skip AVL reports with no timestamp
        long locUpdateTime = state.getLastLocationUpdateTime();
        if (locUpdateTime <= 0) {
            return;
        }

        // Skip if lastLocationUpdateTime hasn't changed (same AVL report, re-extrapolated)
        if (!history.isEmpty()) {
            VehicleHistoryEntry last = history.get(history.size() - 1);
            if (last.getLastLocationUpdateTime() == locUpdateTime) {
                return;
            }
        }

        Location position = state.getLastKnownLocation();
        if (position == null) {
            position = state.getPosition();
        }

        history.add(new VehicleHistoryEntry(
                position,
                state.getDistanceAlongTrip(),
                state.getLastKnownDistanceAlongTrip(),
                locUpdateTime,
                state.getTimestamp()
        ));
    }

    /**
     * Returns a defensive copy of the history for the given key.
     */
    public synchronized List<VehicleHistoryEntry> getHistory(String key) {
        List<VehicleHistoryEntry> history = historyMap.get(key);
        if (history == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(history);
    }

    /**
     * Returns the estimated speed in m/s for the given key and current state.
     */
    public synchronized Double getEstimatedSpeed(String key, VehicleState state) {
        if (key == null || state == null) {
            return null;
        }
        return estimator.estimateSpeed(state.getVehicleId(), state, this);
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
     * Returns true if a schedule fetch is already pending or the schedule is cached.
     */
    public synchronized boolean isSchedulePendingOrCached(String tripId) {
        return scheduleCache.containsKey(tripId) || pendingScheduleFetches.contains(tripId);
    }

    /**
     * Marks a trip as having a pending schedule fetch.
     */
    public synchronized void markSchedulePending(String tripId) {
        if (tripId != null) {
            pendingScheduleFetches.add(tripId);
        }
    }

    /**
     * Clears all history data and schedule cache.
     */
    public synchronized void clearAll() {
        historyMap.clear();
        scheduleCache.clear();
        pendingScheduleFetches.clear();
    }
}
