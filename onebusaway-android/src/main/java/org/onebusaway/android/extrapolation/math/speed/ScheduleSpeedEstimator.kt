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
import org.onebusaway.android.extrapolation.data.VehicleState

/**
 * Estimates speed using the trip schedule: finds the two stops bracketing the vehicle's
 * current scheduled position and computes segment speed from the timetable.
 */
class ScheduleSpeedEstimator : SpeedEstimator {

    override fun estimateSpeed(
        vehicleId: String?,
        state: VehicleState,
        dataManager: TripDataManager
    ): Double? {
        val currentDist = state.scheduledDistanceAlongTrip ?: return null
        val schedule = dataManager.getSchedule(state.activeTripId) ?: return null
        val stopTimes = schedule.stopTimes
        if (stopTimes == null || stopTimes.size < 2) return null

        // Find the two stops bracketing the current position
        var beforeIdx = -1
        var afterIdx = -1

        for (i in stopTimes.indices) {
            if (stopTimes[i].distanceAlongTrip <= currentDist) {
                beforeIdx = i
            } else {
                afterIdx = i
                break
            }
        }

        // Edge cases: before first stop or after last stop
        if (beforeIdx == -1) {
            beforeIdx = 0
            afterIdx = 1
        } else if (afterIdx == -1) {
            beforeIdx = stopTimes.size - 2
            afterIdx = stopTimes.size - 1
        }

        val distDelta = stopTimes[afterIdx].distanceAlongTrip -
            stopTimes[beforeIdx].distanceAlongTrip
        val timeDelta = stopTimes[afterIdx].arrivalTime -
            stopTimes[beforeIdx].departureTime

        if (distDelta <= 0 || timeDelta <= 0) return null

        return distDelta / timeDelta
    }
}
