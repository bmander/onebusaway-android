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

/**
 * Zero-inflated Gamma distribution with point mass [p0] at zero.
 *
 * With probability [p0], the value is exactly zero.
 * With probability (1 - [p0]), the value follows a Gamma distribution
 * parameterized by [alpha] (shape) and [scale].
 */
class ZeroInflatedGammaDistribution(
    @JvmField val p0: Double,
    @JvmField val alpha: Double,
    @JvmField val scale: Double
) : SpeedDistribution {

    private val gamma = GammaDistribution(alpha, scale)

    override val mean: Double get() = (1 - p0) * gamma.mean

    override fun pdf(x: Double): Double {
        // The PDF at x=0 is technically a Dirac delta with weight p0.
        // For continuous x > 0, we return the weighted Gamma PDF.
        if (x <= 0) return 0.0
        return (1 - p0) * gamma.pdf(x)
    }

    override fun cdf(x: Double): Double {
        // CDF includes the point mass at zero
        if (x < 0) return 0.0
        if (x == 0.0) return p0
        return p0 + (1 - p0) * gamma.cdf(x)
    }

    override fun quantile(p: Double): Double {
        if (p <= 0) return 0.0
        if (p >= 1) return Double.MAX_VALUE
        // If p <= p0, we're in the point mass at zero
        if (p <= p0) return 0.0
        // Otherwise, map p into the Gamma portion
        val pGamma = (p - p0) / (1 - p0)
        return gamma.quantile(pGamma)
    }
}
