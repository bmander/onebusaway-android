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
package org.onebusaway.android.extrapolation.math

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Pure-math gamma distribution utilities: PDF, CDF, quantile (inverse CDF),
 * and the Lanczos approximation for ln(Gamma). No Android dependencies.
 */
object GammaDistribution {

    private const val MAX_ITERATIONS = 200
    private const val EPSILON = 1e-10

    /**
     * Gamma PDF: f(x; alpha, scale) = x^(alpha-1) * exp(-x/scale) / (scale^alpha * Gamma(alpha))
     *
     * @param x     value (must be > 0 for nonzero result)
     * @param alpha shape parameter
     * @param scale scale parameter
     * @return PDF value
     */
    @JvmStatic
    fun pdf(x: Double, alpha: Double, scale: Double): Double {
        if (x <= 0) return 0.0
        val lnPdf = (alpha - 1) * ln(x) - x / scale - alpha * ln(scale) - lnGamma(alpha)
        return exp(lnPdf)
    }

    /**
     * Gamma CDF via the regularized lower incomplete gamma function P(a, x).
     *
     * @param x     value
     * @param alpha shape parameter
     * @param scale scale parameter
     * @return CDF value in [0, 1]
     */
    @JvmStatic
    fun cdf(x: Double, alpha: Double, scale: Double): Double {
        if (x <= 0) return 0.0
        return regularizedGammaP(alpha, x / scale)
    }

    /**
     * Inverse CDF (quantile function) via bisection.
     *
     * @param p     probability in (0, 1)
     * @param alpha shape parameter
     * @param scale scale parameter
     * @return value at the given quantile
     */
    @JvmStatic
    fun quantile(p: Double, alpha: Double, scale: Double): Double {
        if (p <= 0) return 0.0
        if (p >= 1) return Double.MAX_VALUE

        val mean = alpha * scale
        var hi = mean + 10 * sqrt(alpha) * scale
        var lo = 0.0

        // Expand upper bracket if needed for heavy-tailed distributions
        while (cdf(hi, alpha, scale) < p) {
            hi *= 2
        }

        repeat(40) {
            val mid = (lo + hi) / 2
            if (cdf(mid, alpha, scale) < p) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /**
     * Regularized lower incomplete gamma function P(a, x).
     * Series expansion for x < a+1, continued fraction otherwise.
     */
    private fun regularizedGammaP(a: Double, x: Double): Double {
        if (x <= 0) return 0.0

        return if (x < a + 1) {
            // Series expansion
            var sum = 1.0 / a
            var term = 1.0 / a
            for (n in 1..MAX_ITERATIONS) {
                term *= x / (a + n)
                sum += term
                if (abs(term) < EPSILON * abs(sum)) break
            }
            sum * exp(-x + a * ln(x) - lnGamma(a))
        } else {
            // Continued fraction (Legendre)
            var c = 1.0
            var d = 1.0 / (x - a + 1)
            var f = d

            for (n in 1..MAX_ITERATIONS) {
                val an = -n * (n - a)
                val bn = x - a + 1 + 2 * n

                d = bn + an * d
                if (abs(d) < 1e-30) d = 1e-30
                d = 1.0 / d

                c = bn + an / c
                if (abs(c) < 1e-30) c = 1e-30

                val delta = c * d
                f *= delta

                if (abs(delta - 1.0) < EPSILON) break
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
    @JvmStatic
    fun lnGamma(x: Double): Double {
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
