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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.io.request.ObaTripDetailsRequest
import org.onebusaway.android.io.request.ObaTripDetailsResponse
import org.onebusaway.android.io.request.ObaTripsForRouteRequest
import org.onebusaway.android.io.request.ObaTripsForRouteResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * System-wide polling service for OBA trip data. Components subscribe to routes
 * or individual trips; each subscription gets its own polling loop that fetches
 * data and records it into [TripDataManager].
 *
 * Collect [routeUpdates] or [tripUpdates] for Kotlin Flow access.
 * Java callers use [subscribeRoute]/[subscribeTripDetails] which bridge flows
 * to callbacks/collection jobs.
 */
object TripPollingService {

    private const val TAG = "TripPollingService"
    private const val TICK_INTERVAL_MS = 10_000L
    private const val STOP_TIMEOUT_MS = 5_000L

    @Volatile
    var lastTickTimeMs = 0L
        private set

    data class PollingSnapshot(
            val isTicking: Boolean,
            val lastTickTimeMs: Long,
            val subscribedRouteIds: Set<String>,
            val subscribedTripIds: Set<String>
    )

    @JvmStatic
    fun getSnapshot(): PollingSnapshot = PollingSnapshot(
            isTicking = activeRouteIds.isNotEmpty() || activeTripIds.isNotEmpty(),
            lastTickTimeMs = lastTickTimeMs,
            subscribedRouteIds = activeRouteIds.toSet(),
            subscribedTripIds = activeTripIds.toSet()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Active ID tracking (for getSnapshot) ---

    private val activeRouteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val activeTripIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // --- Per-ID SharedFlow caches ---

    private val routeFlows = ConcurrentHashMap<String, SharedFlow<ObaTripsForRouteResponse>>()
    private val tripFlows = ConcurrentHashMap<String, SharedFlow<ObaTripDetailsResponse>>()

    private fun getOrCreateRouteFlow(routeId: String): SharedFlow<ObaTripsForRouteResponse> =
            routeFlows.computeIfAbsent(routeId) { id ->
                flow {
                    while (true) {
                        try {
                            val localTimeMs = System.currentTimeMillis()
                            val response = fetchTripsForRoute(id)
                            if (response.code == ObaApi.OBA_OK) {
                                lastTickTimeMs = localTimeMs
                                TripDataManager.recordTripsForRouteResponse(response, localTimeMs)
                                emit(response)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to fetch trips for route $id", e)
                        }
                        delay(TICK_INTERVAL_MS)
                    }
                }
                .onStart { activeRouteIds.add(id) }
                .onCompletion { activeRouteIds.remove(id); routeFlows.remove(id) }
                .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)
            }

    private fun getOrCreateTripFlow(tripId: String): SharedFlow<ObaTripDetailsResponse> =
            tripFlows.computeIfAbsent(tripId) { id ->
                flow {
                    while (true) {
                        try {
                            val localTimeMs = System.currentTimeMillis()
                            val response = fetchTripDetails(id)
                            if (response.code == ObaApi.OBA_OK) {
                                lastTickTimeMs = localTimeMs
                                TripDataManager.recordTripDetailsResponse(id, response, localTimeMs)
                                emit(response)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to fetch trip details for $id", e)
                        }
                        delay(TICK_INTERVAL_MS)
                    }
                }
                .onStart { activeTripIds.add(id) }
                .onCompletion { activeTripIds.remove(id); tripFlows.remove(id) }
                .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)
            }

    // --- Public API ---

    /** Observe route poll responses. Collecting keeps the route polled; cancelling stops it. */
    fun routeUpdates(routeId: String): Flow<ObaTripsForRouteResponse> =
            getOrCreateRouteFlow(routeId)

    /** Observe trip poll responses. Collecting keeps the trip polled; cancelling stops it. */
    fun tripUpdates(tripId: String): Flow<ObaTripDetailsResponse> =
            getOrCreateTripFlow(tripId)

    /** Java callback interface for route responses. */
    fun interface RouteCallback {
        fun onResponse(response: ObaTripsForRouteResponse)
    }

    /** Subscribe to route updates with a Java-compatible callback. Caller cancels the returned Job to unsubscribe. */
    @JvmStatic
    fun subscribeRoute(routeId: String, callback: RouteCallback): Job =
            scope.launch {
                routeUpdates(routeId).collect { response ->
                    withContext(Dispatchers.Main) { callback.onResponse(response) }
                }
            }

    /** Launch a flow collection job for [tripUpdates]. Caller cancels the returned Job to unsubscribe. */
    @JvmStatic
    fun subscribeTripDetails(tripId: String): Job =
            scope.launch { tripUpdates(tripId).collect { } }

    /** Immediate one-shot fetch for a single trip (e.g. refresh button). */
    @JvmStatic
    fun fetchNow(tripId: String) {
        scope.launch {
            try {
                val localTimeMs = System.currentTimeMillis()
                val response = fetchTripDetails(tripId)
                if (response.code == ObaApi.OBA_OK) {
                    TripDataManager.recordTripDetailsResponse(tripId, response, localTimeMs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch trip details for $tripId (fetchNow)", e)
            }
        }
    }

    // --- Network calls ---

    private suspend fun fetchTripsForRoute(routeId: String): ObaTripsForRouteResponse {
        val ctx = Application.get().applicationContext
        return ObaTripsForRouteRequest.Builder(ctx, routeId)
                .setIncludeStatus(true)
                .build()
                .call()
    }

    private suspend fun fetchTripDetails(tripId: String): ObaTripDetailsResponse {
        val ctx = Application.get().applicationContext
        return ObaTripDetailsRequest.newRequest(ctx, tripId).call()
    }
}
