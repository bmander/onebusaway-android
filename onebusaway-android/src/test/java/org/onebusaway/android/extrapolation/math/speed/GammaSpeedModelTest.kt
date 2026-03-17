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
import org.onebusaway.android.extrapolation.math.speed.GammaSpeedModel.GammaParams

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
                val params = GammaSpeedModel.fromSpeeds(sched, prev)
                assertNotNull("null for sched=$sched prev=$prev", params)
                assertTrue("alpha <= 0", params!!.alpha > 0)
                assertTrue("scale <= 0", params.scale > 0)
            }
        }
    }

    @Test
    fun `fromSpeeds worked example at 20 and 10 mph`() {
        val params = GammaSpeedModel.fromSpeeds(mps20, mps10)!!
        // alpha depends on vEff in mph space; should be ~1.93
        assertEquals(1.93, params.alpha, 0.15)
        // scale is now in m/s (~4.73)
        assertEquals(4.73, params.scale, 0.5)
    }

    @Test
    fun `fromSpeeds at very low speed`() {
        val params = GammaSpeedModel.fromSpeeds(0.447, 0.447) // ~1 mph
        assertNotNull(params)
        assertTrue(params!!.alpha > 0)
        assertTrue(params.scale > 0)
    }

    @Test
    fun `fromSpeeds at highway speed`() {
        val params = GammaSpeedModel.fromSpeeds(mps60, mps60)
        assertNotNull(params)
        assertTrue(params!!.alpha > 0)
        assertTrue(params.scale > 0)
    }

    // --- mean / median ---

    @Test
    fun `mean speed is alpha times scale`() {
        val params = GammaParams(alpha = 3.0, scale = 5.0)
        assertEquals(15.0, GammaSpeedModel.mean(params), 1e-9)
    }

    @Test
    fun `mean speed is close to input when schedSpeed equals prevSpeed`() {
        for (inputMps in listOf(mps10, mps20, mps40)) {
            val params = GammaSpeedModel.fromSpeeds(inputMps, inputMps)!!
            val mean = GammaSpeedModel.mean(params)
            assertEquals("mean should be near $inputMps m/s", inputMps, mean, inputMps * 0.2)
        }
    }

    @Test
    fun `median is less than mean for right-skewed gamma`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        val mean = GammaSpeedModel.mean(params)
        val median = GammaSpeedModel.median(params)
        assertTrue("median ($median) should be < mean ($mean)", median < mean)
    }

    // --- pdf ---

    @Test
    fun `pdf is zero at zero and negative`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertEquals(0.0, GammaSpeedModel.pdf(0.0, params), 1e-12)
        assertEquals(0.0, GammaSpeedModel.pdf(-5.0, params), 1e-12)
    }

    @Test
    fun `pdf is positive for reasonable speeds`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        for (speed in listOf(mps5, mps10, mps15, mps20)) {
            assertTrue("pdf should be > 0 at $speed m/s",
                GammaSpeedModel.pdf(speed, params) > 0)
        }
    }

    @Test
    fun `pdf integrates to approximately 1`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        // Trapezoidal rule from 0.01 to 90 m/s (~200 mph)
        val dx = 0.005
        var sum = 0.0
        var x = dx
        while (x <= 90.0) {
            sum += GammaSpeedModel.pdf(x, params) * dx
            x += dx
        }
        assertEquals("pdf should integrate to ~1", 1.0, sum, 0.01)
    }

    // --- cdf ---

    @Test
    fun `cdf is zero at zero and negative`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertEquals(0.0, GammaSpeedModel.cdf(0.0, params), 1e-12)
        assertEquals(0.0, GammaSpeedModel.cdf(-1.0, params), 1e-12)
    }

    @Test
    fun `cdf approaches 1 for large values`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertTrue(GammaSpeedModel.cdf(45.0, params) > 0.99)  // ~100 mph
        assertTrue(GammaSpeedModel.cdf(90.0, params) > 0.999) // ~200 mph
    }

    @Test
    fun `cdf increases from low to high speeds`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        val low = GammaSpeedModel.cdf(mps5, params)
        val mid = GammaSpeedModel.cdf(mps15, params)
        val high = GammaSpeedModel.cdf(mps40, params)
        assertTrue("cdf(5mph) < cdf(15mph)", low < mid)
        assertTrue("cdf(15mph) < cdf(40mph)", mid < high)
    }

    @Test
    fun `cdf at median is approximately 0_5`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        val median = GammaSpeedModel.median(params)
        assertEquals(0.5, GammaSpeedModel.cdf(median, params), 0.01)
    }

    // --- quantile ---

    @Test
    fun `quantile at 0 returns 0`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertEquals(0.0, GammaSpeedModel.quantile(0.0, params), 1e-12)
    }

    @Test
    fun `quantile at 1 returns MAX_VALUE`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        assertEquals(Double.MAX_VALUE, GammaSpeedModel.quantile(1.0, params), 0.0)
    }

    @Test
    fun `quantile is monotonically increasing`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        var prev = 0.0
        for (p in listOf(0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99)) {
            val q = GammaSpeedModel.quantile(p, params)
            assertTrue("quantile($p) = $q should be > $prev", q > prev)
            prev = q
        }
    }

    @Test
    fun `cdf of quantile round-trips for multiple percentiles`() {
        val params = GammaSpeedModel.fromSpeeds(mps15, mps15)!!
        for (p in doubleArrayOf(0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95)) {
            val q = GammaSpeedModel.quantile(p, params)
            val roundTrip = GammaSpeedModel.cdf(q, params)
            assertEquals("CDF(quantile($p)) should ≈ $p", p, roundTrip, 0.01)
        }
    }

    @Test
    fun `cdf of quantile round-trips across different speed regimes`() {
        for (sched in listOf(mps5, mps15, mps40)) {
            for (prev in listOf(mps5, mps15, mps40)) {
                val params = GammaSpeedModel.fromSpeeds(sched, prev)!!
                val q50 = GammaSpeedModel.quantile(0.5, params)
                val cdf50 = GammaSpeedModel.cdf(q50, params)
                assertEquals(
                    "round-trip failed for sched=$sched prev=$prev",
                    0.5, cdf50, 0.01
                )
            }
        }
    }

    // --- GammaParams ---

    @Test
    fun `GammaParams equality`() {
        val a = GammaParams(1.5, 3.0)
        val b = GammaParams(1.5, 3.0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `GammaParams fields accessible`() {
        val p = GammaParams(2.0, 7.5)
        assertEquals(2.0, p.alpha, 0.0)
        assertEquals(7.5, p.scale, 0.0)
    }
}
