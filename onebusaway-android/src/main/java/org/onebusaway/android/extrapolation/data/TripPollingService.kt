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

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.io.request.ObaResponse
import org.onebusaway.android.io.request.ObaTripDetailsRequest
import org.onebusaway.android.io.request.ObaTripDetailsResponse
import org.onebusaway.android.io.request.ObaTripsForRouteRequest
import org.onebusaway.android.io.request.ObaTripsForRouteResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
            isTicking = ticking,
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fetchExecutor = Executors.newSingleThreadExecutor()

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

    private var ticking = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!hasSubscriptions()) { ticking = false; return }
            fetchExecutor.execute { tick() }
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

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
        fetchExecutor.execute {
            val result = fetchTripDetails(tripId)
            if (result is FetchResult.Success) {
                TripDataManager.putTripDetails(tripId, result.response)
                TripDataManager.recordTripDetailsResponse(
                        tripId, result.response, result.localTimeMs)
            }
        }
    }

    // --- Tick ---

    private fun tick() {
        lastTickTimeMs = System.currentTimeMillis()
        val coveredTripIds = mutableSetOf<String>()

        // 1. Poll all subscribed routes (batch)
        for (sub in routeSubscriptions.values) {
            try {
                val result = fetchTripsForRoute(sub.routeId) ?: continue
                if (result !is FetchResult.Success) continue
                TripDataManager.recordTripsForRouteResponse(
                        result.response, result.localTimeMs)
                for (trip in result.response.trips) {
                    trip.status?.activeTripId?.let { coveredTripIds.add(it) }
                }
                mainHandler.post { sub.callback.onResponse(result.response) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch trips for route ${sub.routeId}", e)
            }
        }

        // 2. Poll individual trips not covered by batch responses
        for (tripId in tripRefCounts.keys) {
            if (tripId in coveredTripIds) continue
            try {
                val result = fetchTripDetails(tripId) ?: continue
                if (result is FetchResult.Success) {
                    TripDataManager.putTripDetails(tripId, result.response)
                    TripDataManager.recordTripDetailsResponse(
                            tripId, result.response, result.localTimeMs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch trip details for $tripId", e)
            }
        }
    }

    private fun fetchTripsForRoute(routeId: String): FetchResult<ObaTripsForRouteResponse>? {
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

    private fun fetchTripDetails(tripId: String): FetchResult<ObaTripDetailsResponse>? {
        val ctx = Application.get().applicationContext
        val response = ObaTripDetailsRequest.newRequest(ctx, tripId).call()
        return classify(response, System.currentTimeMillis())
    }

    // --- Lifecycle ---

    private fun hasSubscriptions() =
            routeSubscriptions.isNotEmpty() || tripRefCounts.isNotEmpty()

    private fun ensureTicking() {
        if (!ticking && hasSubscriptions()) {
            ticking = true
            // First tick immediately
            fetchExecutor.execute { tick() }
            mainHandler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
        }
    }
}
