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
import android.util.Log
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.request.ObaTripDetailsRequest
import org.onebusaway.android.io.request.ObaTripsForRouteResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralizes trip-details API polling. Tracks when each trip was last polled
 * (across all callers) to prevent duplicate fetches.
 *
 * Instances provide a repeating poll loop; static methods provide one-shot and
 * batch operations.
 */
class TripDetailsPoller(
        private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    companion object {
        private const val TAG = "TripDetailsPoller"
        private const val DEFAULT_INTERVAL_MS = 10_000L

        private val lastPolledMs = ConcurrentHashMap<String, Long>()
        private val fetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        /**
         * Returns true if the trip should be polled — i.e. it hasn't been polled
         * within [minIntervalMs]. Stamps the last-polled time if returning true.
         * Atomic: uses ConcurrentHashMap.compute to prevent race conditions.
         */
        @JvmStatic
        fun shouldPoll(tripId: String, minIntervalMs: Long = DEFAULT_INTERVAL_MS): Boolean {
            val now = System.currentTimeMillis()
            var allowed = false
            lastPolledMs.compute(tripId) { _, prev ->
                if (prev == null || now - prev >= minIntervalMs) {
                    allowed = true
                    now
                } else prev
            }
            return allowed
        }

        /** One-shot: fetches trip details on a background thread if not recently polled. */
        @JvmStatic
        fun fetchIfNeeded(tripId: String) {
            if (!shouldPoll(tripId)) return
            fetchExecutor.execute { fetchAndRecord(tripId) }
        }

        /** Clears poll tracking state. Call when TripDataManager is cleared. */
        @JvmStatic
        fun clearPollState() {
            lastPolledMs.clear()
        }

        /**
         * Records a batch trips-for-route response into TripDataManager and stamps
         * all trips so individual pollers skip them.
         */
        @JvmStatic
        fun recordBatchResponse(response: ObaTripsForRouteResponse) {
            TripDataManager.recordTripsForRouteResponse(response)
            for (trip in response.trips) {
                val tripId = trip.status?.activeTripId ?: continue
                lastPolledMs[tripId] = System.currentTimeMillis()
            }
        }

        private fun fetchAndRecord(tripId: String) {
            try {
                val ctx = Application.get().applicationContext
                val response = ObaTripDetailsRequest.newRequest(ctx, tripId).call()
                if (response != null) {
                    TripDataManager.recordTripDetailsResponse(tripId, response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch trip details for $tripId", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var tripId: String? = null
    private var running = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            tripId?.let { fetchIfNeeded(it) }
            if (running) handler.postDelayed(this, intervalMs)
        }
    }

    fun start(tripId: String) {
        this.tripId = tripId
        if (!running) {
            running = true
            handler.post(pollRunnable)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
    }
}
