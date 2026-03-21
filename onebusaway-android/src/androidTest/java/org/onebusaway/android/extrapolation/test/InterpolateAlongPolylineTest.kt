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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onebusaway.android.util.LocationUtils

@RunWith(AndroidJUnit4::class)
class InterpolateAlongPolylineTest {

    private fun loc(lat: Double, lng: Double): Location =
            LocationUtils.makeLocation(lat, lng)

    private val points = listOf(loc(0.0, 0.0), loc(0.0, 1.0), loc(0.0, 2.0))
    private val cumDist = doubleArrayOf(0.0, 100.0, 200.0)

    // --- 3-arg overload (returns Location?) ---

    @Test
    fun emptyPolylineReturnsNull() {
        assertNull(LocationUtils.interpolateAlongPolyline(emptyList(), doubleArrayOf(), 50.0))
    }

    @Test
    fun nullPolylineReturnsFalse() {
        val out = Location("test")
        assertFalse(LocationUtils.interpolateAlongPolyline(null, cumDist, 50.0, out))
    }

    @Test
    fun zeroDistanceReturnsFirstPoint() {
        val result = LocationUtils.interpolateAlongPolyline(points, cumDist, 0.0)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(0.0, result.longitude, 1e-12)
    }

    @Test
    fun negativeDistanceReturnsFirstPoint() {
        val result = LocationUtils.interpolateAlongPolyline(points, cumDist, -10.0)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(0.0, result.longitude, 1e-12)
    }

    @Test
    fun distanceBeyondEndReturnsLastPoint() {
        val result = LocationUtils.interpolateAlongPolyline(points, cumDist, 300.0)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(2.0, result.longitude, 1e-12)
    }

    @Test
    fun exactVertexDistance() {
        val result = LocationUtils.interpolateAlongPolyline(points, cumDist, 100.0)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(1.0, result.longitude, 1e-12)
    }

    @Test
    fun midSegmentInterpolation() {
        // 50m is halfway along the first segment (0,0) -> (0,1)
        val result = LocationUtils.interpolateAlongPolyline(points, cumDist, 50.0)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(0.5, result.longitude, 1e-6)
    }

    @Test
    fun midSecondSegment() {
        // 150m is halfway along the second segment (0,1) -> (0,2)
        val result = LocationUtils.interpolateAlongPolyline(points, cumDist, 150.0)!!
        assertEquals(0.0, result.latitude, 1e-12)
        assertEquals(1.5, result.longitude, 1e-6)
    }

    // --- 4-arg overload (writes into reusable Location) ---

    @Test
    fun fourArgWritesIntoOut() {
        val out = Location("test")
        assertTrue(LocationUtils.interpolateAlongPolyline(points, cumDist, 50.0, out))
        assertEquals(0.5, out.longitude, 1e-6)
    }

    // --- Single-point polyline ---

    @Test
    fun singlePointPolyline() {
        val single = listOf(loc(47.6, -122.3))
        val singleDist = doubleArrayOf(0.0)
        val result = LocationUtils.interpolateAlongPolyline(single, singleDist, 50.0)!!
        assertEquals(47.6, result.latitude, 1e-12)
        assertEquals(-122.3, result.longitude, 1e-12)
    }
}
