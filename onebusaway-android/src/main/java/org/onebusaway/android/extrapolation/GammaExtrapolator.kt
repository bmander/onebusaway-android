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
import org.onebusaway.android.extrapolation.math.prob.FrozenDistribution
import org.onebusaway.android.extrapolation.math.prob.GammaDistribution
import org.onebusaway.android.extrapolation.math.prob.GammaMixtureDistribution
import org.onebusaway.android.extrapolation.math.prob.ProbDistribution
import org.onebusaway.android.io.elements.bestDistanceAlongTrip
import org.onebusaway.android.io.elements.speedAtDistance

// H33 two-gamma mixture parameters, fitted on multi-span King County Metro data (in mph).
private const val START_B0 = 0.736731   // 1/mph
private const val END_B0 = 0.144060     // 1/mph
private const val KINK = 21.959         // mph
private const val R_INTERCEPT = -0.004730
private const val R_SLOPE = 0.041241
private const val CV_INTERCEPT = 0.588421
private const val CV_SLOPE = 0.001527
private const val MW_INTERCEPT = -1.735145
private const val MW_SLOPE = -0.014205

internal const val MPS_TO_MPH = 2.23694

/**
 * Per-trip extrapolator for bus-like routes using the H33 two-gamma mixture
 * speed distribution model. Conditioned on scheduled speed only; the slow
 * component captures delayed/stopped vehicles while the fast component is
 * ensemble-mean-locked to the schedule speed.
 */
class GammaExtrapolator(
        private val tripId: String,
        private val dataManager: TripDataManager
) : Extrapolator {

    companion object {
        const val MAX_HORIZON_MS = 15 * 60 * 1000L
    }

    private var cachedDistribution: Pair<Long, ProbDistribution>? = null

    override fun extrapolate(queryTimeMs: Long): ProbDistribution? {
        val newestValid = dataManager.getNewestValidEntry(tripId) ?: return null
        val lastDist = newestValid.bestDistanceAlongTrip ?: return null
        val lastTime = newestValid.lastLocationUpdateTime
        if (lastTime <= 0) return null
        val dtMs = queryTimeMs - lastTime
        if (dtMs < 0 || dtMs > MAX_HORIZON_MS) return null

        val speedDist = resolveDistribution(lastTime) ?: return null
        val dtSec = dtMs / 1000.0
        return AffineTransformDistribution(speedDist, lastDist, dtSec / MPS_TO_MPH)
    }

    private fun resolveDistribution(lastFixTime: Long): ProbDistribution? {
        cachedDistribution?.let { (cachedTime, dist) ->
            if (cachedTime == lastFixTime) return dist
        }

        val lastState = dataManager.getLastState(tripId) ?: return null
        val schedule = dataManager.getSchedule(tripId)
        val scheduleSpeed = schedule?.speedAtDistance(
                lastState.scheduledDistanceAlongTrip ?: return null) ?: return null

        return buildH33SpeedDistribution(scheduleSpeed).also {
            cachedDistribution = lastFixTime to it
        }
    }
}

// --- H33 two-gamma mixture speed model ---

/**
 * Builds a frozen two-gamma mixture speed distribution from H33 parameters.
 * The returned distribution is over speed in mph.
 *
 * @param schedSpeedMps scheduled speed in m/s (must be positive)
 */
internal fun buildH33SpeedDistribution(schedSpeedMps: Double): ProbDistribution {
    require(schedSpeedMps > 0) { "schedSpeedMps must be positive" }

    val v = schedSpeedMps * MPS_TO_MPH

    // Mixture weight
    val m = sigmoid(MW_INTERCEPT + MW_SLOPE * v)

    // Slow component (ratio parameterization)
    val r = sigmoid(R_INTERCEPT + R_SLOPE * v)
    val meanSlow = r * v
    val cv = exp(CV_INTERCEPT + CV_SLOPE * v)
    val cv2 = cv * cv
    val alpha1 = 1.0 / cv2
    val scale1 = meanSlow * cv2

    // Fast component (ensemble-mean locked to v_sched)
    val b0 = beta0(v)
    val alpha2 = b0 * v
    val c = (1.0 - m * r) / (1.0 - m)
    val scale2 = c / b0

    val slow = GammaDistribution(alpha1, scale1)
    val fast = GammaDistribution(alpha2, scale2)
    return FrozenDistribution(GammaMixtureDistribution(m, slow, fast))
}

private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

/** Piecewise linear ramp from START_B0 to END_B0, flat after KINK. */
private fun beta0(v: Double): Double =
        when {
            v >= KINK -> END_B0
            v <= 0 -> START_B0
            else -> START_B0 + (END_B0 - START_B0) * (v / KINK)
        }
