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
 * All public inputs, outputs, and fitted parameters are expressed in m/s.
 */
object GammaSpeedModel {

    // Fitted parameters expressed in m/s.
    // START_B0 and END_B0 were converted from 1/mph to 1/(m/s).
    private const val START_B0 = 0.401083342
    private const val END_B0 = 0.135111176
    private const val KINK = 12.04770802971917 // m/s
    private const val C = 1.0793
    private const val D = 0.1699

    /**
     * Computes a gamma speed distribution from schedule and previous observed speeds.
     *
     * @param schedSpeedMps scheduled speed in m/s
     * @param prevSpeedMps  previous observed speed in m/s
     * @return GammaDistribution (in m/s), or null if inputs are invalid
     */
    @JvmStatic
    fun fromSpeeds(schedSpeedMps: Double, prevSpeedMps: Double): GammaDistribution? {
        var vPrev = prevSpeedMps
        if (vPrev <= 0) vPrev = schedSpeedMps
        if (schedSpeedMps <= 0) return null

        val vEff = schedSpeedMps.pow(1.0 - D) * vPrev.pow(D)

        val b0 = beta0(vEff)
        val alpha = b0 * C * vEff
        val scale = C / b0

        if (alpha <= 0 || scale <= 0) return null

        return GammaDistribution(alpha, scale)
    }

    /** Piecewise linear ramp from START_B0 to END_B0, flat after KINK. */
    private fun beta0(vEff: Double): Double = when {
        vEff >= KINK -> END_B0
        vEff <= 0 -> START_B0
        else -> START_B0 + (END_B0 - START_B0) * (vEff / KINK)
    }

}
