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
import org.onebusaway.android.extrapolation.math.speed.GammaSpeedModel.MPS_TO_MPH

class GammaSpeedModelTest {

    // --- fromSpeeds ---

    @Test
    fun `fromSpeeds returns null when schedSpeed is zero`() {
        assertNull(GammaSpeedModel.fromSpeeds(0.0, 5.0))
    }

    @Test
    fun `fromSpeeds returns null when schedSpeed is negative`() {
        assertNull(GammaSpeedModel.fromSpeeds(-1.0, 5.0))
    }

    @Test
    fun `fromSpeeds falls back to schedSpeed when prevSpeed is zero`() {
        val vSched = 20.0 / MPS_TO_MPH
        val withZero = GammaSpeedModel.fromSpeeds(vSched, 0.0)!!
        val withEqual = GammaSpeedModel.fromSpeeds(vSched, vSched)!!
        assertEquals(withEqual.alpha, withZero.alpha, 1e-9)
        assertEquals(withEqual.scale, withZero.scale, 1e-9)
    }

    @Test
    fun `fromSpeeds falls back to schedSpeed when prevSpeed is negative`() {
        val vSched = 20.0 / MPS_TO_MPH
        val withNeg = GammaSpeedModel.fromSpeeds(vSched, -5.0)!!
        val withEqual = GammaSpeedModel.fromSpeeds(vSched, vSched)!!
        assertEquals(withEqual.alpha, withNeg.alpha, 1e-9)
        assertEquals(withEqual.scale, withNeg.scale, 1e-9)
    }

    @Test
    fun `fromSpeeds produces positive alpha and scale`() {
        for (schedMph in listOf(5.0, 15.0, 30.0, 60.0)) {
            for (prevMph in listOf(5.0, 15.0, 30.0, 60.0)) {
                val params = GammaSpeedModel.fromSpeeds(schedMph / MPS_TO_MPH, prevMph / MPS_TO_MPH)
                assertNotNull("null for sched=$schedMph prev=$prevMph", params)
                assertTrue("alpha <= 0 for sched=$schedMph prev=$prevMph", params!!.alpha > 0)
                assertTrue("scale <= 0 for sched=$schedMph prev=$prevMph", params.scale > 0)
            }
        }
    }

    @Test
    fun `fromSpeeds worked example at 20 and 10 mph`() {
        val params = GammaSpeedModel.fromSpeeds(20.0 / MPS_TO_MPH, 10.0 / MPS_TO_MPH)!!
        // v_eff = 20^(1-0.1699) * 10^0.1699 ≈ 17.5 mph
        // beta0 in linear ramp region, alpha ≈ 1.93, scale ≈ 10.58
        assertEquals(1.93, params.alpha, 0.15)
        assertEquals(10.58, params.scale, 1.0)
    }

    @Test
    fun `fromSpeeds at very low speed`() {
        val params = GammaSpeedModel.fromSpeeds(1.0 / MPS_TO_MPH, 1.0 / MPS_TO_MPH)
        assertNotNull(params)
        assertTrue(params!!.alpha > 0)
        assertTrue(params.scale > 0)
    }

    @Test
    fun `fromSpeeds at highway speed`() {
        // 60 mph is well above the kink (26.95 mph), so beta0 = END_B0
        val params = GammaSpeedModel.fromSpeeds(60.0 / MPS_TO_MPH, 60.0 / MPS_TO_MPH)
        assertNotNull(params)
        assertTrue(params!!.alpha > 0)
        assertTrue(params.scale > 0)
    }

    // --- mean / median ---

    @Test
    fun `mean speed is alpha times scale divided by conversion`() {
        val params = GammaParams(alpha = 3.0, scale = 5.0)
        val expected = 3.0 * 5.0 / MPS_TO_MPH
        assertEquals(expected, GammaSpeedModel.meanSpeedMps(params), 1e-9)
    }

    @Test
    fun `mean speed is close to input when schedSpeed equals prevSpeed`() {
        for (inputMph in listOf(10.0, 20.0, 40.0)) {
            val inputMps = inputMph / MPS_TO_MPH
            val params = GammaSpeedModel.fromSpeeds(inputMps, inputMps)!!
            val meanMph = GammaSpeedModel.meanSpeedMps(params) * MPS_TO_MPH
            assertEquals("mean should be near $inputMph mph", inputMph, meanMph, inputMph * 0.2)
        }
    }

