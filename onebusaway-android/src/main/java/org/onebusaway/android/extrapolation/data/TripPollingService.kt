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

import android.text.TextUtils
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * - Route subscriptions use the batch trips-for-route endpoint
 * - Trip subscriptions use the single trip-details endpoint
 * - Trips already covered by a batch response are not fetched individually
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
            val subscribedTripIds: Map<String, Int>
    )

    @JvmStatic
    fun getSnapshot(): PollingSnapshot = PollingSnapshot(
            isTicking = tickJob?.isActive == true,
            lastTickTimeMs = lastTickTimeMs,
            subscribedRouteIds = routeSubscriptions.keys.toSet(),
            subscribedTripIds = tripRefCounts.toMap()
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

    private fun <T : ObaResponse> classify(response: T?, localTimeMs: Long): FetchResult<T>? {
        if (response == null) return null
        return when (response.code) {
            ObaApi.OBA_OK -> FetchResult.Success(response, localTimeMs)
            ObaApi.OBA_IO_EXCEPTION -> FetchResult.TransportError(response.text, localTimeMs)
            else -> FetchResult.ApiError(response.code, response.text, localTimeMs)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickJob: Job? = null

    fun interface RouteCallback {
        fun onResponse(response: ObaTripsForRouteResponse)
    }

    // --- Route subscriptions ---
    private data class RouteSubscription(
            val routeId: String,
            val callback: RouteCallback
    )
    private val routeSubscriptions = ConcurrentHashMap<String, RouteSubscription>()

    // --- Trip subscriptions (refcounted) ---
    private val tripRefCounts = ConcurrentHashMap<String, Int>()

    // --- Public API ---

    @JvmStatic
    fun subscribeRoute(routeId: String, callback: RouteCallback) {
        routeSubscriptions[routeId] = RouteSubscription(routeId, callback)
        ensureTicking()
    }

    @JvmStatic
    fun unsubscribeRoute(routeId: String) {
        routeSubscriptions.remove(routeId)
    }

    @JvmStatic
    fun subscribeTripDetails(tripId: String) {
        val isNew = tripRefCounts.compute(tripId) { _, count -> (count ?: 0) + 1 } == 1
        ensureTicking()
        if (isNew) fetchNow(tripId)
    }

    @JvmStatic
    fun unsubscribeTripDetails(tripId: String) {
        tripRefCounts.compute(tripId) { _, count ->
            if (count == null || count <= 1) null else count - 1
        }
    }

    /** Immediate one-shot fetch for a single trip (e.g. refresh button). */
    @JvmStatic
    fun fetchNow(tripId: String) {
        scope.launch {
            handleTripResult(tripId, fetchTripDetails(tripId))
        }
    }

    // --- Result handlers ---

    private suspend fun handleRouteResult(
            sub: RouteSubscription,
            result: FetchResult<ObaTripsForRouteResponse>?
    ): Set<String> {
        if (result !is FetchResult.Success) return emptySet()
        TripDataManager.recordTripsForRouteResponse(result.response, result.localTimeMs)
        withContext(Dispatchers.Main) {
            sub.callback.onResponse(result.response)
        }
        return result.response.trips
                .mapNotNull { it.status?.activeTripId }
                .toSet()
    }

    private fun handleTripResult(
            tripId: String,
            result: FetchResult<ObaTripDetailsResponse>?
    ) {
        if (result !is FetchResult.Success) return
        TripDataManager.recordTripDetailsResponse(
                tripId, result.response, result.localTimeMs)
    }

    // --- Polling ---

    private suspend fun pollRoutes(): Set<String> {
        val coveredTripIds = mutableSetOf<String>()
        for (sub in routeSubscriptions.values) {
            try {
                coveredTripIds += handleRouteResult(sub, fetchTripsForRoute(sub.routeId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch trips for route ${sub.routeId}", e)
            }
        }
        return coveredTripIds
    }

    private suspend fun pollTrips(coveredTripIds: Set<String>) {
        for (tripId in tripRefCounts.keys) {
            if (tripId in coveredTripIds) continue
            try {
                handleTripResult(tripId, fetchTripDetails(tripId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch trip details for $tripId", e)
            }
        }
    }

    private suspend fun fetchTripsForRoute(routeId: String): FetchResult<ObaTripsForRouteResponse>? {
        if (Application.get().currentRegion == null
                && TextUtils.isEmpty(Application.get().customApiUrl)) {
            return null
        }
        val ctx = Application.get().applicationContext
        val response = ObaTripsForRouteRequest.Builder(ctx, routeId)
                .setIncludeStatus(true)
                .build()
                .call()
        return classify(response, System.currentTimeMillis())
    }

    private suspend fun fetchTripDetails(tripId: String): FetchResult<ObaTripDetailsResponse>? {
        val ctx = Application.get().applicationContext
        val response = ObaTripDetailsRequest.newRequest(ctx, tripId).call()
        return classify(response, System.currentTimeMillis())
    }

    // --- Lifecycle ---

    private fun hasSubscriptions() =
            routeSubscriptions.isNotEmpty() || tripRefCounts.isNotEmpty()

    private fun ensureTicking() {
        if (tickJob?.isActive == true || !hasSubscriptions()) return
        tickJob = scope.launch {
            while (isActive && hasSubscriptions()) {
                lastTickTimeMs = System.currentTimeMillis()
                val coveredTripIds = pollRoutes()
                pollTrips(coveredTripIds)
                delay(TICK_INTERVAL_MS)
            }
        }
    }
}
