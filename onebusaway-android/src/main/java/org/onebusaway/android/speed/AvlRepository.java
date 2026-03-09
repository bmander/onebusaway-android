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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centralized AVL (Automatic Vehicle Location) data store.
 * Owns all vehicle history entries and supports queries by trip, vehicle, and block.
 * Thread-safe via synchronized methods.
 */
public final class AvlRepository {

    private static final AvlRepository INSTANCE = new AvlRepository();
    private static final int MAX_ENTRIES_PER_TRIP = 100;

    /** Primary store: tripId → ordered list of history entries. */
    private final Map<String, List<VehicleHistoryEntry>> tripHistory = new HashMap<>();

    /** Secondary index: vehicleId → set of tripIds that have data for this vehicle. */
    private final Map<String, Set<String>> vehicleToTrips = new HashMap<>();

    /** Secondary index: blockId → set of tripIds that have data for this block. */
    private final Map<String, Set<String>> blockToTrips = new HashMap<>();

    /** Last recorded VehicleState per tripId. */
    private final Map<String, VehicleState> lastStateCache = new HashMap<>();

    private AvlRepository() {
    }

    public static AvlRepository getInstance() {
        return INSTANCE;
    }

    /**
     * Records a vehicle state snapshot for a trip.
     * Deduplicates by lastLocationUpdateTime — only records when a genuinely new AVL
     * report has arrived, filtering out server re-extrapolations.
     *
     * @param tripId  the active trip ID (key for primary store)
     * @param state   the vehicle state snapshot
     * @param blockId the block ID, or null if unavailable
     */
    public synchronized void record(String tripId, VehicleState state, String blockId) {
        if (tripId == null || state == null) {
            return;
        }

        List<VehicleHistoryEntry> history = tripHistory.get(tripId);
        if (history == null) {
            history = new ArrayList<>();
            tripHistory.put(tripId, history);
        }

        long locUpdateTime = state.getLastLocationUpdateTime();
        if (locUpdateTime <= 0) {
            return;
        }

        // Skip if lastLocationUpdateTime hasn't advanced
        if (!history.isEmpty()) {
            VehicleHistoryEntry last = history.get(history.size() - 1);
            if (locUpdateTime <= last.getLastLocationUpdateTime()) {
                return;
            }
        }

        Location position = state.getLastKnownLocation();
        if (position == null) {
            position = state.getPosition();
        }

        String vehicleId = state.getVehicleId();

        history.add(new VehicleHistoryEntry(
                position,
                state.getDistanceAlongTrip(),
                state.getLastKnownDistanceAlongTrip(),
                locUpdateTime,
                state.getTimestamp(),
                vehicleId,
                tripId,
                blockId
        ));

        // Cap history size
        if (history.size() > MAX_ENTRIES_PER_TRIP) {
            history.subList(0, history.size() - MAX_ENTRIES_PER_TRIP).clear();
        }

        lastStateCache.put(tripId, state);

        // Update secondary indices
        if (vehicleId != null) {
            Set<String> trips = vehicleToTrips.get(vehicleId);
            if (trips == null) {
                trips = new HashSet<>();
                vehicleToTrips.put(vehicleId, trips);
            }
            trips.add(tripId);
        }
        if (blockId != null) {
            Set<String> trips = blockToTrips.get(blockId);
            if (trips == null) {
                trips = new HashSet<>();
                blockToTrips.put(blockId, trips);
            }
            trips.add(tripId);
        }
    }

    // --- Trip-level queries ---

    /**
     * Returns a defensive copy of the history for the given trip.
     */
    public synchronized List<VehicleHistoryEntry> getHistoryForTrip(String tripId) {
        List<VehicleHistoryEntry> history = tripHistory.get(tripId);
        if (history == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(history);
    }

    /**
     * Returns an unmodifiable view of the history for the given trip.
     * The caller must not hold a reference beyond the current call scope,
     * as the underlying list may be modified by future record() calls.
     * Suitable for read-only hot-path use (e.g., per-frame extrapolation).
     */
    public synchronized List<VehicleHistoryEntry> getHistoryForTripReadOnly(String tripId) {
        List<VehicleHistoryEntry> history = tripHistory.get(tripId);
        if (history == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(history);
    }

    /**
     * Returns the number of history entries for the given trip, without copying.
     */
    public synchronized int getHistorySizeForTrip(String tripId) {
        List<VehicleHistoryEntry> history = tripHistory.get(tripId);
        return history == null ? 0 : history.size();
    }

    /**
     * Returns the last cached VehicleState for the given trip, or null.
     */
    public synchronized VehicleState getLastState(String tripId) {
        return lastStateCache.get(tripId);
    }

    // --- Vehicle-level queries ---

    /**
     * Returns all history entries across all trips for the given vehicle,
     * sorted by timestamp.
     */
    public synchronized List<VehicleHistoryEntry> getHistoryForVehicle(String vehicleId) {
        Set<String> tripIds = vehicleToTrips.get(vehicleId);
        if (tripIds == null || tripIds.isEmpty()) {
            return Collections.emptyList();
        }
        return mergeHistories(tripIds);
    }

    /**
     * Returns the set of trip IDs that have recorded data for the given vehicle.
     */
    public synchronized Set<String> getTripsForVehicle(String vehicleId) {
        Set<String> trips = vehicleToTrips.get(vehicleId);
        if (trips == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(trips);
    }

    // --- Block-level queries ---

    /**
     * Returns all history entries across all trips for the given block,
     * sorted by timestamp.
     */
    public synchronized List<VehicleHistoryEntry> getHistoryForBlock(String blockId) {
        Set<String> tripIds = blockToTrips.get(blockId);
        if (tripIds == null || tripIds.isEmpty()) {
            return Collections.emptyList();
        }
        return mergeHistories(tripIds);
    }

    /**
     * Returns the set of trip IDs that have recorded data for the given block.
     */
    public synchronized Set<String> getTripsForBlock(String blockId) {
        Set<String> trips = blockToTrips.get(blockId);
        if (trips == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(trips);
    }

    // --- Lifecycle ---

    /**
     * Clears all stored data and indices.
     */
    public synchronized void clearAll() {
        tripHistory.clear();
        lastStateCache.clear();
        vehicleToTrips.clear();
        blockToTrips.clear();
    }

    /**
     * Merges history lists from multiple trips into a single list sorted by timestamp.
     */
    private List<VehicleHistoryEntry> mergeHistories(Set<String> tripIds) {
        List<VehicleHistoryEntry> merged = new ArrayList<>();
        for (String tid : tripIds) {
            List<VehicleHistoryEntry> h = tripHistory.get(tid);
            if (h != null) {
                merged.addAll(h);
            }
        }
        Collections.sort(merged,
                (a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));
        return merged;
    }
}
