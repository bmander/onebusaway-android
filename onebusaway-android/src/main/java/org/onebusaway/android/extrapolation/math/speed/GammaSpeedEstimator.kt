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
import org.onebusaway.android.extrapolation.math.SpeedDistribution

/**
 * Speed estimator using the H12 gamma distribution model.
 * Combines schedule speed with the most recent AVL-derived speed to produce
 * a gamma distribution over vehicle speed. Falls back to a point estimate
 * from the schedule if the gamma model can't be computed.
 */
class GammaSpeedEstimator : SpeedEstimator {

    private data class AvlDistanceSample(
        val distanceAlongTrip: Double,
        val lastLocationUpdateTime: Long
    )

    private val scheduleEstimator = ScheduleSpeedEstimator()

    override fun estimateSpeed(
        state: VehicleState,
        timestampMs: Long,
        dataManager: TripDataManager
    ): SpeedDistribution? {
        val tripId = state.activeTripId ?: return null

        val scheduleDist = scheduleEstimator.estimateSpeed(state, timestampMs, dataManager)
        val scheduleSpeed = scheduleDist?.mean ?: return null

        if (isTripNotYetStarted(tripId, timestampMs, dataManager) == true) return null

        val vPrev = computePreviousAvlSpeed(tripId, dataManager)
            ?: return scheduleDist

        return GammaSpeedModel.fromSpeeds(scheduleSpeed, vPrev)
            ?: scheduleDist
    }

    private fun isTripNotYetStarted(
        tripId: String,
        timeMs: Long,
        dataManager: TripDataManager
    ): Boolean? =
        dataManager.getServiceDate(tripId)?.let { serviceDate ->
            dataManager.getSchedule(tripId)?.startTime?.let { startTime ->
                timeMs < serviceDate + startTime * 1000
            }
        }

    private fun computePreviousAvlSpeed(
        tripId: String?,
        dataManager: TripDataManager
    ): Double? = tripId?.let { dataManager.getHistoryReadOnly(it) }
        ?.asReversed()
        ?.mapNotNull { e ->
            e.bestDistanceAlongTrip?.takeIf { e.lastLocationUpdateTime > 0 }
                ?.let { dist -> AvlDistanceSample(dist, e.lastLocationUpdateTime) }
        }
        ?.take(2)
        ?.takeIf { it.size >= 2 }
        ?.let { (newer, older) ->
            val dtMs = newer.lastLocationUpdateTime - older.lastLocationUpdateTime
            val dd = newer.distanceAlongTrip - older.distanceAlongTrip
            (dd / (dtMs / 1000.0)).takeIf { dtMs > 0 && it >= 0 }
        }
}
