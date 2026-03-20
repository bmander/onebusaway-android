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

import android.location.Location
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.bestDistanceAlongTrip
import org.onebusaway.android.io.request.ObaShapeRequest
import org.onebusaway.android.io.request.ObaTripDetailsRequest
import org.onebusaway.android.io.request.ObaTripDetailsResponse
import org.onebusaway.android.util.LocationUtils

/**
 * Singleton that owns all per-trip data storage: vehicle position history, route shapes, schedules,
 * service dates, route types, and active trip ID tracking. Also provides ensure* methods that
 * fetch data from the API in the background if not cached. Thread-safe via synchronized methods
 * and concurrent pending-fetch sets.
 */
object TripDataManager {

    private const val TAG = "TripDataManager"

    @JvmStatic fun getInstance() = this

    private val repository = AvlRepository
    private val fetchExecutor = Executors.newFixedThreadPool(2)
    private val pendingScheduleFetches: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingShapeFetches: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scheduleCache = HashMap<String, ObaTripSchedule>()
    private val serviceDateCache = HashMap<String, Long>()
    private val shapeCache = HashMap<String, List<Location>>()
    private val shapeCumDistCache = HashMap<String, DoubleArray>()
    private val routeTypeCache = HashMap<String, Int>()
    /** Last active trip ID reported by the server for each queried trip. */
    private val lastActiveTripId = HashMap<String, String?>()

    // --- Vehicle history ---

    /**
     * Records a trip status snapshot into the history. Delegates to [AvlRepository] for storage.
     */
    fun recordStatus(status: ObaTripStatus?) {
        if (status == null) return
        repository.record(status)
    }

    /**
     * Convenience method that extracts trip status, active trip ID, and service date from a trip
     * details response and records them.
     *
     * @param polledTripId the trip ID that was queried
     * @param response the API response
     */
    fun recordTripDetailsResponse(polledTripId: String?, response: ObaTripDetailsResponse?) {
        if (response == null) return
        val status = response.status ?: return
        putLastActiveTripId(polledTripId, status.activeTripId)
        status.activeTripId?.let { activeTripId ->
            recordStatus(status)
            if (status.serviceDate > 0) {
                putServiceDate(activeTripId, status.serviceDate)
            }
        }
    }

    /** Returns a read-only view of the history for the given trip. */
    fun getHistory(activeTripId: String?): List<ObaTripStatus> {
        if (activeTripId == null) return emptyList()
        return repository.getHistoryForTrip(activeTripId)
    }

    /** Returns the number of history entries for the given trip, without copying. */
    fun getHistorySize(activeTripId: String?): Int {
        if (activeTripId == null) return 0
        return repository.getHistorySizeForTrip(activeTripId)
    }

    /** Returns the last recorded ObaTripStatus for the given trip, or null. */
    fun getLastState(activeTripId: String?): ObaTripStatus? {
        if (activeTripId == null) return null
        return repository.getLastState(activeTripId)
    }

    /** Returns the newest entry with valid bestDistanceAlongTrip. O(1) cached lookup. */
    fun getNewestValidEntry(activeTripId: String?): ObaTripStatus? {
        if (activeTripId == null) return null
        return repository.getNewestValidEntry(activeTripId)
    }

    /** Returns the set of all trip IDs that have recorded AVL history. */
    fun getTrackedTripIds(): Set<String> = repository.getTrackedTripIds()

    /**
     * Returns a sequence of history entries with valid AVL fixes, newest first. Lazily evaluated
     * for efficient access to just the most recent N fixes.
     */
    fun mostRecentAvlFixes(tripId: String): Sequence<ObaTripStatus> =
            repository.getHistoryForTrip(tripId).asReversed().asSequence().filter {
                it.bestDistanceAlongTrip != null && it.lastLocationUpdateTime > 0
            }

    // --- Schedule cache ---

    /** Stores a trip schedule in the cache. */
    @Synchronized
    fun putSchedule(tripId: String?, schedule: ObaTripSchedule?) {
        if (tripId != null && schedule != null) {
            scheduleCache[tripId] = schedule
        }
    }

    /** Returns the cached schedule for the given trip, or null if not cached. */
    @Synchronized
    fun getSchedule(tripId: String): ObaTripSchedule? = tripId.let { scheduleCache[it] }

    /** Returns true if a schedule is cached for the given trip. */
    @Synchronized
    fun isScheduleCached(tripId: String?): Boolean =
            tripId != null && scheduleCache.containsKey(tripId)

