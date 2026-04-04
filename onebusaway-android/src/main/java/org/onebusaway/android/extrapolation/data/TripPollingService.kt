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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.withContext
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.io.request.ObaResponse
import org.onebusaway.android.io.request.ObaTripDetailsRequest
import org.onebusaway.android.io.request.ObaTripDetailsResponse
import org.onebusaway.android.io.request.ObaTripsForRouteRequest
import org.onebusaway.android.io.request.ObaTripsForRouteResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * System-wide polling service for OBA trip data. Components subscribe to routes
 * or individual trips; the service runs a single tick loop that fetches data
 * and records it into [TripDataManager].
 *
 * Collect [routeUpdates] or [tripUpdates] for Kotlin Flow access.
 * Java callers use [subscribeRoute]/[subscribeTripDetails] which bridge flows
 * to callbacks/collection jobs.
 */
object TripPollingService {

    private const val TAG = "TripPollingService"
    private const val TICK_INTERVAL_MS = 10_000L

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
            isTicking = tickJob.isActive,
            lastTickTimeMs = lastTickTimeMs,
            subscribedRouteIds = subscribedRouteIds.toSet(),
            subscribedTripIds = tripRefCounts.keys.toSet()
    )

    sealed class FetchResult<out T : ObaResponse> {
        abstract val localTimeMs: Long

        data class Success<T : ObaResponse>(
                val response: T,
                override val localTimeMs: Long
        ) : FetchResult<T>() {
            val serverTimeMs: Long get() = response.currentTime
        }

        data class ApiError(
                val code: Int,
                val text: String?,
                override val localTimeMs: Long
        ) : FetchResult<Nothing>()

        data class TransportError(
                val message: String?,
                override val localTimeMs: Long
        ) : FetchResult<Nothing>()
    }

    private fun <T : ObaResponse> classify(response: T, localTimeMs: Long): FetchResult<T> =
        when (response.code) {
            ObaApi.OBA_OK -> FetchResult.Success(response, localTimeMs)
            ObaApi.OBA_IO_EXCEPTION -> FetchResult.TransportError(response.text, localTimeMs)
            else -> FetchResult.ApiError(response.code, response.text, localTimeMs)
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val tickJob: Job = scope.launch {
        while (isActive) {
            if (hasSubscriptions()) {
                val elapsed = measureTimeMillis {
                    lastTickTimeMs = System.currentTimeMillis()
                    val coveredTripIds = pollRoutes()
                    pollTrips(coveredTripIds)
                }
                delay((TICK_INTERVAL_MS - elapsed).coerceAtLeast(0))
            } else {
                wakeUp.receive()
            }
        }
    }

    // --- Shared flows ---
    // TODO: replay=0 means the initial fetch in tripUpdates (and any emission before the
    //  collector is ready) is lost to the flow consumer. Consider replay=1 or emitting
    //  the initial result directly into the returned flow before subscribing to the SharedFlow.

    private val _routeResponses = MutableSharedFlow<Pair<String, ObaTripsForRouteResponse>>(
            extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _tripResponses = MutableSharedFlow<Pair<String, ObaTripDetailsResponse>>(
            extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Observe route poll responses. Collecting registers the route; cancelling unregisters it. */
    fun routeUpdates(routeId: String): Flow<ObaTripsForRouteResponse> = flow {
        subscribedRouteIds.add(routeId)
        wakeUp.trySend(Unit)
        try {
            _routeResponses
                    .filter { it.first == routeId }
                    .map { it.second }
                    .collect { emit(it) }
        } finally {
            subscribedRouteIds.remove(routeId)
        }
    }

    /**
     * Observe trip poll responses for a specific trip. Collecting this flow
     * registers the trip for polling; cancelling collection unregisters it.
     * The first collector triggers an immediate fetch.
     */
    fun tripUpdates(tripId: String): Flow<ObaTripDetailsResponse> = flow {
        val isFirst = addTripRef(tripId)
        wakeUp.trySend(Unit)
        if (isFirst) {
            val response = fetchTripDetails(tripId)
            handleTripResult(tripId, classify(response, System.currentTimeMillis()))
        }
        try {
            _tripResponses
                    .filter { it.first == tripId }
                    .map { it.second }
                    .collect { emit(it) }
        } finally {
            removeTripRef(tripId)
        }
    }

    /** Java callback interface for route responses. */
    fun interface RouteCallback {
        fun onResponse(response: ObaTripsForRouteResponse)
    }

    // --- Route subscriptions ---
    private val subscribedRouteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // --- Trip subscriptions (refcounted via flow lifecycle) ---
    private val tripRefCounts = ConcurrentHashMap<String, Int>()

    private fun addTripRef(tripId: String): Boolean =
            tripRefCounts.compute(tripId) { _, c -> (c ?: 0) + 1 } == 1

    private fun removeTripRef(tripId: String) {
        tripRefCounts.compute(tripId) { _, c ->
            if (c == null || c <= 1) null else c - 1
        }
    }

    // --- Public API ---

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
            val response = fetchTripDetails(tripId)
            handleTripResult(tripId, classify(response, System.currentTimeMillis()))
        }
    }

    // --- Result handlers ---

    private suspend fun handleRouteResult(
            routeId: String,
            result: FetchResult<ObaTripsForRouteResponse>
    ): Set<String> {
        if (result !is FetchResult.Success) return emptySet()
        TripDataManager.recordTripsForRouteResponse(result.response, result.localTimeMs)
        _routeResponses.emit(routeId to result.response)
        return result.response.trips
                .mapNotNull { it.status?.activeTripId }
                .toSet()
    }

    private suspend fun handleTripResult(
            tripId: String,
            result: FetchResult<ObaTripDetailsResponse>
    ) {
        if (result !is FetchResult.Success) return
        TripDataManager.recordTripDetailsResponse(
                tripId, result.response, result.localTimeMs)
        _tripResponses.emit(tripId to result.response)
    }

    // --- Polling ---
    // TODO: Routes and trips are fetched sequentially as an ad-hoc throttling strategy.
    //  Replace with parallel fetches (async/awaitAll) gated by a real concurrency limit.

    private suspend fun pollRoutes(): Set<String> {
        val coveredTripIds = mutableSetOf<String>()
        for (routeId in subscribedRouteIds) {
            try {
                val response = fetchTripsForRoute(routeId)
                coveredTripIds += handleRouteResult(routeId,
                        classify(response, System.currentTimeMillis()))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch trips for route $routeId", e)
            }
        }
        return coveredTripIds
    }

    private suspend fun pollTrips(coveredTripIds: Set<String>) {
        for (tripId in tripRefCounts.keys) {
            if (tripId in coveredTripIds) continue
            try {
                val response = fetchTripDetails(tripId)
                handleTripResult(tripId, classify(response, System.currentTimeMillis()))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch trip details for $tripId", e)
            }
        }
    }

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

    // --- Lifecycle ---

    private fun hasSubscriptions() =
            subscribedRouteIds.isNotEmpty() || tripRefCounts.isNotEmpty()
}
