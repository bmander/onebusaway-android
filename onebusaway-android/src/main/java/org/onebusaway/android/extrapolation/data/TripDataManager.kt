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
import android.os.Handler
import android.os.Looper
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
 * fetch data from the API in the background if not cached.
 *
 * Thread-safe via a single @Synchronized lock on this object. All mutable state lives here under
 * one lock to avoid nested locking and contention.
 */
object TripDataManager {

    private const val TAG = "TripDataManager"
    private const val MAX_ENTRIES_PER_TRIP = 100

    @JvmStatic fun getInstance() = this

    private val fetchExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingScheduleFetches: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingShapeFetches: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // --- AVL history ---
    private val tripHistory = mutableMapOf<String, MutableList<ObaTripStatus>>()
    private val newestValidEntry = mutableMapOf<String, ObaTripStatus>()

    // --- Caches ---
    private val tripDetailsCache = HashMap<String, ObaTripDetailsResponse>()
    private val scheduleCache = HashMap<String, ObaTripSchedule>()
    private val serviceDateCache = HashMap<String, Long>()
    private val shapeCache = HashMap<String, List<Location>>()
    private val shapeCumDistCache = HashMap<String, DoubleArray>()
    private val routeTypeCache = HashMap<String, Int>()
    /** Last active trip ID reported by the server for each queried trip. */
    private val lastActiveTripId = HashMap<String, String?>()

    // --- Vehicle history ---

    /**
     * Records a trip status snapshot into the history. Deduplicates by lastLocationUpdateTime —
     * only records when a genuinely new AVL report has arrived, filtering out server
     * re-extrapolations.
     */
    @Synchronized
    fun recordStatus(status: ObaTripStatus?) {
        if (status == null) return
        val tripId = status.activeTripId ?: return
        recordStatusInternal(status, tripId)
    }

    /** Core recording logic. Caller must already hold the lock. */
    private fun recordStatusInternal(status: ObaTripStatus, tripId: String) {
        if (!status.isPredicted) return

        val locUpdateTime = status.lastLocationUpdateTime
        if (locUpdateTime <= 0) return

        val history = tripHistory.getOrPut(tripId) { mutableListOf() }

        if (history.isNotEmpty() && locUpdateTime <= history.last().lastLocationUpdateTime) {
            return
        }

        history.add(status)

        if (status.bestDistanceAlongTrip != null) {
            newestValidEntry[tripId] = status
        }

        if (history.size > MAX_ENTRIES_PER_TRIP) {
            history.subList(0, history.size - MAX_ENTRIES_PER_TRIP).clear()
        }
    }

    /**
     * Extracts trip status, active trip ID, and service date from a response and records them
     * atomically.
     */
    @Synchronized
    fun recordTripDetailsResponse(polledTripId: String?, response: ObaTripDetailsResponse?) {
        if (response == null) return
        val status = response.status ?: return
        if (polledTripId != null) {
            lastActiveTripId[polledTripId] = status.activeTripId
        }
        val activeTripId = status.activeTripId ?: return
        recordStatusInternal(status, activeTripId)
        if (status.serviceDate > 0) {
            serviceDateCache[activeTripId] = status.serviceDate
        }
    }

    @Synchronized
    fun getHistory(activeTripId: String?): List<ObaTripStatus> {
        if (activeTripId == null) return emptyList()
        return tripHistory[activeTripId]?.toList().orEmpty()
    }

    @Synchronized
    fun getHistorySize(activeTripId: String?): Int {
        if (activeTripId == null) return 0
        return tripHistory[activeTripId]?.size ?: 0
    }

    @Synchronized
    fun getLastState(activeTripId: String?): ObaTripStatus? {
        if (activeTripId == null) return null
        return tripHistory[activeTripId]?.lastOrNull()
    }

    @Synchronized
    fun getNewestValidEntry(activeTripId: String?): ObaTripStatus? {
        if (activeTripId == null) return null
        return newestValidEntry[activeTripId]
    }

    @Synchronized
    fun getTrackedTripIds(): Set<String> = tripHistory.keys.toSet()

    // --- Trip details response cache ---

    @Synchronized
    fun putTripDetails(tripId: String, response: ObaTripDetailsResponse) {
        tripDetailsCache[tripId] = response
    }

    @Synchronized
    fun getTripDetails(tripId: String): ObaTripDetailsResponse? = tripDetailsCache[tripId]

    // --- Schedule cache ---

    @Synchronized
    fun putSchedule(tripId: String?, schedule: ObaTripSchedule?) {
        if (tripId != null && schedule != null) {
            scheduleCache[tripId] = schedule
        }
    }

    @Synchronized
    fun getSchedule(tripId: String): ObaTripSchedule? = scheduleCache[tripId]

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
                } else {
                    Log.d(TAG, "Schedule fetch for $tripId returned no schedule data")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch schedule for $tripId", e)
            } finally {
                pendingScheduleFetches.remove(tripId)
            }
        }
    }

    // --- Service date cache ---

    @Synchronized
    fun putServiceDate(tripId: String?, serviceDate: Long) {
        if (tripId != null && serviceDate > 0) {
            serviceDateCache[tripId] = serviceDate
        }
    }

    @Synchronized fun getServiceDate(tripId: String?): Long? = tripId?.let { serviceDateCache[it] }

    // --- Shape cache ---

    /** Atomically readable shape data: polyline points and precomputed cumulative distances. */
    data class ShapeData(
            @JvmField val points: List<Location>,
            @JvmField val cumulativeDistances: DoubleArray
    )

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

    /**
     * Fetches and caches the shape in the background if not already cached or in-flight.
     * If [onReady] is provided, it is invoked on the main thread with the [ShapeData] once
     * the shape is available. If the shape is already cached, [onReady] is called immediately.
     */
    @JvmOverloads
    fun ensureShape(tripId: String, shapeId: String, onReady: ((ShapeData) -> Unit)? = null) {
        val cached = getShapeWithDistances(tripId)
        if (cached != null) {
            onReady?.invoke(cached)
            return
        }
        if (!pendingShapeFetches.add(tripId)) return
        fetchExecutor.execute {
            try {
                val ctx = Application.get().applicationContext
                val response = ObaShapeRequest.newRequest(ctx, shapeId).call()
                val points = response?.points
                if (points != null && points.isNotEmpty()) {
                    putShape(tripId, points)
                    if (onReady != null) {
                        val sd = getShapeWithDistances(tripId)
                        if (sd != null) mainHandler.post { onReady(sd) }
                    }
                } else {
                    Log.d(TAG, "Shape fetch for $tripId returned no points")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch shape for $tripId", e)
            } finally {
                pendingShapeFetches.remove(tripId)
            }
        }
    }

    // --- Route type cache ---

    @Synchronized
    fun putRouteType(tripId: String, type: Int) {
        routeTypeCache[tripId] = type
    }

    @Synchronized fun getRouteType(tripId: String): Int? = routeTypeCache[tripId]

    // --- Active trip ID tracking ---

    @Synchronized
    fun putLastActiveTripId(polledTripId: String?, activeTripId: String?) {
        if (polledTripId != null) {
            lastActiveTripId[polledTripId] = activeTripId
        }
    }

    @Synchronized
    fun getLastActiveTripId(polledTripId: String): String? =
            lastActiveTripId[polledTripId]

    // --- Clear ---

    @Synchronized
    fun clearAll() {
        tripHistory.clear()
        newestValidEntry.clear()
        tripDetailsCache.clear()
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
