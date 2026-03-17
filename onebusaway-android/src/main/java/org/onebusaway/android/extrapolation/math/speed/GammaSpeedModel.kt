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
 * All public methods use m/s. The model was calibrated in mph internally;
 * the conversion is encapsulated in [fromSpeeds].
 */
object GammaSpeedModel {

    // Fitted parameters (calibrated in mph space)
    private const val START_B0 = 0.1793
    private const val END_B0 = 0.0604
    private const val KINK = 26.95 // mph
    private const val C = 1.0793
    private const val D = 0.1699
    private const val MPS_TO_MPH = 2.23694

    /**
     * Parameters for a Gamma distribution: shape ([alpha]) and [scale] in m/s.
     */
    data class GammaParams(@JvmField val alpha: Double, @JvmField val scale: Double)

    /**
     * Computes Gamma distribution parameters from schedule and previous observed speeds.
     *
     * @param schedSpeedMps scheduled speed in m/s
     * @param prevSpeedMps  previous observed speed in m/s
     * @return GammaParams (scale in m/s), or null if inputs are invalid
     */
    @JvmStatic
    fun fromSpeeds(schedSpeedMps: Double, prevSpeedMps: Double): GammaParams? {
        var vPrev = prevSpeedMps
        if (vPrev <= 0) vPrev = schedSpeedMps
        if (schedSpeedMps <= 0) return null

        // Model was calibrated in mph; convert for parameter computation
        val vSchedMph = schedSpeedMps * MPS_TO_MPH
        val vPrevMph = vPrev * MPS_TO_MPH

        val vEff = vSchedMph.pow(1.0 - D) * vPrevMph.pow(D)

        val b0 = beta0(vEff)
        val alpha = b0 * C * vEff
        val scaleMps = C / b0 / MPS_TO_MPH

        if (alpha <= 0 || scaleMps <= 0) return null

        return GammaParams(alpha, scaleMps)
    }

    /** Piecewise linear ramp from START_B0 to END_B0, flat after KINK (in mph). */
    private fun beta0(vEffMph: Double): Double = when {
        vEffMph >= KINK -> END_B0
        vEffMph <= 0 -> START_B0
        else -> START_B0 + (END_B0 - START_B0) * (vEffMph / KINK)
    }

    /**
     * Returns the mean speed in m/s.
     */
    @JvmStatic
    fun mean(params: GammaParams): Double = params.alpha * params.scale

    /**
     * Returns the median (50th percentile) speed in m/s.
     */
    @JvmStatic
    fun median(params: GammaParams): Double = quantile(0.50, params)

    /**
     * Gamma PDF evaluated at [speedMps].
     */
    @JvmStatic
    fun pdf(speedMps: Double, params: GammaParams): Double =
        GammaDistribution.pdf(speedMps, params.alpha, params.scale)

    /**
     * Gamma CDF evaluated at [speedMps].
     */
    @JvmStatic
    fun cdf(speedMps: Double, params: GammaParams): Double =
        GammaDistribution.cdf(speedMps, params.alpha, params.scale)

    /**
     * Returns the speed in m/s at the given quantile.
     */
    @JvmStatic
    fun quantile(p: Double, params: GammaParams): Double =
        GammaDistribution.quantile(p, params.alpha, params.scale)
}
