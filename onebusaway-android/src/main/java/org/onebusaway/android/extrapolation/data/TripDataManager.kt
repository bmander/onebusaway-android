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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.request.ObaShapeRequest
import org.onebusaway.android.io.request.ObaTripsForRouteResponse
import org.onebusaway.android.io.request.ObaTripDetailsRequest
import org.onebusaway.android.io.request.ObaTripDetailsResponse
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.Polyline

/**
 * Registry of [Trip] objects with background fetch support.
 * All per-trip data lives on Trip; this singleton manages the registry, eviction, and
 * background fetches for schedules and shapes.
 *
 * Thread-safe via a single @Synchronized lock on this object.
 */
object TripDataManager {

    private const val TAG = "TripDataManager"
    private const val MAX_TRACKED_TRIPS = 100
    private const val MAX_FETCH_FAILURES = 3

    @JvmStatic fun getInstance() = this

    private val fetchExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingScheduleFetches: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingShapeFetches: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scheduleFailures = ConcurrentHashMap<String, Int>()
    private val shapeFailures = ConcurrentHashMap<String, Int>()

    private val trips = LinkedHashMap<String, Trip>()

    // --- Change notifications ---

    private val _changes = MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits Unit whenever any mutation method runs. Coalesces bursts via DROP_OLDEST. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    // --- Trip registry ---

    @Synchronized
    fun getOrCreateTrip(tripId: String): Trip =
            trips.getOrPut(tripId) { Trip(tripId) }

    @Synchronized
    fun getTrip(tripId: String?): Trip? =
            if (tripId != null) trips[tripId] else null

    // --- Recording ---

    /**
     * Records a trip status snapshot. Always updates the extrapolation anchor.
     * Deduplicates history by distance — only adds an entry when the vehicle has moved.
     */
    @Synchronized
    fun recordStatus(status: ObaTripStatus?, serverTimeMs: Long, localTimeMs: Long) {
        if (status == null) return
        val tripId = status.activeTripId ?: return
        getOrCreateTrip(tripId).recordStatus(status, serverTimeMs, localTimeMs)
        evictOldTripsIfNeeded()
        notifyChanged()
    }

    @Synchronized
    fun recordTripDetailsResponse(
            polledTripId: String?, response: ObaTripDetailsResponse?, localTimeMs: Long) {
        if (response == null) return
        val status = response.status ?: return
        if (polledTripId != null) {
            val polledTrip = getOrCreateTrip(polledTripId)
            polledTrip.lastActiveTripId = status.activeTripId
            polledTrip.tripDetailsResponse = response
        }
        val activeTripId = status.activeTripId ?: return
        val trip = getOrCreateTrip(activeTripId)
        trip.recordStatus(status, response.currentTime, localTimeMs)
        if (status.serviceDate > 0) {
            trip.serviceDate = status.serviceDate
        }
        evictOldTripsIfNeeded()
        notifyChanged()
    }

    fun recordTripsForRouteResponse(response: ObaTripsForRouteResponse, localTimeMs: Long) {
        val serverTime = response.currentTime
        for (tripDetails in response.trips) {
            val status = tripDetails.status ?: continue
            val activeTrip = response.getTrip(status.activeTripId) ?: continue
            val tripId = status.activeTripId ?: continue

            synchronized(this) {
                val trip = getOrCreateTrip(tripId)
                trip.recordStatus(status, serverTime, localTimeMs)
                if (status.serviceDate > 0) trip.serviceDate = status.serviceDate
                if (trip.routeType == null) {
                    val routeId = activeTrip.routeId
                    val route = if (routeId != null) response.getRoute(routeId) else null
                    if (route != null) trip.routeType = route.type
                }
                evictOldTripsIfNeeded()
            }

            ensureSchedule(tripId)
            val shapeId = activeTrip.shapeId
            if (shapeId != null) {
                ensureShape(tripId, shapeId)
            }
        }
        notifyChanged()
    }

    // --- Delegating accessors (for callers not yet using Trip directly) ---

    data class HistorySnapshot(
            val history: List<ObaTripStatus>,
            val fetchTimes: List<Long>,
            val localFetchTimes: List<Long>
    ) {
        companion object {
            val EMPTY = HistorySnapshot(emptyList(), emptyList(), emptyList())
        }
    }

    @Synchronized
    fun getHistorySnapshot(activeTripId: String?): HistorySnapshot {
        val trip = getTrip(activeTripId) ?: return HistorySnapshot.EMPTY
        return HistorySnapshot(
                trip.history.toList(),
                trip.fetchTimes.toList(),
                trip.localFetchTimes.toList()
        )
    }

    @Synchronized
    fun getHistory(activeTripId: String?): List<ObaTripStatus> =
            getTrip(activeTripId)?.history?.toList().orEmpty()

    @Synchronized
    fun getHistorySize(activeTripId: String?): Int =
            getTrip(activeTripId)?.history?.size ?: 0

    @Synchronized
    fun getFetchTimes(activeTripId: String?): List<Long> =
            getTrip(activeTripId)?.fetchTimes?.toList().orEmpty()

    @Synchronized
    fun getLocalFetchTimes(activeTripId: String?): List<Long> =
            getTrip(activeTripId)?.localFetchTimes?.toList().orEmpty()

    @Synchronized
    fun getLastState(activeTripId: String?): ObaTripStatus? =
            getTrip(activeTripId)?.history?.lastOrNull()

