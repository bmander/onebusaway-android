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

import org.onebusaway.android.extrapolation.Extrapolator
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.DiracDistribution
import org.onebusaway.android.extrapolation.math.ProbDistribution
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.bestDistanceAlongTrip

/**
 * Extrapolator for grade-separated transit (rail, subway) that replays the schedule
 * trajectory forward from the vehicle's current position, including dwell times at stops.
 */
class ScheduleReplayExtrapolator : Extrapolator {

    override fun extrapolate(
            newestValid: ObaTripStatus,
            snapshot: TripDataManager.TripSnapshot,
            queryTimeMs: Long
    ): ProbDistribution? {
        val schedule = snapshot.schedule ?: return null
        val stopTimes = schedule.stopTimes ?: return null
        if (stopTimes.size < 2) return null

        val lastDist = newestValid.bestDistanceAlongTrip ?: return null
        val lastTimeMs = newestValid.lastLocationUpdateTime
        if (lastTimeMs <= 0) return null
        val dtMs = queryTimeMs - lastTimeMs
        if (dtMs < 0 || dtMs > VehicleTrajectoryTracker.MAX_EXTRAPOLATION_AGE_MS) return null

        val distance = replaySchedule(stopTimes, lastDist, dtMs / 1000.0) ?: return null
        return DiracDistribution(distance)
    }
}

/**
 * Replays the schedule forward from [startDist] by [dtSec] seconds.
 *
 * Finds the schedule segment bracketing startDist, computes the corresponding
 * schedule time, adds dtSec, then walks forward through stops — traveling at
 * segment speeds between stops and dwelling at stops for scheduled dwell times.
 *
 * @param stopTimes the trip's scheduled stop times, ordered by distance
 * @param startDist the starting distance along the trip in meters
 * @param dtSec the time to advance in seconds (must be >= 0)
 * @return the extrapolated distance in meters, or null if out of bounds
 */
fun replaySchedule(
        stopTimes: Array<ObaTripSchedule.StopTime>,
        startDist: Double,
        dtSec: Double
): Double? {
    if (stopTimes.size < 2 || dtSec < 0) return null

    // Find the segment bracketing startDist
    val segIdx = findSegmentIndex(stopTimes, startDist) ?: return null
    val segStart = stopTimes[segIdx]
    val segEnd = stopTimes[segIdx + 1]

    val segDist = segEnd.distanceAlongTrip - segStart.distanceAlongTrip
    val travelTimeSec = (segEnd.arrivalTime - segStart.departureTime).toDouble()
    if (segDist <= 0 || travelTimeSec <= 0) return null

    // Interpolate the schedule time at startDist within this segment
    val fraction = (startDist - segStart.distanceAlongTrip) / segDist
    val schedTimeAtStart = segStart.departureTime + fraction * travelTimeSec
    val targetSchedTime = schedTimeAtStart + dtSec

    return walkForward(stopTimes, segIdx, targetSchedTime)
}

/**
 * Walks the schedule timeline forward from segment [startSegIdx] to find the
 * distance corresponding to [targetTime] (in seconds since service date).
 */
private fun walkForward(
        stopTimes: Array<ObaTripSchedule.StopTime>,
        startSegIdx: Int,
        targetTime: Double
): Double {
    // Check if still within the starting segment's travel phase
    val segEnd = stopTimes[startSegIdx + 1]
    if (targetTime <= segEnd.arrivalTime) {
        return interpolateInSegment(stopTimes[startSegIdx], segEnd, targetTime)
    }

    // Walk through subsequent stops
    for (i in (startSegIdx + 1) until stopTimes.size) {
        val stop = stopTimes[i]

        // Dwelling at this stop?
        if (targetTime <= stop.departureTime) {
            return stop.distanceAlongTrip
        }

        // Traveling to next stop?
        if (i + 1 < stopTimes.size) {
            val nextStop = stopTimes[i + 1]
            if (targetTime <= nextStop.arrivalTime) {
                return interpolateInSegment(stop, nextStop, targetTime)
            }
        }
    }

    // Past the last stop: clamp to end of trip
    return stopTimes.last().distanceAlongTrip
}

/** Linearly interpolates distance within a travel segment given a schedule time. */
private fun interpolateInSegment(
        from: ObaTripSchedule.StopTime,
        to: ObaTripSchedule.StopTime,
        targetTime: Double
): Double {
    val travelTime = to.arrivalTime - from.departureTime
    if (travelTime <= 0) return to.distanceAlongTrip
    val elapsed = targetTime - from.departureTime
    val fraction = (elapsed / travelTime).coerceIn(0.0, 1.0)
    val segDist = to.distanceAlongTrip - from.distanceAlongTrip
    return from.distanceAlongTrip + fraction * segDist
}

/** Finds the segment index bracketing the given distance, or null if out of bounds. */
private fun findSegmentIndex(
        stopTimes: Array<ObaTripSchedule.StopTime>,
        distance: Double
): Int? {
    for (i in 0 until stopTimes.size - 1) {
        if (distance >= stopTimes[i].distanceAlongTrip &&
                distance < stopTimes[i + 1].distanceAlongTrip) {
            return i
        }
    }
    // Exactly at the last stop
    if (stopTimes.size >= 2 && distance == stopTimes.last().distanceAlongTrip) {
        return stopTimes.size - 2
    }
    return null
}
