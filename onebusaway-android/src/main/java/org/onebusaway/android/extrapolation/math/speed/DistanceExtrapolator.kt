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

import android.location.Location
import org.onebusaway.android.extrapolation.data.VehicleHistoryEntry
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.util.LocationUtils
import java.util.Arrays
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared extrapolation logic for estimating vehicle distance along a trip.
 */
object DistanceExtrapolator {

    /** Max age of the newest AVL entry before we consider extrapolation unreliable. */
    private const val MAX_EXTRAPOLATION_AGE_MS = 5L * 60 * 1000

    /** Matches OBA server's SphericalGeometryLibrary.RADIUS_OF_EARTH_IN_KM * 1000. */
    const val EARTH_RADIUS_METERS = 6371010.0

    /**
     * Returns the newest history entry with a valid distance and timestamp, or null.
     */
    @JvmStatic
    fun findNewestValidEntry(history: List<VehicleHistoryEntry>?): VehicleHistoryEntry? {
        if (history == null) return null
        for (i in history.indices.reversed()) {
            val e = history[i]
            if (e.bestDistanceAlongTrip != null && e.lastLocationUpdateTime > 0) {
                return e
            }
        }
        return null
    }

    /**
     * Extrapolates the current distance along the trip based on the newest valid history entry
     * and estimated speed. Returns null if extrapolation is not possible.
     *
     * @param history       vehicle history entries for the trip
     * @param speedMps      estimated speed in meters per second
     * @param currentTimeMs current time in milliseconds
     * @return extrapolated distance in meters, or null
     */
    @JvmStatic
    fun extrapolateDistance(
        history: List<VehicleHistoryEntry>?,
        speedMps: Double,
        currentTimeMs: Long
    ): Double? {
        if (speedMps <= 0) return null
        val newest = findNewestValidEntry(history) ?: return null
        val lastTime = newest.lastLocationUpdateTime
        if (currentTimeMs - lastTime > MAX_EXTRAPOLATION_AGE_MS) return null
        val lastDist = newest.bestDistanceAlongTrip ?: return null
        return lastDist + speedMps * (currentTimeMs - lastTime) / 1000.0
    }

    /**
     * Finds the index of the next stop the vehicle has not yet reached, based on
     * distance along the trip.
     *
     * @param stopTimes        array of stop times with distance info
     * @param distanceAlongTrip current distance along the trip in meters
     * @return index of the next stop, or stopTimes.size if past the last stop,
     *         or null if stopTimes is null/empty
     */
    @JvmStatic
    fun findNextStopIndex(
        stopTimes: Array<ObaTripSchedule.StopTime>?,
        distanceAlongTrip: Double
    ): Int? {
        if (stopTimes == null || stopTimes.isEmpty()) return null
        for (i in stopTimes.indices) {
            if (stopTimes[i].distanceAlongTrip > distanceAlongTrip) return i
        }
        return stopTimes.size
    }

    /**
     * Builds a cumulative distance array for a polyline. Entry i holds the total
     * distance from the first point to point i. Entry 0 is always 0.
     *
     * Uses the same Haversine formula and Earth radius as the OBA server
     * (SphericalGeometryLibrary) so that distance values are consistent with
     * the server's distanceAlongTrip values.
     *
     * @param polylinePoints decoded polyline points
     * @return cumulative distance array (same length as polylinePoints), or null
     */
    @JvmStatic
    fun buildCumulativeDistances(polylinePoints: List<Location>?): DoubleArray? {
        if (polylinePoints == null || polylinePoints.isEmpty()) return null
        val cumDist = DoubleArray(polylinePoints.size)
        cumDist[0] = 0.0
        for (i in 1 until polylinePoints.size) {
            val prev = polylinePoints[i - 1]
            val cur = polylinePoints[i]
            cumDist[i] = cumDist[i - 1] + haversineDistance(
                prev.latitude, prev.longitude, cur.latitude, cur.longitude
            )
        }
        return cumDist
    }

    /**
     * Haversine great-circle distance matching the OBA server's
     * SphericalGeometryLibrary.distance() exactly — same formula, same
     * Earth radius (6371.01 km).
     *
     * @return distance in meters
     */
    internal fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLon1 = Math.toRadians(lon1)
        val rLat2 = Math.toRadians(lat2)
        val rLon2 = Math.toRadians(lon2)

        val deltaLon = rLon2 - rLon1
        val cosLat2 = cos(rLat2)
        val sinDeltaLon = sin(deltaLon)
        val cosLat1 = cos(rLat1)
        val sinLat2 = sin(rLat2)
        val sinLat1 = sin(rLat1)
        val cosDeltaLon = cos(deltaLon)

        val y = sqrt(
            (cosLat2 * sinDeltaLon) * (cosLat2 * sinDeltaLon)
                + (cosLat1 * sinLat2 - sinLat1 * cosLat2 * cosDeltaLon)
                * (cosLat1 * sinLat2 - sinLat1 * cosLat2 * cosDeltaLon)
        )
        val x = sinLat1 * sinLat2 + cosLat1 * cosLat2 * cosDeltaLon

