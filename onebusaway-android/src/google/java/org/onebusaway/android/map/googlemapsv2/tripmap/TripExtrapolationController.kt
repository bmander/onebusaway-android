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
package org.onebusaway.android.map.googlemapsv2.tripmap

import android.util.Log
import org.onebusaway.android.extrapolation.ExtrapolationResult
import org.onebusaway.android.extrapolation.Extrapolator
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.map.googlemapsv2.ThrottledFrameLoop

private const val TAG = "TripExtrapController"

/**
 * Owns the per-frame extrapolation loop for a single trip on the trip map view. Computes positions
 * and distributions each frame, then delegates all rendering to [TripMapRenderer].
 */
class TripExtrapolationController
internal constructor(
        private val vehicleOverlay: TripVehicleOverlay,
        private val tripId: String,
        private val extrapolator: Extrapolator
) {
    private val frameLoop = ThrottledFrameLoop(::doFrame)

    fun start() = frameLoop.start()

    fun stop() = frameLoop.stop()

    private fun doFrame() {
        try {
            val now = System.currentTimeMillis()
            val shapeData = TripDataManager.getPolyline(tripId) ?: return
            val result = extrapolator.extrapolate(now)

            when (result) {
                is ExtrapolationResult.Success -> {
                    val distribution = result.distribution
                    val loc = shapeData.interpolate(distribution.median())
                    if (loc != null) {
                        vehicleOverlay.updateVehiclePosition(loc, extrapolator.lastUsedEntry, now)
                    }
                    vehicleOverlay.updateEstimateOverlays(distribution)
                }
                else -> {
                    vehicleOverlay.hideVehicleMarker()
                    vehicleOverlay.hideEstimateOverlays()
                }
            }

            TripDataManager.getLastState(tripId)?.let {
                vehicleOverlay.showOrUpdateDataReceivedMarker(it, now)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extrapolation frame failed for trip $tripId", e)
        }
    }
}
