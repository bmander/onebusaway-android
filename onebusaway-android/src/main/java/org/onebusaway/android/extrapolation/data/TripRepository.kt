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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.request.ObaShapeRequest
import org.onebusaway.android.io.request.ObaTripDetailsRequest

/**
 * Coordinates fetching and caching of trip data. Checks [TripDataManager] first; on a cache miss,
 * fetches from the OBA API in the background and stores the result. Safe to call repeatedly —
 * duplicate in-flight requests are suppressed.
 */
object TripRepository {

    private const val TAG = "TripRepository"

    @JvmStatic fun getInstance() = this

    private val executor = Executors.newFixedThreadPool(2)
    private val pendingScheduleFetches: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingShapeFetches: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val cache = TripDataManager

    /**
     * Ensures a schedule is cached for [tripId], fetching in the background if needed.
     */
    fun ensureSchedule(tripId: String) {
        if (cache.isScheduleCached(tripId) || !pendingScheduleFetches.add(tripId)) return
        executor.execute {
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
                    cache.putSchedule(tripId, schedule)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch schedule for $tripId", e)
            } finally {
                pendingScheduleFetches.remove(tripId)
            }
        }
    }

    /**
     * Ensures a shape is cached for [tripId], fetching [shapeId] in the background if needed.
     */
    fun ensureShape(tripId: String, shapeId: String) {
        if (cache.getShape(tripId) != null || !pendingShapeFetches.add(tripId)) return
        executor.execute {
            try {
                val ctx = Application.get().applicationContext
                val response = ObaShapeRequest.newRequest(ctx, shapeId).call()
                val points = response?.points
                if (points != null && points.isNotEmpty()) {
                    cache.putShape(tripId, points)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch shape for $tripId", e)
            } finally {
                pendingShapeFetches.remove(tripId)
            }
        }
    }

    /** Clears pending-fetch state. */
    fun clearAll() {
        pendingScheduleFetches.clear()
        pendingShapeFetches.clear()
    }
}
