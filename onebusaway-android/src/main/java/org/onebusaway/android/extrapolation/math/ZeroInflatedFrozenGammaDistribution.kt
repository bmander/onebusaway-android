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

import kotlin.math.exp

/**
 * A zero-inflated gamma distribution optimized for per-frame quantile evaluation.
 *
 * The underlying gamma quantile is pre-computed via a [FrozenDistribution] lookup table
 * (built once when alpha/scale change). The zero-inflation probability p0 is computed
 * on the fly from dt using p0 = A * exp(-lambda * dt), making per-frame quantile
 * evaluation O(1) — just an exp(), a rescale, and a table lookup.
 *
 * @param frozenGamma pre-computed gamma quantile table
 * @param a zero-inflation amplitude
 * @param lambda zero-inflation decay rate (1/s)
 */
class ZeroInflatedFrozenGammaDistribution(
        private val frozenGamma: FrozenDistribution,
        private val a: Double,
        private val lambda: Double
) {
    /** The gamma distribution's mean (independent of p0). */
    val gammaMean: Double = frozenGamma.mean

    /**
     * Computes the quantile for a given probability and time since last observation.
     *
     * @param p target probability (0..1)
     * @param dtSec seconds since last AVL observation
     * @return speed in m/s at the given quantile
     */
    fun quantile(p: Double, dtSec: Double): Double {
        if (p <= 0.0) return 0.0
        val p0 = a * exp(-lambda * dtSec)
        if (p <= p0) return 0.0
        if (p >= 1.0) return frozenGamma.quantile(1.0)
        val pAdj = (p - p0) / (1 - p0)
        return frozenGamma.quantile(pAdj)
    }

    /**
     * Computes the median speed for a given time since last observation.
     */
    fun median(dtSec: Double): Double = quantile(0.5, dtSec)
}
