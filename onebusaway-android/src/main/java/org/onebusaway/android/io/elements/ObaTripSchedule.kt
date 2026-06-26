/*
 * Copyright (C) 2010 Paul Watts (paulcwatts@gmail.com)
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
package org.onebusaway.android.io.elements

class ObaTripSchedule(
    val stopTimes: Array<StopTime> = emptyArray(),
    val timeZone: String? = "",
    val previousTripId: String? = "",
    val nextTripId: String? = "",
) {

    class StopTime(
        val stopId: String = "",
        val headsign: String? = "",
        val arrivalTime: Long = 0,
        val departureTime: Long = 0,
        private val historicalOccupancyRaw: String? = "",
        private val predictedOccupancyRaw: String? = "",
        val distanceAlongTrip: Double = 0.0,
    ) {
        /** The average historical occupancy when the vehicle arrives at this stop, or null. */
        val historicalOccupancy: Occupancy? get() = Occupancy.fromString(historicalOccupancyRaw)

        /** The predicted occupancy when the vehicle arrives at this stop, or null. */
        val predictedOccupancy: Occupancy? get() = Occupancy.fromString(predictedOccupancyRaw)
    }

    /**
     * Finds the index of the next stop the vehicle has not yet reached, based on distance along the
     * trip. Returns [stopTimes].size if past the last stop, or null if there are no stop times.
     */
    fun findNextStopIndex(distanceAlongTrip: Double): Int? {
        if (stopTimes.isEmpty()) return null
        for (i in stopTimes.indices) {
            if (stopTimes[i].distanceAlongTrip > distanceAlongTrip) return i
        }
        return stopTimes.size
    }

    /** The scheduled start time of the trip in seconds since the service start date, or null. */
    val startTime: Long?
        get() = if (stopTimes.isEmpty()) null else stopTimes[0].departureTime

    /**
     * Finds the index of the first stop in the segment bracketing [distanceAlongTrip]. The segment
     * spans `stopTimes[result]`..`stopTimes[result + 1]`.
     *
     * @throws IndexOutOfBoundsException if the distance is before the first stop, after the last
     *         stop, or there are fewer than 2 stops.
     */
    fun findSegmentStartIndex(distanceAlongTrip: Double): Int {
        if (stopTimes.size < 2) {
            throw IndexOutOfBoundsException("Fewer than 2 stop times")
        }
        if (distanceAlongTrip < stopTimes[0].distanceAlongTrip) {
            throw IndexOutOfBoundsException("Distance is before first stop")
        }
        if (distanceAlongTrip > stopTimes[stopTimes.size - 1].distanceAlongTrip) {
            throw IndexOutOfBoundsException("Distance is after last stop")
        }
        for (i in 0 until stopTimes.size - 1) {
            if (stopTimes[i].distanceAlongTrip <= distanceAlongTrip &&
                distanceAlongTrip < stopTimes[i + 1].distanceAlongTrip
            ) {
                return i
            }
        }
        // At exactly the last stop's distance
        return stopTimes.size - 2
    }

    companion object {
        @JvmField
        val EMPTY_OBJECT = ObaTripSchedule()
    }
}
