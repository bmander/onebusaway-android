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
package org.onebusaway.android.extrapolation.math.prob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroInflatedDistributionTest {

    private val base = GammaDistribution(3.0, 2.0)
    private val dist = ZeroInflatedDistribution(0.3, base)

    @Test
    fun `mean is (1-p0) times base mean`() =
            assertEquals((1 - 0.3) * base.mean, dist.mean, 1e-9)

    @Test
    fun `cdf at zero equals p0`() = assertEquals(0.3, dist.cdf(0.0), 1e-9)

    @Test
    fun `cdf at negative is zero`() = assertEquals(0.0, dist.cdf(-1.0), 1e-9)

    @Test
    fun `cdf approaches 1 for large values`() = assertTrue(dist.cdf(100.0) > 0.99)

    @Test
    fun `pdf is zero at zero and negative`() {
        assertEquals(0.0, dist.pdf(0.0), 0.0)
        assertEquals(0.0, dist.pdf(-5.0), 0.0)
    }

    @Test
    fun `pdf is positive for x greater than zero`() =
            assertTrue(dist.pdf(base.mean) > 0)

    @Test
    fun `quantile returns zero for p at or below p0`() {
        assertEquals(0.0, dist.quantile(0.0), 0.0)
        assertEquals(0.0, dist.quantile(0.1), 0.0)
        assertEquals(0.0, dist.quantile(0.3), 0.0)
    }

    @Test
    fun `quantile returns positive for p above p0`() =
            assertTrue(dist.quantile(0.5) > 0)

    @Test
    fun `quantile is monotonically non-decreasing`() {
        var prev = 0.0
        for (p in listOf(0.0, 0.1, 0.3, 0.5, 0.7, 0.9, 0.99)) {
            val q = dist.quantile(p)
            assertTrue("quantile($p) = $q >= $prev", q >= prev)
            prev = q
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `p0 below 0 throws`() { ZeroInflatedDistribution(-0.1, base) }

    @Test(expected = IllegalArgumentException::class)
    fun `p0 above 1 throws`() { ZeroInflatedDistribution(1.1, base) }

    @Test
    fun `p0 at boundary 0 works`() {
        val pure = ZeroInflatedDistribution(0.0, base)
        assertEquals(base.quantile(0.5), pure.quantile(0.5), base.quantile(0.5) * 0.02)
    }

    @Test
    fun `p0 at boundary 1 gives all zero`() {
        val allZero = ZeroInflatedDistribution(1.0, base)
        assertEquals(0.0, allZero.quantile(0.5), 0.0)
        assertEquals(0.0, allZero.quantile(0.99), 0.0)
    }
}