    /** Fetches and caches the schedule in the background if not already cached or in-flight. */
    fun ensureSchedule(tripId: String) {
        if (isScheduleCached(tripId) || !pendingScheduleFetches.add(tripId)) return
        fetchExecutor.execute {
            try {
                val ctx = Application.get().applicationContext
                val response = ObaTripDetailsRequest.Builder(ctx, tripId)
                        .setIncludeSchedule(true)
                        .setIncludeStatus(false)
                        .setIncludeTrip(false)
                        .build()
                        .call()
                val schedule = response?.schedule
                if (schedule != null) {
                    putSchedule(tripId, schedule)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch schedule for $tripId", e)
            } finally {
                pendingScheduleFetches.remove(tripId)
            }
        }
    }

    // --- Service date cache ---

    /** Stores the service date for a trip. */
    @Synchronized
    fun putServiceDate(tripId: String?, serviceDate: Long) {
        if (tripId != null && serviceDate > 0) {
            serviceDateCache[tripId] = serviceDate
        }
    }

    /** Returns the cached service date for the given trip, or null if not cached. */
    @Synchronized fun getServiceDate(tripId: String?): Long? = tripId?.let { serviceDateCache[it] }

    // --- Shape cache ---

    /** Atomically readable shape data: polyline points and precomputed cumulative distances. */
    data class ShapeData(
            @JvmField val points: List<Location>,
            @JvmField val cumulativeDistances: DoubleArray
    )

    /**
     * Read-only snapshot of all per-trip data needed for extrapolation, fetched in a single
     * synchronized call. Avoids multiple separate lock acquisitions per frame.
     */
    data class TripSnapshot(
            @JvmField val shapeData: ShapeData?,
            @JvmField val routeType: Int?,
            @JvmField val lastState: ObaTripStatus?,
            @JvmField val newestValid: ObaTripStatus?,
            @JvmField val schedule: ObaTripSchedule?
    )

    /** Fetches all extrapolation-related data for a trip in one synchronized call. */
    @Synchronized
    fun getSnapshot(tripId: String): TripSnapshot {
        val points = shapeCache[tripId]
        val cumDist = shapeCumDistCache[tripId]
        val shapeData = if (points != null && cumDist != null) ShapeData(points, cumDist) else null
        return TripSnapshot(
                shapeData = shapeData,
                routeType = routeTypeCache[tripId],
                lastState = repository.getLastState(tripId),
                newestValid = repository.getNewestValidEntry(tripId),
                schedule = scheduleCache[tripId]
        )
    }

    /**
     * Stores the decoded polyline points for a trip's shape, and precomputes cumulative distances
     * for fast interpolation.
     */
    @Synchronized
    fun putShape(tripId: String?, points: List<Location>?) {
        if (tripId != null && points != null && points.isNotEmpty()) {
            shapeCache[tripId] = points
            shapeCumDistCache[tripId] = buildCumulativeDistances(points)
        }
    }

    /** Returns the cached shape polyline points for the given trip, or null if not cached. */
    @Synchronized fun getShape(tripId: String?): List<Location>? = tripId?.let { shapeCache[it] }

    /**
     * Returns the precomputed cumulative distance array for the trip's shape, or null if not
     * cached.
     */
    @Synchronized
    fun getShapeCumulativeDistances(tripId: String?): DoubleArray? =
            tripId?.let { shapeCumDistCache[it] }

    /**
     * Returns both the shape points and cumulative distances atomically, or null if neither is
     * cached.
     */
    @Synchronized
    fun getShapeWithDistances(tripId: String): ShapeData? {
        val points = shapeCache[tripId] ?: return null
        val cumDist = shapeCumDistCache[tripId] ?: return null
        return ShapeData(points, cumDist)
    }

    /** Fetches and caches the shape in the background if not already cached or in-flight. */
    fun ensureShape(tripId: String, shapeId: String) {
        if (getShape(tripId) != null || !pendingShapeFetches.add(tripId)) return
        fetchExecutor.execute {
            try {
                val ctx = Application.get().applicationContext
                val response = ObaShapeRequest.newRequest(ctx, shapeId).call()
                val points = response?.points
                if (points != null && points.isNotEmpty()) {
                    putShape(tripId, points)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch shape for $tripId", e)
            } finally {
                pendingShapeFetches.remove(tripId)
            }
        }
    }

    // --- Route type cache ---

    /** Stores the route type for a trip ID. */
    @Synchronized
    fun putRouteType(tripId: String, type: Int) {
        routeTypeCache[tripId] = type
    }

    /** Returns the cached route type for the given trip, or null if not cached. */
    @Synchronized fun getRouteType(tripId: String): Int? = routeTypeCache[tripId]

    // --- Active trip ID tracking ---

    /** Stores the last active trip ID reported by the server for the given queried trip ID. */
    @Synchronized
    fun putLastActiveTripId(polledTripId: String?, activeTripId: String?) {
        if (polledTripId != null) {
            lastActiveTripId[polledTripId] = activeTripId
        }
    }

    /**
     * Returns the last active trip ID the server reported for a queried trip, or null if no
     * response has been processed yet.
     */
    @Synchronized
    fun getLastActiveTripId(polledTripId: String): String? =
            lastActiveTripId[polledTripId]

    // --- Clear ---

    /** Clears all data caches. */
    @Synchronized
    fun clearAll() {
        repository.clearAll()
        scheduleCache.clear()
        serviceDateCache.clear()
        shapeCache.clear()
        shapeCumDistCache.clear()
        routeTypeCache.clear()
        lastActiveTripId.clear()
        pendingScheduleFetches.clear()
        pendingShapeFetches.clear()
    }

    // --- Private helpers ---

    /**
     * Builds a cumulative distance array for a polyline. Entry i holds the total distance from the
     * first point to point i. Entry 0 is always 0.
     *
     * Uses the same Haversine formula and Earth radius as the OBA server (SphericalGeometryLibrary)
     * so that distance values are consistent with the server's distanceAlongTrip values.
     *
     * @param polylinePoints decoded polyline points
     * @return cumulative distance array (same length as polylinePoints), or null
     */
    private fun buildCumulativeDistances(polylinePoints: List<Location>) =
            polylinePoints
                    .zipWithNext { prev, cur ->
                        LocationUtils.haversineDistance(
                                prev.latitude,
                                prev.longitude,
                                cur.latitude,
                                cur.longitude
                        )
                    }
                    .runningFold(0.0) { acc, dist -> acc + dist }
                    .toDoubleArray()
}
