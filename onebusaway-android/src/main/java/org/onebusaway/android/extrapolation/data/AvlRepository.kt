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
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.bestDistanceAlongTrip

/**
 * Centralized AVL (Automatic Vehicle Location) data store. Owns all vehicle history entries and
 * supports queries by trip. Thread-safe via a read-write lock: reads run concurrently, writes are
 * exclusive.
 */
object AvlRepository {

    private const val MAX_ENTRIES_PER_TRIP = 100
    private val lock = ReentrantReadWriteLock()

    /** Primary store: tripId -> ordered list of trip statuses. */
    private val tripHistory = mutableMapOf<String, MutableList<ObaTripStatus>>()

    /** Cached newest entry with valid bestDistanceAlongTrip, per trip. */
    private val newestValidEntry = mutableMapOf<String, ObaTripStatus>()

    /**
     * Records a trip status snapshot for a trip. Deduplicates by lastLocationUpdateTime -- only
     * records when a genuinely new AVL report has arrived, filtering out server re-extrapolations.
     *
     * @param status the trip status snapshot (must have a non-null activeTripId)
     */
    fun record(status: ObaTripStatus) {
        val tripId = status.activeTripId ?: return
        if (!status.isPredicted) return

        val locUpdateTime = status.lastLocationUpdateTime
        if (locUpdateTime <= 0) return

        lock.write {
            val history = tripHistory.getOrPut(tripId) { mutableListOf() }

            if (history.isNotEmpty() && locUpdateTime <= history.last().lastLocationUpdateTime) {
                return@write
            }

            history.add(status)

            // Update the cached newest-valid entry if the new status has distance data
            if (status.bestDistanceAlongTrip != null) {
                newestValidEntry[tripId] = status
            }

            if (history.size > MAX_ENTRIES_PER_TRIP) {
                history.subList(0, history.size - MAX_ENTRIES_PER_TRIP).clear()
            }
        }
    }

    // --- Trip-level queries ---

    /** Returns a snapshot of the history for the given trip. */
    fun getHistoryForTrip(tripId: String): List<ObaTripStatus> =
            lock.read { tripHistory[tripId]?.toList().orEmpty() }

    /** Returns the number of history entries for the given trip, without copying. */
    fun getHistorySizeForTrip(tripId: String): Int = lock.read { tripHistory[tripId]?.size ?: 0 }

    /** Returns the last recorded ObaTripStatus for the given trip, or null. */
    fun getLastState(tripId: String): ObaTripStatus? =
            lock.read { tripHistory[tripId]?.lastOrNull() }

    /** Returns the newest entry with valid bestDistanceAlongTrip, or null. O(1) cached lookup. */
    fun getNewestValidEntry(tripId: String): ObaTripStatus? =
            lock.read { newestValidEntry[tripId] }

    /** Returns the set of all trip IDs that have recorded history. */
    fun getTrackedTripIds(): Set<String> = lock.read { tripHistory.keys.toSet() }

    // --- Lifecycle ---

    /** Clears all stored data. */
    fun clearAll() = lock.write {
        tripHistory.clear()
        newestValidEntry.clear()
    }
}
