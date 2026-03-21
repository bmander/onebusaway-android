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

import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.DiracDistribution
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.speedAtDistance

/**
 * Estimates speed using the trip schedule: finds the two stops bracketing the vehicle's current
 * scheduled position and computes segment speed from the timetable.
 */
class ScheduleSpeedEstimator(private val dataManager: TripDataManager) : SpeedEstimator {

    override fun estimateSpeed(tripId: String, queryTime: Long): SpeedEstimateResult {
        val status = dataManager.getLastState(tripId)
        val schedule = dataManager.getSchedule(tripId)
        return estimateFromStatus(status, schedule, queryTime)
    }

    override fun estimateSpeed(tripId: String, queryTime: Long,
                               snapshot: TripDataManager.TripSnapshot): SpeedEstimateResult =
            estimateFromStatus(snapshot.lastState, snapshot.schedule, queryTime)

    private fun estimateFromStatus(
            status: ObaTripStatus?,
            schedule: ObaTripSchedule?,
            queryTime: Long): SpeedEstimateResult {
        if (status == null) return SpeedEstimateResult.Failure(
                SpeedEstimateError.InsufficientData("No state for trip"))

        if (queryTime < status.lastLocationUpdateTime) {
            return SpeedEstimateResult.Failure(
                    SpeedEstimateError.TimestampOutOfBounds("Query time is before vehicle state")
            )
        }

        val currentDist = status.scheduledDistanceAlongTrip
                ?: return SpeedEstimateResult.Failure(
                        SpeedEstimateError.InsufficientData("No scheduled distance along trip"))

        val speed = schedule?.speedAtDistance(currentDist)
                ?: return SpeedEstimateResult.Failure(
                        SpeedEstimateError.InsufficientData("Cannot compute schedule speed"))

        return SpeedEstimateResult.Success(DiracDistribution(speed))
    }
}
