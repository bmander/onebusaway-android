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
import org.onebusaway.android.extrapolation.math.PointEstimate
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
        dataManager: TripDataManager
    ): SpeedDistribution? {
        val scheduleDist = scheduleEstimator.estimateSpeed(state, dataManager)
        val vSched = scheduleDist?.mean ?: 0.0

        val tripId = state.activeTripId
        if (isTripNotYetStarted(tripId, vSched, dataManager)) return null

        val vPrev = computePreviousAvlSpeed(tripId, dataManager)

        return GammaSpeedModel.fromSpeeds(vSched, vPrev)
            ?: scheduleDist
    }

    private fun isTripNotYetStarted(
        tripId: String?,
        vSched: Double,
        dataManager: TripDataManager
    ): Boolean {
        if (tripId == null || vSched <= 0) return false
        val serviceDate = dataManager.getServiceDate(tripId) ?: return false
        val schedule = dataManager.getSchedule(tripId) ?: return false
        val stopTimes = schedule.stopTimes
        if (stopTimes == null || stopTimes.isEmpty()) return false
        val tripStartMs = serviceDate + stopTimes[0].departureTime * 1000L
        return System.currentTimeMillis() < tripStartMs
    }

    private fun computePreviousAvlSpeed(
        tripId: String?,
        dataManager: TripDataManager
    ): Double {
        if (tripId == null) return 0.0
        val history = dataManager.getHistoryReadOnly(tripId)
        if (history.size < 2) return 0.0

        var newer: AvlDistanceSample? = null
        var older: AvlDistanceSample? = null
        for (i in history.indices.reversed()) {
            val e = history[i]
            val distanceAlongTrip = e.bestDistanceAlongTrip
            if (distanceAlongTrip != null && e.lastLocationUpdateTime > 0) {
                val sample = AvlDistanceSample(distanceAlongTrip, e.lastLocationUpdateTime)
                if (newer == null) {
                    newer = sample
                } else {
                    older = sample
                    break
                }
            }
        }
        if (older == null || newer == null) return 0.0

        val dtMs = newer.lastLocationUpdateTime - older.lastLocationUpdateTime
        if (dtMs <= 0) return 0.0

        val dd = newer.distanceAlongTrip - older.distanceAlongTrip
        return maxOf(0.0, dd / (dtMs / 1000.0))
    }
}
