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

import org.onebusaway.android.extrapolation.math.GammaDistribution
import kotlin.math.pow

/**
 * Five-parameter power-law blend gamma distribution (H12) for vehicle speed modeling.
 * Maps schedule + observed speeds to a Gamma distribution, then delegates
 * PDF/CDF/quantile math to [GammaDistribution].
 */
object GammaSpeedModel {

    // Fitted parameters
    private const val START_B0 = 0.1793
    private const val END_B0 = 0.0604
    private const val KINK = 26.95 // mph
    private const val C = 1.0793
    private const val D = 0.1699

    const val MPS_TO_MPH = 2.23694

    /** Parameters for a Gamma distribution: shape (alpha) and scale (theta). */
    data class GammaParams(@JvmField val alpha: Double, @JvmField val scale: Double)

    /**
     * Computes Gamma distribution parameters from schedule and previous observed speeds.
     *
     * @param schedSpeedMps scheduled speed in m/s
     * @param prevSpeedMps  previous observed speed in m/s
     * @return GammaParams, or null if inputs are invalid
     */
    @JvmStatic
    fun fromSpeeds(schedSpeedMps: Double, prevSpeedMps: Double): GammaParams? {
        var vPrev = prevSpeedMps
        if (vPrev <= 0) vPrev = schedSpeedMps
        if (schedSpeedMps <= 0) return null

        val vSchedMph = schedSpeedMps * MPS_TO_MPH
        val vPrevMph = vPrev * MPS_TO_MPH

        val vEff = vSchedMph.pow(1.0 - D) * vPrevMph.pow(D)

        val b0 = beta0(vEff)
        val alpha = b0 * C * vEff
        val scale = C / b0

        if (alpha <= 0 || scale <= 0) return null

        return GammaParams(alpha, scale)
    }

    /** Piecewise linear ramp from START_B0 to END_B0, flat after KINK. */
    private fun beta0(vEff: Double): Double = when {
        vEff >= KINK -> END_B0
        vEff <= 0 -> START_B0
        else -> START_B0 + (END_B0 - START_B0) * (vEff / KINK)
    }

    /**
     * Returns the mean speed in m/s from the gamma distribution.
     */
    @JvmStatic
    fun meanSpeedMps(params: GammaParams): Double = params.alpha * params.scale / MPS_TO_MPH

    /**
     * Returns the median (50th percentile) speed in m/s from the gamma distribution.
     */
    @JvmStatic
    fun medianSpeedMps(params: GammaParams): Double = quantileMps(0.50, params)

    /**
     * Gamma PDF evaluated at [speedMph].
     */
    @JvmStatic
    fun pdf(speedMph: Double, params: GammaParams): Double =
        GammaDistribution.pdf(speedMph, params.alpha, params.scale)

    /**
     * Gamma CDF evaluated at [speedMph].
     */
    @JvmStatic
    fun cdf(speedMph: Double, params: GammaParams): Double =
        GammaDistribution.cdf(speedMph, params.alpha, params.scale)

    /**
     * Returns the speed at the given quantile in m/s.
     */
    @JvmStatic
    fun quantileMps(p: Double, params: GammaParams): Double = quantile(p, params) / MPS_TO_MPH

    /**
     * Returns the speed at the given quantile in mph.
     */
    @JvmStatic
    fun quantile(p: Double, params: GammaParams): Double =
        GammaDistribution.quantile(p, params.alpha, params.scale)
}
