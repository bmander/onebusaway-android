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
package org.onebusaway.android.util

import android.location.Location
import java.util.Arrays

/**
 * An ordered sequence of geographic points with fast distance-based interpolation.
 * Cumulative distances are precomputed at construction so that [interpolate] and
 * [subPolyline] are O(log n) via binary search.
 */
class Polyline(val points: List<Location>) {

    val totalDistance: Double
        get() = if (cumulativeDistances.isEmpty()) 0.0 else cumulativeDistances.last()

    internal val cumulativeDistances: DoubleArray = points
            .zipWithNext { prev, cur ->
                LocationUtils.haversineDistance(
                        prev.latitude, prev.longitude,
                        cur.latitude, cur.longitude)
            }
            .runningFold(0.0) { acc, dist -> acc + dist }
            .toDoubleArray()

    /** Returns the interpolated position at the given distance along the polyline. */
    fun interpolate(distanceMeters: Double): Location? {
        if (points.isEmpty()) return null
        if (distanceMeters <= 0) return copyLocation(points.first())

        val idx = Arrays.binarySearch(cumulativeDistances, distanceMeters)
        if (idx >= 0) return copyLocation(points[idx])

        val insertionPoint = -idx - 1
        if (insertionPoint >= points.size) return copyLocation(points.last())

        val segStart = insertionPoint - 1
        if (segStart < 0) return copyLocation(points.first())

        val segLen = cumulativeDistances[insertionPoint] - cumulativeDistances[segStart]
        if (segLen <= 0) return copyLocation(points[segStart])

        val fraction = (distanceMeters - cumulativeDistances[segStart]) / segLen
        val p0 = points[segStart]
        val p1 = points[insertionPoint]
        return LocationUtils.makeLocation(
                p0.latitude + fraction * (p1.latitude - p0.latitude),
                p0.longitude + fraction * (p1.longitude - p0.longitude))
    }

    /** Returns the sub-polyline between two distances, with interpolated endpoints. */
    fun subPolyline(startDist: Double, endDist: Double): List<Location>? {
        val start = interpolate(startDist) ?: return null
        val end = interpolate(endDist) ?: return null
        return buildList {
            add(start)
            vertexRange(startDist, endDist)?.let { (from, to) ->
                for (i in from until to) add(points[i])
            }
            add(end)
        }
    }

    /**
     * Finds the range of vertex indices whose cumulative distances fall strictly between
     * [startDist] and [endDist]. Returns a pair (from, to) for use as a half-open range,
     * or null if no vertices fall in range.
     */
    fun vertexRange(startDist: Double, endDist: Double): Pair<Int, Int>? {
        if (cumulativeDistances.isEmpty() || startDist >= endDist) return null
        val rawStart = Arrays.binarySearch(cumulativeDistances, startDist)
        val from = if (rawStart >= 0) rawStart + 1 else -rawStart - 1
        val rawEnd = Arrays.binarySearch(cumulativeDistances, endDist)
        val to = if (rawEnd >= 0) rawEnd else -rawEnd - 1
        return if (from < to) Pair(from, to) else null
    }

    private fun copyLocation(loc: Location) =
            LocationUtils.makeLocation(loc.latitude, loc.longitude)
}
