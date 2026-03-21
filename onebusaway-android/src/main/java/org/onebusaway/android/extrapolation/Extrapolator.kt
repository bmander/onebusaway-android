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
import org.onebusaway.android.extrapolation.math.ProbDistribution
import org.onebusaway.android.io.elements.ObaTripStatus

/**
 * Extrapolates a vehicle's position along a trip, returning a distribution over
 * distance along the trip. Implementations encapsulate different motion models
 * (gamma speed distribution for buses, schedule replay for grade-separated transit).
 */
interface Extrapolator {

    /**
     * Computes a distribution over extrapolated distance along the trip at [queryTimeMs].
     *
     * @param newestValid the most recent status with a valid distanceAlongTrip
     * @param snapshot the trip's cached data (schedule, shape, route type, etc.)
     * @param queryTimeMs the wall-clock time to extrapolate to
     * @return a [ProbDistribution] over distance (meters), or null if extrapolation is not possible
     */
    fun extrapolate(
            newestValid: ObaTripStatus,
            snapshot: TripDataManager.TripSnapshot,
            queryTimeMs: Long
    ): ProbDistribution?

    /** Clears any cached state. Default no-op. */
    fun clearCache() {}
}
