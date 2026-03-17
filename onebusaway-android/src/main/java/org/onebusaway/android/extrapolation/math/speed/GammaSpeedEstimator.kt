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
import org.onebusaway.android.extrapolation.math.speed.SpeedEstimateResult

/**
 * Speed estimator using the H12 gamma distribution model.
 * Combines schedule speed with the most recent AVL-derived speed to produce
 * a gamma distribution over vehicle speed. Falls back to a point estimate
 * from the schedule if the gamma model can't be computed.
 */
class GammaSpeedEstimator : SpeedEstimator {

    companion object {
        /** Maximum time horizon (ms) for which gamma speed estimates are considered valid. */
        const val MAX_HORIZON_MS = 15 * 60 * 1000L // 15 minutes
    }

    private data class AvlDistanceSample(
        val distanceAlongTrip: Double,
        val lastLocationUpdateTime: Long
    )

    private val scheduleEstimator = ScheduleSpeedEstimator()

    override fun estimateSpeed(
        state: VehicleState,
        queryTime: Long,
        dataManager: TripDataManager
    ): SpeedEstimateResult {
        val tripId = state.activeTripId
            ?: return SpeedEstimateResult.Failure(
                SpeedEstimateError.InsufficientData("No active trip ID")
            )

        val scheduleResult = scheduleEstimator.estimateSpeed(state, queryTime, dataManager)

        // Propagate timestamp errors from schedule estimator
        if (scheduleResult is SpeedEstimateResult.Failure) {
            return scheduleResult
        }

        val scheduleDist = (scheduleResult as SpeedEstimateResult.Success).distribution
        val scheduleSpeed = scheduleDist.mean

        // Check if time is after the start of the trip
        val serviceDate = dataManager.getServiceDate(tripId) ?: return SpeedEstimateResult.Failure(
            SpeedEstimateError.InsufficientData("No service date for trip")
        )
        val startTime = dataManager.getSchedule(tripId)?.startTime ?: return SpeedEstimateResult.Failure(
            SpeedEstimateError.InsufficientData("No schedule for trip")
        )
        if (queryTime < serviceDate + startTime * 1000) return SpeedEstimateResult.Failure(
            SpeedEstimateError.InsufficientData("Time is before trip start")
        )
        if (queryTime < state.timestamp) return SpeedEstimateResult.Failure(
            SpeedEstimateError.TimestampOutOfBounds("Query time is before vehicle state")
        )
        if (queryTime - state.timestamp > MAX_HORIZON_MS) return SpeedEstimateResult.Failure(
            SpeedEstimateError.TimestampOutOfBounds("Query time exceeds max horizon")
        )

        val vPrev = computePreviousAvlSpeed(tripId, queryTime, dataManager)
            ?: return scheduleResult

        return SpeedEstimateResult.Success(
            GammaSpeedModel.fromSpeeds(scheduleSpeed, vPrev) ?: scheduleDist
        )
    }

    private fun computePreviousAvlSpeed(
        tripId: String,
        queryTime: Long,
        dataManager: TripDataManager
    ): Double? {


        // Compute speed from two most recent AVL samples
        return dataManager.getHistoryReadOnly(tripId)
            .asReversed()
            .mapNotNull { e ->
                e.bestDistanceAlongTrip?.takeIf { e.lastLocationUpdateTime > 0 }
                    ?.let { dist -> AvlDistanceSample(dist, e.lastLocationUpdateTime) }
            }
            .take(2)
            .takeIf { it.size >= 2 }
            ?.let { (newer, older) ->
                val dtMs = newer.lastLocationUpdateTime - older.lastLocationUpdateTime
                val dd = newer.distanceAlongTrip - older.distanceAlongTrip
                (dd / (dtMs / 1000.0)).takeIf { dtMs > 0 && it >= 0 }
            }
    }
}