    @Synchronized
    fun getTrackedTripIds(): Set<String> = trips.keys.toSet()

    // --- Trip details response cache ---

    @Synchronized
    fun getTripDetails(tripId: String): ObaTripDetailsResponse? =
            getTrip(tripId)?.tripDetailsResponse

    // --- Schedule ---

    @Synchronized
    fun putSchedule(tripId: String?, schedule: ObaTripSchedule?) {
        if (tripId != null && schedule != null) {
            getOrCreateTrip(tripId).schedule = schedule
            notifyChanged()
        }
    }

    @Synchronized
    fun getSchedule(tripId: String): ObaTripSchedule? = getTrip(tripId)?.schedule

    @Synchronized
    fun isScheduleCached(tripId: String?): Boolean =
            tripId != null && getTrip(tripId)?.schedule != null

    fun ensureSchedule(tripId: String) {
        if (isScheduleCached(tripId) || !pendingScheduleFetches.add(tripId)) return
        if ((scheduleFailures[tripId] ?: 0) >= MAX_FETCH_FAILURES) return
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
                    scheduleFailures.remove(tripId)
                } else {
                    Log.d(TAG, "Schedule fetch for $tripId returned no schedule data")
                    scheduleFailures.merge(tripId, 1, Int::plus)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch schedule for $tripId", e)
                scheduleFailures.merge(tripId, 1, Int::plus)
            } finally {
                pendingScheduleFetches.remove(tripId)
            }
        }
    }

    // --- Service date ---

    @Synchronized
    fun putServiceDate(tripId: String?, serviceDate: Long) {
        if (tripId != null && serviceDate > 0) {
            getOrCreateTrip(tripId).serviceDate = serviceDate
            notifyChanged()
        }
    }

    @Synchronized
    fun getServiceDate(tripId: String?): Long? =
            getTrip(tripId)?.serviceDate?.let { if (it > 0) it else null }

    // --- Shape ---

    @Synchronized
    fun putShape(tripId: String?, points: List<Location>?) {
        if (tripId != null && points != null && points.isNotEmpty()) {
            getOrCreateTrip(tripId).polyline = Polyline(points)
            notifyChanged()
        }
    }

    @Synchronized fun getShape(tripId: String?): List<Location>? =
            getTrip(tripId)?.polyline?.points

    @Synchronized fun getPolyline(tripId: String): Polyline? = getTrip(tripId)?.polyline

    @JvmOverloads
    fun ensureShape(
            tripId: String,
            shapeId: String,
            onReady: ((Polyline) -> Unit)? = null,
            onError: (() -> Unit)? = null
    ) {
        val cached = getPolyline(tripId)
        if (cached != null) {
            if (onReady != null) mainHandler.post { onReady(cached) }
            return
        }
        if (!pendingShapeFetches.add(tripId)) return
        if ((shapeFailures[tripId] ?: 0) >= MAX_FETCH_FAILURES) {
            if (onError != null) mainHandler.post { onError() }
            return
        }
        fetchExecutor.execute {
            try {
                val ctx = Application.get().applicationContext
                val response = ObaShapeRequest.newRequest(ctx, shapeId).call()
                val points = response?.points
                if (points != null && points.isNotEmpty()) {
                    putShape(tripId, points)
                    shapeFailures.remove(tripId)
                    if (onReady != null) {
                        val sd = getPolyline(tripId)
                        if (sd != null) mainHandler.post { onReady(sd) }
                    }
                } else {
                    Log.d(TAG, "Shape fetch for $tripId returned no points")
                    shapeFailures.merge(tripId, 1, Int::plus)
                    if (onError != null) mainHandler.post { onError() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch shape for $tripId", e)
                shapeFailures.merge(tripId, 1, Int::plus)
                if (onError != null) mainHandler.post { onError() }
            } finally {
                pendingShapeFetches.remove(tripId)
            }
        }
    }

    // --- Route type ---

    @Synchronized
    fun putRouteType(tripId: String, type: Int) {
        getOrCreateTrip(tripId).routeType = type
        notifyChanged()
    }

    @Synchronized fun getRouteType(tripId: String): Int? = getTrip(tripId)?.routeType

    // --- Active trip ID tracking ---

    @Synchronized
    fun putLastActiveTripId(polledTripId: String?, activeTripId: String?) {
        if (polledTripId != null) {
            getOrCreateTrip(polledTripId).lastActiveTripId = activeTripId
            notifyChanged()
        }
    }

    @Synchronized
    // TODO: Rename — "get trip id for trip id returning a different trip id" is confusing.
    //  This tracks when a vehicle switches trips (e.g. finishes one run, starts the next).
    //  The parameter is the trip we're watching; the return is what the vehicle is actually running.
    fun getLastActiveTripId(polledTripId: String): String? =
            getTrip(polledTripId)?.lastActiveTripId

    // --- Eviction and cleanup ---

    private fun evictOldTripsIfNeeded() {
        while (trips.size > MAX_TRACKED_TRIPS) {
            val oldest = trips.keys.iterator().next()
            trips.remove(oldest)
            pendingScheduleFetches.remove(oldest)
            pendingShapeFetches.remove(oldest)
        }
    }

    @Synchronized
    fun clearAll() {
        trips.clear()
        pendingScheduleFetches.clear()
        pendingShapeFetches.clear()
        notifyChanged()
    }
}
