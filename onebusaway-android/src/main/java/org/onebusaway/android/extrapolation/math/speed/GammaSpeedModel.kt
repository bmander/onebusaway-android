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

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Five-parameter power-law blend gamma distribution (H12) for vehicle speed modeling.
 * Pure-math utility, no Android dependencies.
 */
object GammaSpeedModel {

    // Fitted parameters
    private const val START_B0 = 0.1793
    private const val END_B0 = 0.0604
    private const val KINK = 26.95 // mph
    private const val C = 1.0793
    private const val D = 0.1699

    const val MPS_TO_MPH = 2.23694

    private const val CDF_MAX_ITERATIONS = 200
    private const val CDF_EPSILON = 1e-10

    /** Parameters for a Gamma distribution: shape (alpha) and scale (theta). */
    data class GammaParams(val alpha: Double, val scale: Double)

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
     * Gamma PDF: f(x; alpha, scale) = x^(alpha-1) * exp(-x/scale) / (scale^alpha * Gamma(alpha))
     *
     * @param speedMph speed in mph
     * @param params   gamma parameters
     * @return PDF value
     */
    @JvmStatic
    fun pdf(speedMph: Double, params: GammaParams): Double {
        if (speedMph <= 0) return 0.0
        val a = params.alpha
        val s = params.scale
        val lnPdf = (a - 1) * ln(speedMph) - speedMph / s - a * ln(s) - lnGamma(a)
        return exp(lnPdf)
    }

    /**
     * Regularized lower incomplete gamma function P(a, x) = CDF of Gamma(a, 1) at x/scale.
     *
     * @param speedMph speed in mph
     * @param params   gamma parameters
     * @return CDF value in [0, 1]
     */
    @JvmStatic
    fun cdf(speedMph: Double, params: GammaParams): Double {
        if (speedMph <= 0) return 0.0
        val x = speedMph / params.scale
        return regularizedGammaP(params.alpha, x)
    }

    /**
     * Returns the speed at the given quantile in m/s.
     *
     * @param p      probability in (0, 1)
     * @param params gamma parameters
     * @return speed in m/s at the given quantile
     */
    @JvmStatic
    fun quantileMps(p: Double, params: GammaParams): Double = quantile(p, params) / MPS_TO_MPH

    /**
     * Inverse CDF via bisection.
     *
     * @param p      probability in (0, 1)
     * @param params gamma parameters
     * @return speed in mph at the given quantile
     */
    @JvmStatic
    fun quantile(p: Double, params: GammaParams): Double {
        if (p <= 0) return 0.0
        if (p >= 1) return Double.MAX_VALUE

        val mean = params.alpha * params.scale
        var hi = mean + 10 * sqrt(params.alpha) * params.scale
        var lo = 0.0

        while (cdf(hi, params) < p) {
            hi *= 2
        }

        repeat(40) {
            val mid = (lo + hi) / 2
            if (cdf(mid, params) < p) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /**
     * Regularized lower incomplete gamma function P(a, x).
     * Series for x < a+1, continued fraction otherwise.
     */
    private fun regularizedGammaP(a: Double, x: Double): Double {
        if (x <= 0) return 0.0

        return if (x < a + 1) {
            // Series expansion
            var sum = 1.0 / a
            var term = 1.0 / a
            for (n in 1..CDF_MAX_ITERATIONS) {
                term *= x / (a + n)
                sum += term
                if (abs(term) < CDF_EPSILON * abs(sum)) break
            }
            sum * exp(-x + a * ln(x) - lnGamma(a))
        } else {
            // Continued fraction (Legendre)
            var c = 1.0
            var d = 1.0 / (x - a + 1)
            var f = d

            for (n in 1..CDF_MAX_ITERATIONS) {
                val an = -n * (n - a)
                val bn = x - a + 1 + 2 * n

                d = bn + an * d
                if (abs(d) < 1e-30) d = 1e-30
                d = 1.0 / d

                c = bn + an / c
                if (abs(c) < 1e-30) c = 1e-30

                val delta = c * d
                f *= delta

                if (abs(delta - 1.0) < CDF_EPSILON) break
            }

            // P(a,x) = 1 - Q(a,x), where Q uses the continued fraction
            1.0 - exp(-x + a * ln(x) - lnGamma(a)) * f
        }
    }

    private val LN_GAMMA_COEF = doubleArrayOf(
        76.18009172947146,
        -86.50532032941677,
        24.01409824083091,
        -1.231739572450155,
        0.1208650973866179e-2,
        -0.5395239384953e-5
    )

    /** Lanczos approximation for ln(Gamma(x)), valid for x > 0. */
    private fun lnGamma(x: Double): Double {
        var y = x
        var tmp = x + 5.5
        tmp -= (x + 0.5) * ln(tmp)
        var ser = 1.000000000190015
        for (c in LN_GAMMA_COEF) {
            y += 1.0
            ser += c / y
        }
        return -tmp + ln(2.5066282746310005 * ser / x)
    }
}
