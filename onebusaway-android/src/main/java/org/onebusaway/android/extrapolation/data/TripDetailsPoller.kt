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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.request.ObaTripDetailsRequest

/**
 * Polls the trip-details API at a fixed interval on a single background thread,
 * recording responses into [TripDataManager]. Start/stop with lifecycle.
 */
class TripDetailsPoller(
        private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    companion object {
        private const val TAG = "TripDetailsPoller"
        private const val DEFAULT_INTERVAL_MS = 10_000L
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null
    private var tripId: String? = null

    fun start(tripId: String) {
        this.tripId = tripId
        if (future == null) {
            future = executor.scheduleWithFixedDelay(::poll, 0, intervalMs, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        future?.cancel(false)
        future = null
    }

    private fun poll() {
        val tid = tripId ?: return
        try {
            val ctx = Application.get().applicationContext
            val response = ObaTripDetailsRequest.newRequest(ctx, tid).call()
            if (response != null) {
                TripDataManager.recordTripDetailsResponse(tid, response)
            } else {
                Log.d(TAG, "Null response polling trip details for $tid")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll trip details for $tid", e)
        }
    }
}
