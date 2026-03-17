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
import org.onebusaway.android.extrapolation.data.VehicleHistoryEntry
import org.onebusaway.android.extrapolation.data.VehicleState

/**
 * Speed estimator using the H12 gamma distribution model.
 * Combines schedule speed with the most recent AVL-derived speed to produce
 * a gamma distribution over vehicle speed.
 */
class GammaSpeedEstimator : SpeedEstimator {

    private val scheduleEstimator = ScheduleSpeedEstimator()
    private var lastGammaParams: GammaSpeedModel.GammaParams? = null
    private var lastScheduleSpeed: Double = 0.0

    override fun estimateSpeed(
        vehicleId: String?,
        state: VehicleState,
        dataManager: TripDataManager
    ): Double? {
        lastGammaParams = null
        lastScheduleSpeed = 0.0

        val scheduleSpeed = scheduleEstimator.estimateSpeed(vehicleId, state, dataManager)
        val vSched = scheduleSpeed ?: 0.0
        lastScheduleSpeed = vSched

        val tripId = state.activeTripId
        if (isTripNotYetStarted(tripId, vSched, dataManager)) return null

        val vPrev = computePreviousAvlSpeed(tripId, dataManager)

        val params = GammaSpeedModel.fromSpeeds(vSched, vPrev)
        if (params != null) {
            lastGammaParams = params
            return GammaSpeedModel.medianSpeedMps(params)
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
    ): Double {
        if (tripId == null) return 0.0
        val history = dataManager.getHistoryReadOnly(tripId)
        if (history.size < 2) return 0.0

        var newer: VehicleHistoryEntry? = null
        var older: VehicleHistoryEntry? = null
        for (i in history.indices.reversed()) {
            val e = history[i]
            if (e.bestDistanceAlongTrip != null && e.lastLocationUpdateTime > 0) {
                if (newer == null) {
                    newer = e
                } else {
                    older = e
                    break
                }
            }
        }
        if (older == null || newer == null) return 0.0

        val dtMs = newer.lastLocationUpdateTime - older.lastLocationUpdateTime
        if (dtMs <= 0) return 0.0

        val dd = newer.bestDistanceAlongTrip!! - older.bestDistanceAlongTrip!!
        return maxOf(0.0, dd / (dtMs / 1000.0))
    }

    override fun getLastGammaParams(): GammaSpeedModel.GammaParams? = lastGammaParams

    override fun getLastScheduleSpeed(): Double = lastScheduleSpeed

    override fun clearState() {
        lastGammaParams = null
        lastScheduleSpeed = 0.0
    }
}
