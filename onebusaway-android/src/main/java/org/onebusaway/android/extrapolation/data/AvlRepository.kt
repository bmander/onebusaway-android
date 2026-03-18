/*
 * Copyright (C) 2024-2026 Open Transit Software Foundation
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
package org.onebusaway.android.extrapolation.data

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Centralized AVL (Automatic Vehicle Location) data store. Owns all vehicle history entries and
 * supports queries by trip and vehicle. Thread-safe via a read-write lock: reads run concurrently,
 * writes are exclusive.
 */
object AvlRepository {

    private const val MAX_ENTRIES_PER_TRIP = 100
    private val lock = ReentrantReadWriteLock()

    /** Primary store: tripId → ordered list of vehicle states. */
    private val tripHistory = mutableMapOf<String, MutableList<VehicleState>>()

    /** Secondary index: vehicleId → set of tripIds that have data for this vehicle. */
    private val vehicleToTrips = mutableMapOf<String, MutableSet<String>>()

    /** Last recorded VehicleState per tripId. */
    private val lastStateCache = mutableMapOf<String, VehicleState>()

    /**
     * Records a vehicle state snapshot for a trip. Deduplicates by lastLocationUpdateTime — only
     * records when a genuinely new AVL report has arrived, filtering out server re-extrapolations.
     *
     * @param state the vehicle state snapshot (must have a non-null activeTripId)
     */
    fun record(state: VehicleState?) {
        if (state == null) return
        val tripId = state.activeTripId ?: return

        // Only record entries backed by real-time AVL data.
        if (!state.isPredicted) return

        val locUpdateTime = state.lastLocationUpdateTime
        if (locUpdateTime <= 0) return

        lock.write {
            val history = tripHistory.getOrPut(tripId) { mutableListOf() }

            // Skip if lastLocationUpdateTime hasn't advanced
            if (history.isNotEmpty() && locUpdateTime <= history.last().lastLocationUpdateTime) {
                return
            }

            history.add(state)

            // Cap history size
            if (history.size > MAX_ENTRIES_PER_TRIP) {
                history.subList(0, history.size - MAX_ENTRIES_PER_TRIP).clear()
            }

            lastStateCache[tripId] = state

            // Update secondary index
            val vehicleId = state.vehicleId
            if (vehicleId != null) {
                vehicleToTrips.getOrPut(vehicleId) { mutableSetOf() }.add(tripId)
            }
        }
    }

    // --- Trip-level queries ---

    /** Returns a read-only view of the history for the given trip. */
    fun getHistoryForTrip(tripId: String): List<VehicleState> =
            lock.read { tripHistory[tripId].orEmpty() }

    /** Returns the number of history entries for the given trip, without copying. */
    fun getHistorySizeForTrip(tripId: String): Int = lock.read { tripHistory[tripId]?.size ?: 0 }

    /** Returns the last cached VehicleState for the given trip, or null. */
    fun getLastState(tripId: String): VehicleState? = lock.read { lastStateCache[tripId] }

    // --- Vehicle-level queries ---

    /** Returns all history entries across all trips for the given vehicle, sorted by timestamp. */
    fun getHistoryForVehicle(vehicleId: String): List<VehicleState> =
            lock.read { vehicleToTrips[vehicleId]?.let(::mergeHistories).orEmpty() }

    /** Returns the set of trip IDs that have recorded data for the given vehicle. */
    fun getTripsForVehicle(vehicleId: String): Set<String> =
            lock.read { vehicleToTrips[vehicleId]?.toSet() ?: emptySet() }

    // --- Lifecycle ---

    /** Clears all stored data and indices. */
    fun clearAll() =
            lock.write {
                tripHistory.clear()
                lastStateCache.clear()
                vehicleToTrips.clear()
            }

    /**
     * Merges history lists from multiple trips into a single list sorted by timestamp. Must be
     * called under the read lock.
     */
    private fun mergeHistories(tripIds: Set<String>): List<VehicleState> =
            tripIds.flatMap { tripHistory[it] ?: emptyList() }.sortedBy { it.timestamp }
}
