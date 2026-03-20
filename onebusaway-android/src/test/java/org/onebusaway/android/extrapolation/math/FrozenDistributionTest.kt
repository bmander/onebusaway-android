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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onebusaway.android.extrapolation.math.speed.gammaProbDistribution

class FrozenDistributionTest {

    @Test
    fun `frozen quantile matches source within interpolation error`() {
        val source = GammaDistribution(alpha = 3.0, scale = 2.5)
        val frozen = FrozenDistribution(source)

        for (p in doubleArrayOf(0.01, 0.10, 0.25, 0.50, 0.75, 0.90, 0.99)) {
            val expected = source.quantile(p)
            val actual = frozen.quantile(p)
            assertEquals("quantile($p)", expected, actual, expected * 0.02)
        }
    }

    @Test
    fun `frozen quantile boundaries`() {
        val source = GammaDistribution(alpha = 2.0, scale = 3.0)
        val frozen = FrozenDistribution(source)

        assertEquals(0.0, frozen.quantile(0.0), 0.0)
        assertTrue(frozen.quantile(1.0) > 0)
        assertEquals(0.0, frozen.quantile(-0.1), 0.0)
    }

    @Test
    fun `frozen quantile is monotonically non-decreasing`() {
        val source = GammaDistribution(alpha = 5.0, scale = 1.5)
        val frozen = FrozenDistribution(source)

        var prev = 0.0
        for (i in 1..100) {
            val p = i / 100.0
            val q = frozen.quantile(p)
            assertTrue("quantile($p) = $q should be >= $prev", q >= prev)
            prev = q
        }
    }

    @Test
    fun `higher resolution gives tighter accuracy`() {
        val source = GammaDistribution(alpha = 3.0, scale = 2.5)
        val coarse = FrozenDistribution(source, resolution = 20)
        val fine = FrozenDistribution(source, resolution = 500)

        val p = 0.73 // mid-table value that tests interpolation
        val exact = source.quantile(p)
        val coarseErr = kotlin.math.abs(coarse.quantile(p) - exact)
        val fineErr = kotlin.math.abs(fine.quantile(p) - exact)
        assertTrue("fine ($fineErr) should be more accurate than coarse ($coarseErr)",
                fineErr <= coarseErr)
    }

    @Test
    fun `frozen preserves mean`() {
        val source = GammaDistribution(alpha = 4.0, scale = 2.0)
        val frozen = FrozenDistribution(source)
        assertEquals(source.mean, frozen.mean, 0.0)
    }

    @Test
    fun `zero-inflated frozen quantile matches analytic`() {
        val gamma = GammaDistribution(alpha = 3.0, scale = 2.5)
        val frozen = FrozenDistribution(gamma)
        val zifg = ZeroInflatedFrozenGammaDistribution(frozen, a = 0.1732, lambda = 0.00462)

        // At dt=0, p0 = A = 0.1732
        // quantile(0.5, 0) should equal gamma.quantile((0.5 - 0.1732) / (1 - 0.1732))
        val p0 = 0.1732
        val pAdj = (0.5 - p0) / (1 - p0)
        val expected = gamma.quantile(pAdj)
        val actual = zifg.quantile(0.5, 0.0)
        assertEquals(expected, actual, expected * 0.02)
    }

    @Test
    fun `zero-inflated frozen quantile at large dt approaches gamma quantile`() {
        val gamma = GammaDistribution(alpha = 3.0, scale = 2.5)
        val frozen = FrozenDistribution(gamma)
        val zifg = ZeroInflatedFrozenGammaDistribution(frozen, a = 0.1732, lambda = 0.00462)

        // At very large dt, p0 -> 0, so quantile(p, dt) -> gamma.quantile(p)
        val largeDt = 10000.0
        val expected = gamma.quantile(0.5)
        val actual = zifg.quantile(0.5, largeDt)
        assertEquals(expected, actual, expected * 0.02)
    }

    @Test
    fun `zero-inflated frozen returns zero below p0`() {
        val gamma = GammaDistribution(alpha = 3.0, scale = 2.5)
        val frozen = FrozenDistribution(gamma)
        val zifg = ZeroInflatedFrozenGammaDistribution(frozen, a = 0.5, lambda = 0.001)

        // At dt=0, p0 = 0.5, so quantile(0.3, 0) should be 0
        assertEquals(0.0, zifg.quantile(0.3, 0.0), 0.0)
    }
}
