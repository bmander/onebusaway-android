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

/**
 * Polls the trip-details API on a background thread at a fixed interval,
 * recording responses into [TripDataManager]. Start/stop with lifecycle.
 */
class TripDetailsPoller(
        private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    companion object {
        private const val TAG = "TripDetailsPoller"
        private const val DEFAULT_INTERVAL_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var tripId: String? = null
    private val runnable = Runnable { poll() }

    fun start(tripId: String) {
        this.tripId = tripId
        if (!active) {
            active = true
            handler.postDelayed(runnable, intervalMs)
        }
    }

    fun stop() {
        active = false
        handler.removeCallbacks(runnable)
    }

    private fun poll() {
        if (!active) return
        val tid = tripId ?: return

        Thread {
            try {
                val ctx = Application.get().applicationContext
                val response = ObaTripDetailsRequest.newRequest(ctx, tid).call()
                if (response != null) {
                    TripDataManager.recordTripDetailsResponse(tid, response)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to poll trip details for $tid", e)
            }
            handler.post {
                if (active) {
                    handler.postDelayed(runnable, intervalMs)
                }
            }
        }.start()
    }
}