    @Test
    fun `median is less than mean for right-skewed gamma`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        val mean = GammaSpeedModel.meanSpeedMps(params)
        val median = GammaSpeedModel.medianSpeedMps(params)
        assertTrue("median ($median) should be < mean ($mean)", median < mean)
    }

    // --- pdf ---

    @Test
    fun `pdf is zero at zero and negative`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        assertEquals(0.0, GammaSpeedModel.pdf(0.0, params), 1e-12)
        assertEquals(0.0, GammaSpeedModel.pdf(-5.0, params), 1e-12)
    }

    @Test
    fun `pdf is positive for reasonable speeds`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        for (speedMph in listOf(5.0, 10.0, 15.0, 20.0, 30.0)) {
            assertTrue("pdf should be > 0 at $speedMph mph",
                GammaSpeedModel.pdf(speedMph, params) > 0)
        }
    }

    @Test
    fun `pdf integrates to approximately 1`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        // Trapezoidal rule from 0.01 to 200 mph
        val dx = 0.01
        var sum = 0.0
        var x = dx
        while (x <= 200.0) {
            sum += GammaSpeedModel.pdf(x, params) * dx
            x += dx
        }
        assertEquals("pdf should integrate to ~1", 1.0, sum, 0.01)
    }

    // --- cdf ---

    @Test
    fun `cdf is zero at zero and negative`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        assertEquals(0.0, GammaSpeedModel.cdf(0.0, params), 1e-12)
        assertEquals(0.0, GammaSpeedModel.cdf(-1.0, params), 1e-12)
    }

    @Test
    fun `cdf approaches 1 for large values`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        assertTrue(GammaSpeedModel.cdf(100.0, params) > 0.99)
        assertTrue(GammaSpeedModel.cdf(200.0, params) > 0.999)
    }

    @Test
    fun `cdf increases from low to high speeds`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        val low = GammaSpeedModel.cdf(5.0, params)
        val mid = GammaSpeedModel.cdf(15.0, params)
        val high = GammaSpeedModel.cdf(40.0, params)
        assertTrue("cdf(5) < cdf(15)", low < mid)
        assertTrue("cdf(15) < cdf(40)", mid < high)
    }

    @Test
    fun `cdf at median is approximately 0_5`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        val medianMph = GammaSpeedModel.medianSpeedMps(params) * MPS_TO_MPH
        assertEquals(0.5, GammaSpeedModel.cdf(medianMph, params), 0.01)
    }

    // --- quantile ---

    @Test
    fun `quantile at 0 returns 0`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        assertEquals(0.0, GammaSpeedModel.quantile(0.0, params), 1e-12)
    }

    @Test
    fun `quantile at 1 returns MAX_VALUE`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        assertEquals(Double.MAX_VALUE, GammaSpeedModel.quantile(1.0, params), 0.0)
    }

    @Test
    fun `quantile is monotonically increasing`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        var prev = 0.0
        for (p in listOf(0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99)) {
            val q = GammaSpeedModel.quantile(p, params)
            assertTrue("quantile($p) = $q should be > quantile at previous p = $prev", q > prev)
            prev = q
        }
    }

    @Test
    fun `cdf of quantile round-trips for multiple percentiles`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        for (p in doubleArrayOf(0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95)) {
            val q = GammaSpeedModel.quantile(p, params)
            val roundTrip = GammaSpeedModel.cdf(q, params)
            assertEquals("CDF(quantile($p)) should ≈ $p", p, roundTrip, 0.01)
        }
    }

    @Test
    fun `cdf of quantile round-trips across different speed regimes`() {
        for (schedMph in listOf(5.0, 15.0, 40.0)) {
            for (prevMph in listOf(5.0, 15.0, 40.0)) {
                val params = GammaSpeedModel.fromSpeeds(
                    schedMph / MPS_TO_MPH, prevMph / MPS_TO_MPH
                )!!
                val q50 = GammaSpeedModel.quantile(0.5, params)
                val cdf50 = GammaSpeedModel.cdf(q50, params)
                assertEquals(
                    "round-trip failed for sched=$schedMph prev=$prevMph",
                    0.5, cdf50, 0.01
                )
            }
        }
    }

    // --- quantileMps ---

    @Test
    fun `quantileMps converts from mph to mps`() {
        val params = GammaSpeedModel.fromSpeeds(15.0 / MPS_TO_MPH, 15.0 / MPS_TO_MPH)!!
        val qMph = GammaSpeedModel.quantile(0.5, params)
        val qMps = GammaSpeedModel.quantileMps(0.5, params)
        assertEquals(qMph / MPS_TO_MPH, qMps, 1e-9)
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