        return EARTH_RADIUS_METERS * atan2(y, x)
    }

    /**
     * Interpolates a position along a polyline at a given distance from the start.
     * Uses a precomputed cumulative distance array for O(log n) binary search.
     *
     * @param polylinePoints decoded polyline points (lat/lng)
     * @param cumDist        precomputed cumulative distances from [buildCumulativeDistances]
     * @param distanceMeters target distance along the polyline in meters
     * @return interpolated Location, or null if the polyline is empty
     */
    @JvmStatic
    fun interpolateAlongPolyline(
        polylinePoints: List<Location>?,
        cumDist: DoubleArray?,
        distanceMeters: Double
    ): Location? {
        val result = LocationUtils.makeLocation(0.0, 0.0)
        return if (interpolateAlongPolyline(polylinePoints, cumDist, distanceMeters, result)) {
            result
        } else {
            null
        }
    }

    /**
     * Interpolates a position along a polyline, writing into a reusable Location
     * to avoid allocation on the hot path. Returns true if successful.
     *
     * @param polylinePoints decoded polyline points (lat/lng)
     * @param cumDist        precomputed cumulative distances from [buildCumulativeDistances]
     * @param distanceMeters target distance along the polyline in meters
     * @param out            reusable Location to write the result into
     * @return true if interpolation succeeded, false if inputs were invalid
     */
    @JvmStatic
    fun interpolateAlongPolyline(
        polylinePoints: List<Location>?,
        cumDist: DoubleArray?,
        distanceMeters: Double,
        out: Location?
    ): Boolean {
        if (polylinePoints == null || polylinePoints.isEmpty() || cumDist == null || out == null) {
            return false
        }
        if (distanceMeters <= 0) {
            val first = polylinePoints[0]
            out.latitude = first.latitude
            out.longitude = first.longitude
            return true
        }

        val idx = Arrays.binarySearch(cumDist, distanceMeters)
        if (idx >= 0) {
            val exact = polylinePoints[idx]
            out.latitude = exact.latitude
            out.longitude = exact.longitude
            return true
        }
        // binarySearch returns -(insertion point) - 1
        val insertionPoint = -idx - 1
        if (insertionPoint >= polylinePoints.size) {
            val last = polylinePoints[polylinePoints.size - 1]
            out.latitude = last.latitude
            out.longitude = last.longitude
            return true
        }

        val segStart = insertionPoint - 1
        if (segStart < 0) {
            val first = polylinePoints[0]
            out.latitude = first.latitude
            out.longitude = first.longitude
            return true
        }

        val segLen = cumDist[insertionPoint] - cumDist[segStart]
        if (segLen <= 0) {
            val p = polylinePoints[segStart]
            out.latitude = p.latitude
            out.longitude = p.longitude
            return true
        }

        val fraction = (distanceMeters - cumDist[segStart]) / segLen
        val p0 = polylinePoints[segStart]
        val p1 = polylinePoints[insertionPoint]
        out.latitude = p0.latitude + fraction * (p1.latitude - p0.latitude)
        out.longitude = p0.longitude + fraction * (p1.longitude - p0.longitude)
        return true
    }

    /**
     * Finds the range of polyline vertex indices whose cumulative distances fall
     * strictly between startDist and endDist. Uses binary search for O(log n).
     *
     * @param cumDist   sorted cumulative distance array
     * @param startDist start distance in meters
     * @param endDist   end distance in meters
     * @return int array {startIndex, endIndex} for use with a for loop (exclusive end),
     *         or null if no vertices fall in range
     */
    @JvmStatic
    fun findVertexRange(cumDist: DoubleArray?, startDist: Double, endDist: Double): IntArray? {
        if (cumDist == null || cumDist.isEmpty() || startDist >= endDist) return null

        // Find first index where cumDist[i] > startDist
        val rawStart = Arrays.binarySearch(cumDist, startDist)
        val from = if (rawStart >= 0) rawStart + 1 else -rawStart - 1

        // Find first index where cumDist[i] >= endDist
        val rawEnd = Arrays.binarySearch(cumDist, endDist)
        val to = if (rawEnd >= 0) rawEnd else -rawEnd - 1

        return if (from < to) intArrayOf(from, to) else null
    }

    /**
     * Returns the bearing (in degrees clockwise from north) of the polyline segment
     * at the given distance along the polyline. Returns NaN if inputs are invalid.
     *
     * @param polylinePoints decoded polyline points (lat/lng)
     * @param cumDist        precomputed cumulative distances
     * @param distanceMeters target distance along the polyline in meters
     * @return bearing in degrees [0, 360), or NaN
     */
    @JvmStatic
    fun headingAlongPolyline(
        polylinePoints: List<Location>?,
        cumDist: DoubleArray?,
        distanceMeters: Double
    ): Double {
        if (polylinePoints == null || polylinePoints.size < 2 || cumDist == null) {
            return Double.NaN
        }

        // Clamp to valid range
        if (distanceMeters <= 0) {
            return bearing(polylinePoints[0], polylinePoints[1])
        }

        val idx = Arrays.binarySearch(cumDist, distanceMeters)
        val insertionPoint = if (idx >= 0) idx + 1 else -idx - 1

        if (insertionPoint >= polylinePoints.size) {
            val n = polylinePoints.size
            return bearing(polylinePoints[n - 2], polylinePoints[n - 1])
        }

        val segStart = insertionPoint - 1
        if (segStart < 0) {
            return bearing(polylinePoints[0], polylinePoints[1])
        }

        return bearing(polylinePoints[segStart], polylinePoints[insertionPoint])
    }

    /**
     * Computes the initial bearing from point A to point B in degrees [0, 360).
     */
    private fun bearing(a: Location, b: Location): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360) % 360
    }
}
