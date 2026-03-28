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
package org.onebusaway.android.extrapolation

import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.prob.ProbDistribution
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.bestDistanceAlongTrip

private const val MAX_HORIZON_MS = 15 * 60 * 1000L
private const val PRE_DEPARTURE_DISTANCE_THRESHOLD = 50.0 // meters
private const val TRIP_END_DISTANCE_THRESHOLD = 50.0 // meters from end

/** Result of an extrapolation attempt. */
sealed class ExtrapolationResult {
    /** Extrapolation succeeded. */
    class Success(val distribution: ProbDistribution) : ExtrapolationResult()
    /** No valid vehicle data exists for the trip. */
    object NoData : ExtrapolationResult()
    /** Vehicle data is older than the extrapolation horizon. */
    object Stale : ExtrapolationResult()
    /** Vehicle is at the trip start before scheduled departure. */
    object TripNotStarted : ExtrapolationResult()
    /** Vehicle is at or near the end of the trip. */
    object TripEnded : ExtrapolationResult()
    /** Required schedule data is missing. */
    object MissingSchedule : ExtrapolationResult()
}

/**
 * Extrapolates a vehicle's position along a trip, returning a distribution over
 * distance along the trip. Each instance is bound to a specific trip.
 *
 * Subclasses implement [doExtrapolate] with their model-specific logic.
 * The base class handles common validation: fetching the newest valid entry,
 * checking time bounds, and suppressing extrapolation before trip departure.
 */
abstract class Extrapolator(
        protected val tripId: String,
        protected val dataManager: TripDataManager
) {

    fun extrapolate(queryTimeMs: Long): ExtrapolationResult {
        val newestValid = dataManager.getNewestValidEntry(tripId)
                ?: return ExtrapolationResult.NoData
        val lastDist = newestValid.bestDistanceAlongTrip
                ?: return ExtrapolationResult.NoData
        val lastTime = newestValid.lastLocationUpdateTime
        if (lastTime <= 0) return ExtrapolationResult.NoData
        val dtMs = queryTimeMs - lastTime
        if (dtMs < 0 || dtMs > MAX_HORIZON_MS) return ExtrapolationResult.Stale
        if (isAtTripStart(lastDist)) return ExtrapolationResult.TripNotStarted
        val totalDist = newestValid.totalDistanceAlongTrip
        if (totalDist != null && isAtTripEnd(lastDist, totalDist)) return ExtrapolationResult.TripEnded

        return doExtrapolate(lastDist, dtMs / 1000.0, lastTime)
    }

    protected abstract fun doExtrapolate(lastDist: Double, dtSec: Double, lastFixTimeMs: Long): ExtrapolationResult

    private fun isAtTripEnd(distanceAlongTrip: Double, totalDistance: Double) =
            totalDistance > 0 && totalDistance - distanceAlongTrip < TRIP_END_DISTANCE_THRESHOLD

    private fun isAtTripStart(distanceAlongTrip: Double) =
            distanceAlongTrip <= PRE_DEPARTURE_DISTANCE_THRESHOLD
}

/** Creates the appropriate [Extrapolator] for a trip based on its route type. */
fun createExtrapolator(
        tripId: String,
        dataManager: TripDataManager = TripDataManager
): Extrapolator {
    val routeType = dataManager.getRouteType(tripId)
    return if (routeType != null && ObaRoute.isGradeSeparated(routeType))
        ScheduleReplayExtrapolator(tripId, dataManager)
    else
        GammaExtrapolator(tripId, dataManager)
}
