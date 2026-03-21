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

import kotlin.math.exp
import org.onebusaway.android.extrapolation.math.DiracDistribution
import org.onebusaway.android.extrapolation.math.FrozenDistribution
import org.onebusaway.android.extrapolation.math.GammaDistribution
import org.onebusaway.android.extrapolation.math.ProbDistribution
import org.onebusaway.android.extrapolation.math.ZeroInflatedDistribution

// Fitted parameters expressed in m/s; fit on a single day of King County Metro
// data from early March 2026. TODO: get more data.
// START_B0 and END_B0 were converted from 1/mph to 1/(m/s).
private const val START_B0 = 0.9455 // s/m
private const val END_B0 = 0.3102 // s/m
private const val KINK = 6.087 // m/s
private const val D = 0.9127 // unitless
private const val A = 0.1732 // unitless
private const val LAMBDA = 0.00462 // 1/s

/**
 * A function that produces a [ProbDistribution] for a given dt (seconds since last observation).
 * The underlying gamma quantile table is pre-computed at creation time; evaluating at a new dt
 * is O(1).
 */
fun interface SpeedDistributionFactory {
    fun at(dtSec: Double): ProbDistribution
}

/**
 * Creates a [SpeedDistributionFactory] from schedule and previous observed speeds.
 *
 * The returned factory pre-computes the gamma quantile table once. Each call to [at] just
 * computes p0 from dt and wraps it — no gamma CDF math.
 *
 * @param schedSpeedMps scheduled speed in m/s
 * @param prevSpeedMps previous observed speed in m/s (null or non-positive falls back to schedule)
 * @return a factory that produces distributions for any dt
 * @throws IllegalArgumentException if schedSpeedMps is non-positive
 */
fun makeGammaProbDistribution(
        schedSpeedMps: Double,
        prevSpeedMps: Double?
): SpeedDistributionFactory {
    var vPrev = prevSpeedMps ?: 0.0
    if (vPrev <= 0) vPrev = schedSpeedMps
    require(schedSpeedMps > 0) { "schedSpeedMps must be positive" }

    // Effective speed is a blend of schedule and previous speed
    val vEff = schedSpeedMps * D + (1 - D) * vPrev

    // Defensive guard: should not be reachable given the require above
    if (vEff <= 0) return SpeedDistributionFactory { DiracDistribution(0.0) }

    // Shape parameter is an empirical function of effective speed
    val b0 = beta0(vEff)
    require(b0 > 0) { "Computed b0 must be positive" }

    val alpha = b0 * vEff
    val scale = 1.0 / b0

    // Build the quantile table once — this is the expensive part
    val frozen = FrozenDistribution(GammaDistribution(alpha, scale))

    return SpeedDistributionFactory { dtSec ->
        val p0 = A * exp(-LAMBDA * dtSec)
        ZeroInflatedDistribution(p0, frozen)
    }
}

/**
 * Convenience that creates a distribution directly for a given dt.
 * Builds a fresh factory each call — use [makeGammaProbDistribution] for per-frame rendering.
 */
fun gammaProbDistribution(
        schedSpeedMps: Double,
        prevSpeedMps: Double?,
        dt: Double
): ProbDistribution = makeGammaProbDistribution(schedSpeedMps, prevSpeedMps).at(dt)

/** Piecewise linear ramp from START_B0 to END_B0, flat after KINK. */
private fun beta0(vEff: Double): Double =
        when {
            vEff >= KINK -> END_B0
            vEff <= 0 -> START_B0
            else -> START_B0 + (END_B0 - START_B0) * (vEff / KINK)
        }
