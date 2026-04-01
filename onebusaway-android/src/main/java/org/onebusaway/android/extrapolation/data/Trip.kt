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

import org.onebusaway.android.extrapolation.ExtrapolationResult
import org.onebusaway.android.extrapolation.Extrapolator
import org.onebusaway.android.extrapolation.GammaExtrapolator
import org.onebusaway.android.extrapolation.ScheduleReplayExtrapolator

import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.request.ObaTripDetailsResponse
import org.onebusaway.android.util.Polyline

private const val MAX_ENTRIES = 100
private const val MAX_HORIZON_MS = 15 * 60 * 1000L
private const val PRE_DEPARTURE_DISTANCE_THRESHOLD = 50.0
private const val TRIP_END_DISTANCE_THRESHOLD = 50.0

/**
 * All data for a single tracked trip: vehicle history, extrapolation anchor, schedule,
 * polyline, and route metadata. Provides [extrapolate] which selects the appropriate
 * strategy (gamma, schedule replay, or schedule-only fallback) based on the data available.
 *
 * Thread safety: all mutable state is accessed under TripDataManager's @Synchronized lock.
 */
class Trip(val tripId: String) {

    // --- Vehicle history ---
    val history = mutableListOf<ObaTripStatus>()
    val fetchTimes = mutableListOf<Long>()
    /** The most recent status with valid distance data, or null. */
    val anchor: ObaTripStatus? get() = history.lastOrNull()

    // --- Caches ---
    var schedule: ObaTripSchedule? = null
    var serviceDate: Long = 0
    var polyline: Polyline? = null
    var routeType: Int? = null
    var lastActiveTripId: String? = null
    var tripDetailsResponse: ObaTripDetailsResponse? = null

    // --- Extrapolation ---

    private var extrapolator: Extrapolator? = null

    // --- Recording ---

    /** Records a status snapshot. Skips entries without valid distance data. */
    fun recordStatus(status: ObaTripStatus) {
        if (status.distanceAlongTrip == null) return

        history.add(status)
        fetchTimes.add(System.currentTimeMillis())

        if (history.size > MAX_ENTRIES) {
            history.subList(0, history.size - MAX_ENTRIES).clear()
            fetchTimes.subList(0, fetchTimes.size - MAX_ENTRIES).clear()
        }
    }

    // --- Extrapolation ---

    fun extrapolate(queryTimeMs: Long): ExtrapolationResult {
        val currentAnchor = anchor ?: return ExtrapolationResult.NoData
        val lastDist = currentAnchor.distanceAlongTrip
                ?: return ExtrapolationResult.NoData

        val serverTime = try { currentAnchor.lastUpdateTime } catch (_: NullPointerException) { 0L }
        val lastTime = if (serverTime > 0) serverTime else fetchTimes.lastOrNull() ?: 0L
        if (lastTime <= 0) return ExtrapolationResult.NoData

        val dtMs = queryTimeMs - lastTime
        if (dtMs < 0 || dtMs > MAX_HORIZON_MS) return ExtrapolationResult.Stale
        if (lastDist <= PRE_DEPARTURE_DISTANCE_THRESHOLD) return ExtrapolationResult.TripNotStarted
        val totalDist = currentAnchor.totalDistanceAlongTrip
        if (totalDist != null && totalDist > 0
                && totalDist - lastDist < TRIP_END_DISTANCE_THRESHOLD) {
            return ExtrapolationResult.TripEnded
        }

        return getOrCreateExtrapolator().doExtrapolate(lastDist, lastTime, queryTimeMs)
    }

    private fun getOrCreateExtrapolator(): Extrapolator {
        extrapolator?.let { return it }
        val ext = if (routeType != null && ObaRoute.isGradeSeparated(routeType!!))
            ScheduleReplayExtrapolator(this)
        else
            GammaExtrapolator(this)
        extrapolator = ext
        return ext
    }
}
