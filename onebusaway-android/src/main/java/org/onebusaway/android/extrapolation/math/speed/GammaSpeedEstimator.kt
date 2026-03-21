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
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.bestDistanceAlongTrip

/**
 * Speed estimator using a zero-inflated gamma distribution model. Combines schedule speed with
 * the most recent AVL-derived speed to produce a gamma distribution over vehicle speed. Falls
 * back to a point estimate from the schedule if the gamma model can't be computed.
 */
class GammaSpeedEstimator(private val dataManager: TripDataManager) : SpeedEstimator {

    companion object {
        /** Maximum time horizon (ms) for which gamma speed estimates are considered valid. */
        const val MAX_HORIZON_MS = 15 * 60 * 1000L // 15 minutes
    }

    private val scheduleEstimator = ScheduleSpeedEstimator(dataManager)

    /** Per-trip cached factory, keyed by tripId. Invalidated when lastFixTime changes. */
    private data class CachedFactory(val lastFixTime: Long, val factory: SpeedDistributionFactory)
    private val factoryCache = HashMap<String, CachedFactory>()

    override fun estimateSpeed(tripId: String, queryTime: Long): SpeedEstimateResult {
        val lastState = dataManager.getLastState(tripId)
        val schedule = dataManager.getSchedule(tripId)
        return estimateSpeedCore(tripId, queryTime, lastState, schedule)
    }

    override fun estimateSpeed(tripId: String, queryTime: Long,
                               snapshot: TripDataManager.TripSnapshot): SpeedEstimateResult =
            estimateSpeedCore(tripId, queryTime, snapshot.lastState, snapshot.schedule)

    private fun estimateSpeedCore(tripId: String, queryTime: Long,
                                   lastState: ObaTripStatus?,
                                   schedule: ObaTripSchedule?): SpeedEstimateResult {
        if (lastState == null) return SpeedEstimateResult.Failure(
                SpeedEstimateError.InsufficientData("No AVL fixes for trip"))

        val lastFixTime = lastState.lastLocationUpdateTime
        val dtSeconds = (queryTime - lastFixTime) / 1000.0
        if (dtSeconds < 0) return SpeedEstimateResult.Failure(
                SpeedEstimateError.TimestampOutOfBounds("Query time is before last AVL fix"))
        if (dtSeconds * 1000 > MAX_HORIZON_MS) return SpeedEstimateResult.Failure(
                SpeedEstimateError.TimestampOutOfBounds("Query time exceeds max horizon"))

        val cached = factoryCache[tripId]
        val factory = if (cached != null && cached.lastFixTime == lastFixTime) {
            cached.factory
        } else {
            val fixes = dataManager.mostRecentAvlFixes(tripId).take(2).toList()
            val prevSpeed = computeAvlSpeed(fixes)
            val scheduleSpeed =
                    when (val result = scheduleEstimator.estimateSpeedCore(lastState, schedule, queryTime)) {
                        is SpeedEstimateResult.Failure -> return result
                        is SpeedEstimateResult.Success -> result.distribution.mean
                    }
            makeGammaProbDistribution(scheduleSpeed, prevSpeed).also {
                factoryCache[tripId] = CachedFactory(lastFixTime, it)
            }
        }

        return SpeedEstimateResult.Success(factory.at(dtSeconds))
    }

    /** Clears the per-trip factory cache. */
    override fun clearCache() = factoryCache.clear()

    /** Computes speed from two AVL fixes (distance / time). Returns null if fewer than 2 fixes. */
    private fun computeAvlSpeed(fixes: List<ObaTripStatus>): Double? {
        if (fixes.size < 2) return null
        val (newer, older) = fixes
        val newerDist = newer.bestDistanceAlongTrip ?: return null
        val olderDist = older.bestDistanceAlongTrip ?: return null
        val dtMs = newer.lastLocationUpdateTime - older.lastLocationUpdateTime
        val dd = newerDist - olderDist
        return if (dtMs > 0) maxOf(0.0, dd / (dtMs / 1000.0)) else null
    }
}
