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
package org.onebusaway.android.extrapolation.math.speed

import org.onebusaway.android.extrapolation.Extrapolator
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.AffineTransformDistribution
import org.onebusaway.android.extrapolation.math.ProbDistribution
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.bestDistanceAlongTrip

/**
 * Extrapolator for bus-like routes using the gamma speed distribution model.
 * Returns a distribution over distance along the trip by transforming the
 * speed distribution via distance = lastDist + speed * dt.
 */
class GammaExtrapolator(dataManager: TripDataManager) : Extrapolator {

    private val speedEstimator = GammaSpeedEstimator(dataManager)

    override fun extrapolate(
            newestValid: ObaTripStatus,
            snapshot: TripDataManager.TripSnapshot,
            queryTimeMs: Long
    ): ProbDistribution? {
        val lastDist = newestValid.bestDistanceAlongTrip ?: return null
        val lastTime = newestValid.lastLocationUpdateTime
        val dtMs = queryTimeMs - lastTime
        if (dtMs < 0 || dtMs > VehicleTrajectoryTracker.MAX_EXTRAPOLATION_AGE_MS) return null

        val tripId = newestValid.activeTripId ?: return null
        val result = speedEstimator.estimateSpeed(tripId, queryTimeMs, snapshot)
        val speedDistribution = (result as? SpeedEstimateResult.Success)?.distribution
                ?: return null

        val dtSec = dtMs / 1000.0
        return AffineTransformDistribution(speedDistribution, lastDist, dtSec)
    }

    override fun clearCache() = speedEstimator.clearCache()
}
