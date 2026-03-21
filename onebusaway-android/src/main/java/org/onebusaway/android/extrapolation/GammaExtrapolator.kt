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

import kotlin.math.exp
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.prob.AffineTransformDistribution
import org.onebusaway.android.extrapolation.math.prob.DiracDistribution
import org.onebusaway.android.extrapolation.math.prob.FrozenDistribution
import org.onebusaway.android.extrapolation.math.prob.GammaDistribution
import org.onebusaway.android.extrapolation.math.prob.ProbDistribution
import org.onebusaway.android.extrapolation.math.prob.ZeroInflatedDistribution
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.bestDistanceAlongTrip
import org.onebusaway.android.io.elements.speedAtDistance

// Fitted parameters expressed in m/s; fit on a single day of King County Metro
// data from early March 2026. TODO: get more data.
private const val START_B0 = 0.9455 // s/m
private const val END_B0 = 0.3102 // s/m
private const val KINK = 6.087 // m/s
private const val BLEND_WEIGHT = 0.9127 // unitless
private const val ZERO_INFLATION_A = 0.1732 // unitless
private const val ZERO_INFLATION_LAMBDA = 0.00462 // 1/s

/**
 * Per-trip extrapolator for bus-like routes using the gamma speed distribution model.
 * Combines schedule speed with the most recent AVL-derived speed to produce a
 * zero-inflated gamma distribution over vehicle speed, then transforms it to a
 * distribution over distance via distance = lastDist + speed * dt.
 */
class GammaExtrapolator(
        private val tripId: String,
        private val dataManager: TripDataManager
) : Extrapolator {

    companion object {
        const val MAX_HORIZON_MS = 15 * 60 * 1000L
    }

    /**
     * Cached speed distribution factory. Invalidated when lastFixTime changes
     * (i.e., new AVL data arrives). The factory pre-computes the gamma quantile
     * table once; evaluating at a new dt is O(1).
     */
    private var cachedFactory: Pair<Long, (Double) -> ProbDistribution>? = null

    override fun extrapolate(queryTimeMs: Long): ProbDistribution? {
        val newestValid = dataManager.getNewestValidEntry(tripId) ?: return null
        val lastDist = newestValid.bestDistanceAlongTrip ?: return null
        val lastTime = newestValid.lastLocationUpdateTime
        if (lastTime <= 0) return null
        val dtMs = queryTimeMs - lastTime
        if (dtMs < 0 || dtMs > MAX_HORIZON_MS) return null

        val factory = resolveFactory(lastTime) ?: return null
        val speedDistribution = factory(dtMs / 1000.0)

        return AffineTransformDistribution(speedDistribution, lastDist, dtMs / 1000.0)
    }

    private fun resolveFactory(lastFixTime: Long): ((Double) -> ProbDistribution)? {
        cachedFactory?.let { (cachedTime, factory) ->
            if (cachedTime == lastFixTime) return factory
        }

        val prevSpeed = computeAvlSpeed(dataManager.mostRecentAvlFixes(tripId).take(2).toList())
        val lastState = dataManager.getLastState(tripId) ?: return null
        val schedule = dataManager.getSchedule(tripId)
        val scheduleSpeed = schedule?.speedAtDistance(
                lastState.scheduledDistanceAlongTrip ?: return null) ?: return null

        return buildSpeedDistributionFactory(scheduleSpeed, prevSpeed).also {
            cachedFactory = lastFixTime to it
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

// --- Gamma speed model ---

/**
 * Creates a speed distribution factory from schedule and previous observed speeds.
 * The returned function pre-computes the gamma quantile table once. Each call just
 * computes p0 from dt and wraps it — no gamma CDF math.
 */
internal fun buildSpeedDistributionFactory(
        schedSpeedMps: Double,
        prevSpeedMps: Double?
): (Double) -> ProbDistribution {
    var vPrev = prevSpeedMps ?: 0.0
    if (vPrev <= 0) vPrev = schedSpeedMps
    require(schedSpeedMps > 0) { "schedSpeedMps must be positive" }

    val vEff = schedSpeedMps * BLEND_WEIGHT + (1 - BLEND_WEIGHT) * vPrev

    // Defensive guard: should not be reachable given the require above
    if (vEff <= 0) return { DiracDistribution(0.0) }

    val b0 = beta0(vEff)
    require(b0 > 0) { "Computed b0 must be positive" }

    val alpha = b0 * vEff
    val scale = 1.0 / b0
    val frozen = FrozenDistribution(GammaDistribution(alpha, scale))

    return { dtSec ->
        val p0 = ZERO_INFLATION_A * exp(-ZERO_INFLATION_LAMBDA * dtSec)
        ZeroInflatedDistribution(p0, frozen)
    }
}

/** Piecewise linear ramp from START_B0 to END_B0, flat after KINK. */
private fun beta0(vEff: Double): Double =
        when {
            vEff >= KINK -> END_B0
            vEff <= 0 -> START_B0
            else -> START_B0 + (END_B0 - START_B0) * (vEff / KINK)
        }
