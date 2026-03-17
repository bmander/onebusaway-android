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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onebusaway.android.extrapolation.math.GammaDistribution

class GammaSpeedModelTest {

    // m/s test speeds (named for readability)
    private val mps5 = 2.235   // ~5 mph
    private val mps10 = 4.470  // ~10 mph
    private val mps15 = 6.706  // ~15 mph
    private val mps20 = 8.941  // ~20 mph
    private val mps30 = 13.411 // ~30 mph
    private val mps40 = 17.882 // ~40 mph
    private val mps60 = 26.822 // ~60 mph

    // --- fromSpeeds ---

    @Test
    fun `fromSpeeds returns null when schedSpeed is zero`() {
        assertNull(GammaSpeedModel.fromSpeeds(0.0, mps5))
    }

    @Test
    fun `fromSpeeds returns null when schedSpeed is negative`() {
        assertNull(GammaSpeedModel.fromSpeeds(-1.0, mps5))
    }

    @Test
    fun `fromSpeeds falls back to schedSpeed when prevSpeed is zero`() {
        val withZero = GammaSpeedModel.fromSpeeds(mps20, 0.0)!!
        val withEqual = GammaSpeedModel.fromSpeeds(mps20, mps20)!!
        assertEquals(withEqual.alpha, withZero.alpha, 1e-9)
        assertEquals(withEqual.scale, withZero.scale, 1e-9)
    }

    @Test
    fun `fromSpeeds falls back to schedSpeed when prevSpeed is negative`() {
        val withNeg = GammaSpeedModel.fromSpeeds(mps20, -5.0)!!
        val withEqual = GammaSpeedModel.fromSpeeds(mps20, mps20)!!
        assertEquals(withEqual.alpha, withNeg.alpha, 1e-9)
        assertEquals(withEqual.scale, withNeg.scale, 1e-9)
    }

    @Test
    fun `fromSpeeds produces positive alpha and scale`() {
        for (sched in listOf(mps5, mps15, mps30, mps60)) {
            for (prev in listOf(mps5, mps15, mps30, mps60)) {
                val dist = GammaSpeedModel.fromSpeeds(sched, prev)
                assertNotNull("null for sched=$sched prev=$prev", dist)
                assertTrue("alpha <= 0", dist!!.alpha > 0)
                assertTrue("scale <= 0", dist.scale > 0)
            }
        }
    }

    @Test
    fun `fromSpeeds worked example at 20 and 10 mph`() {
        val dist = GammaSpeedModel.fromSpeeds(mps20, mps10)!!
        assertEquals(1.93, dist.alpha, 0.15)
        assertEquals(4.73, dist.scale, 0.5)
    }

    @Test
    fun `fromSpeeds at very low speed`() {
        val dist = GammaSpeedModel.fromSpeeds(0.447, 0.447) // ~1 mph
        assertNotNull(dist)
        assertTrue(dist!!.alpha > 0)
        assertTrue(dist.scale > 0)
    }

    @Test
    fun `fromSpeeds at highway speed`() {
        val dist = GammaSpeedModel.fromSpeeds(mps60, mps60)
        assertNotNull(dist)
        assertTrue(dist!!.alpha > 0)
        assertTrue(dist.scale > 0)
    }

    // --- mean / median ---

    @Test
    fun `mean speed is alpha times scale`() {
        val dist = GammaDistribution(alpha = 3.0, scale = 5.0)
        assertEquals(15.0, dist.mean, 1e-9)
    }

    @Test
    fun `mean speed is close to input when schedSpeed equals prevSpeed`() {
        for (inputMps in listOf(mps10, mps20, mps40)) {
            val dist = GammaSpeedModel.fromSpeeds(inputMps, inputMps)!!
            assertEquals("mean should be near $inputMps m/s", inputMps, dist.mean, inputMps * 0.2)
        }
    }

    @Test
    fun `median is less than mean for right-skewed gamma`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        val median = dist.quantile(0.5)
        assertTrue("median ($median) should be < mean (${dist.mean})", median < dist.mean)
    }

    // --- pdf ---

    @Test
    fun `pdf is zero at zero and negative`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertEquals(0.0, dist.pdf(0.0), 1e-12)
        assertEquals(0.0, dist.pdf(-5.0), 1e-12)
    }

    @Test
    fun `pdf is positive for reasonable speeds`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        for (speed in listOf(mps5, mps10, mps15, mps20)) {
            assertTrue("pdf should be > 0 at $speed m/s", dist.pdf(speed) > 0)
        }
    }

    @Test
    fun `pdf integrates to approximately 1`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        val dx = 0.005
        var sum = 0.0
        var x = dx
        while (x <= 90.0) {
            sum += dist.pdf(x) * dx
            x += dx
        }
        assertEquals("pdf should integrate to ~1", 1.0, sum, 0.01)
    }

    // --- cdf ---

    @Test
    fun `cdf is zero at zero and negative`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertEquals(0.0, dist.cdf(0.0), 1e-12)
        assertEquals(0.0, dist.cdf(-1.0), 1e-12)
    }

    @Test
    fun `cdf approaches 1 for large values`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertTrue(dist.cdf(45.0) > 0.99)
        assertTrue(dist.cdf(90.0) > 0.999)
    }

    @Test
    fun `cdf increases from low to high speeds`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertTrue("cdf(5mph) < cdf(15mph)", dist.cdf(mps5) < dist.cdf(mps15))
        assertTrue("cdf(15mph) < cdf(40mph)", dist.cdf(mps15) < dist.cdf(mps40))
    }

    @Test
    fun `cdf at median is approximately 0_5`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        val median = dist.quantile(0.5)
        assertEquals(0.5, dist.cdf(median), 0.01)
    }

    // --- quantile ---

    @Test
    fun `quantile at 0 returns 0`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertEquals(0.0, dist.quantile(0.0), 1e-12)
    }

    @Test
    fun `quantile at 1 returns MAX_VALUE`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertEquals(Double.MAX_VALUE, dist.quantile(1.0), 0.0)
    }

    @Test
    fun `quantile is monotonically increasing`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        var prev = 0.0
        for (p in listOf(0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99)) {
            val q = dist.quantile(p)
            assertTrue("quantile($p) = $q should be > $prev", q > prev)
            prev = q
        }
    }

    @Test
    fun `cdf of quantile round-trips for multiple percentiles`() {
        val dist = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        for (p in doubleArrayOf(0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95)) {
            val q = dist.quantile(p)
            assertEquals("CDF(quantile($p)) should ≈ $p", p, dist.cdf(q), 0.01)
        }
    }

    @Test
    fun `cdf of quantile round-trips across different speed regimes`() {
        for (sched in listOf(mps5, mps15, mps40)) {
            for (prev in listOf(mps5, mps15, mps40)) {
                val dist = GammaSpeedModel.fromSpeeds(sched, prev)!!
                val q50 = dist.quantile(0.5)
                assertEquals(
                    "round-trip failed for sched=$sched prev=$prev",
                    0.5, dist.cdf(q50), 0.01
                )
            }
        }
    }

    // --- GammaDistribution ---

    @Test
    fun `GammaDistribution fields accessible`() {
        val d = GammaDistribution(2.0, 7.5)
        assertEquals(2.0, d.alpha, 0.0)
        assertEquals(7.5, d.scale, 0.0)
    }
}
