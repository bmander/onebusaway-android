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

/**
 * Extrapolates a vehicle's position along a trip, returning a distribution over
 * distance along the trip. Each instance is bound to a specific trip.
 */
interface Extrapolator {

    companion object {
        /** Max age of the newest AVL entry before extrapolation is considered unreliable. */
        const val MAX_EXTRAPOLATION_AGE_MS = 5L * 60 * 1000
    }

    /**
     * Computes a distribution over extrapolated distance along the trip at [queryTimeMs].
     *
     * @param queryTimeMs the wall-clock time to extrapolate to
     * @return a [ProbDistribution] over distance (meters), or null if extrapolation is not possible
     */
    fun extrapolate(queryTimeMs: Long): ProbDistribution?
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
