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
package org.onebusaway.android.extrapolation.test

import android.location.Location
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.Polyline

@RunWith(AndroidJUnit4::class)
class InterpolateAlongPolylineTest {

    private fun loc(lat: Double, lng: Double): Location =
            LocationUtils.makeLocation(lat, lng)

    // Three points along the equator, each ~111km apart
    private val poly = Polyline(listOf(loc(0.0, 0.0), loc(0.0, 1.0), loc(0.0, 2.0)))

    @Test
    fun emptyPolylineReturnsNull() {
        assertNull(Polyline(emptyList()).interpolate(50.0))
    }

    @Test
    fun zeroDistanceReturnsFirstPoint() {
        val result = poly.interpolate(0.0)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(0.0, result.longitude, 1e-12)
    }

    @Test
    fun negativeDistanceReturnsFirstPoint() {
        val result = poly.interpolate(-10.0)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(0.0, result.longitude, 1e-12)
    }

    @Test
    fun distanceBeyondEndReturnsLastPoint() {
        val result = poly.interpolate(999_999.0)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(2.0, result.longitude, 1e-12)
    }

    @Test
    fun midSegmentInterpolation() {
        // Halfway along the first segment should give ~0.5 degrees longitude
        val segLen = poly.interpolate(0.0)!!.distanceTo(loc(0.0, 1.0)).toDouble()
        val result = poly.interpolate(segLen / 2)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(0.5, result.longitude, 0.01)
    }

    @Test
    fun exactVertexDistance() {
        // At the exact distance of the second point
        val segLen = poly.interpolate(0.0)!!.distanceTo(loc(0.0, 1.0)).toDouble()
        val result = poly.interpolate(segLen)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(1.0, result.longitude, 1e-6)
    }

    @Test
    fun singlePointPolyline() {
        val single = Polyline(listOf(loc(47.6, -122.3)))
        val result = single.interpolate(50.0)!!
        assertEquals(47.6, result.latitude, 1e-12)
        assertEquals(-122.3, result.longitude, 1e-12)
    }

    @Test
    fun subPolylineReturnsEndpoints() {
        val segLen = poly.interpolate(0.0)!!.distanceTo(loc(0.0, 1.0)).toDouble()
        val sub = poly.subPolyline(segLen * 0.25, segLen * 0.75)
        assertNotNull(sub)
        assertEquals(0.25, sub!!.first().longitude, 0.01)
        assertEquals(0.75, sub.last().longitude, 0.01)
    }
}
