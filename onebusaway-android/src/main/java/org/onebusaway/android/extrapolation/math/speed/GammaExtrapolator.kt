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
import org.onebusaway.android.extrapolation.math.prob.AffineTransformDistribution
import org.onebusaway.android.extrapolation.math.prob.ProbDistribution
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.bestDistanceAlongTrip
import org.onebusaway.android.io.elements.speedAtDistance

/**
 * Per-trip extrapolator for bus-like routes using the gamma speed distribution model.
 * Combines schedule speed with the most recent AVL-derived speed to produce a
 * gamma distribution over vehicle speed, then transforms it to a distribution
 * over distance via distance = lastDist + speed * dt.
 */
class GammaExtrapolator(
        private val tripId: String,
        private val dataManager: TripDataManager
) : Extrapolator {

    companion object {
        const val MAX_HORIZON_MS = 15 * 60 * 1000L
    }

    private data class CachedFactory(val lastFixTime: Long, val factory: SpeedDistributionFactory)
    private var cachedFactory: CachedFactory? = null

    override fun extrapolate(queryTimeMs: Long): ProbDistribution? {
        val newestValid = dataManager.getNewestValidEntry(tripId) ?: return null
        val lastDist = newestValid.bestDistanceAlongTrip ?: return null
        val lastTime = newestValid.lastLocationUpdateTime
        if (lastTime <= 0) return null
        val dtMs = queryTimeMs - lastTime
        if (dtMs < 0 || dtMs > MAX_HORIZON_MS) return null

        val factory = resolveFactory(lastTime) ?: return null
        val speedDistribution = factory.at(dtMs / 1000.0)

        return AffineTransformDistribution(speedDistribution, lastDist, dtMs / 1000.0)
    }

    private fun resolveFactory(lastFixTime: Long): SpeedDistributionFactory? {
        cachedFactory?.let { if (it.lastFixTime == lastFixTime) return it.factory }

        val prevSpeed = computeAvlSpeed(dataManager.mostRecentAvlFixes(tripId).take(2).toList())
        val lastState = dataManager.getLastState(tripId) ?: return null
        val schedule = dataManager.getSchedule(tripId)
        val scheduleSpeed = schedule?.speedAtDistance(
                lastState.scheduledDistanceAlongTrip ?: return null) ?: return null

        return makeGammaProbDistribution(scheduleSpeed, prevSpeed).also {
            cachedFactory = CachedFactory(lastFixTime, it)
        }
    }

    private fun computeAvlSpeed(fixes: List<ObaTripStatus>): Double? {
        if (fixes.size < 2) return null
        val (newer, older) = fixes
        val newerDist = newer.bestDistanceAlongTrip ?: return null
        val olderDist = older.bestDistanceAlongTrip ?: return null
        val dtMs = newer.lastLocationUpdateTime - older.lastLocationUpdateTime
        val dd = newerDist - olderDist
        return if (dtMs > 0) maxOf(0.0, dd / (dtMs / 1000.0)) else null
    }
}
