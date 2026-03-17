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
import org.onebusaway.android.extrapolation.math.GammaDistribution

/**
 * Speed estimator using the H12 gamma distribution model.
 * Combines schedule speed with the most recent AVL-derived speed to produce
 * a gamma distribution over vehicle speed.
 */
class GammaSpeedEstimator : SpeedEstimator {

    private val scheduleEstimator = ScheduleSpeedEstimator()
    private var lastGammaDistribution: GammaDistribution? = null
    private var lastScheduleSpeed: Double = 0.0

    override fun estimateSpeed(
        state: VehicleState,
        dataManager: TripDataManager
    ): Double? {
        lastGammaDistribution = null
        lastScheduleSpeed = 0.0

        val scheduleSpeed = scheduleEstimator.estimateSpeed(state, dataManager)
        val vSched = scheduleSpeed ?: 0.0
        lastScheduleSpeed = vSched

        val tripId = state.activeTripId
        if (isTripNotYetStarted(tripId, vSched, dataManager)) return null

        val vPrev = computePreviousAvlSpeed(tripId, dataManager)

        val dist = GammaSpeedModel.fromSpeeds(vSched, vPrev)
        if (dist != null) {
            lastGammaDistribution = dist
            return dist.quantile(0.5)
        }

        // Fall back to schedule speed
        return if (vSched > 0) vSched else null
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
    ): Double = tripId?.let { dataManager.getHistoryReadOnly(it) }
        ?.asReversed()
        ?.mapNotNull { e ->
            e.bestDistanceAlongTrip?.takeIf { e.lastLocationUpdateTime > 0 }
                ?.let { dist -> e.lastLocationUpdateTime to dist }
        }
        ?.take(2)
        ?.takeIf { it.size >= 2 }
        ?.let { (newer, older) ->
            val dtMs = newer.first - older.first
            val dd = newer.second - older.second
            (dd / (dtMs / 1000.0)).takeIf { dtMs > 0 && it >= 0 }
        }
        ?: 0.0

    override fun getLastGammaDistribution(): GammaDistribution? = lastGammaDistribution

    override fun getLastScheduleSpeed(): Double = lastScheduleSpeed

    override fun clearState() {
        lastGammaDistribution = null
        lastScheduleSpeed = 0.0
    }
}
