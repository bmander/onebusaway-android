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

import org.onebusaway.android.extrapolation.ExtrapolationResult
import org.onebusaway.android.extrapolation.data.Trip
import org.onebusaway.android.map.googlemapsv2.ThrottledFrameLoop

/**
 * Owns the per-frame extrapolation loop for a single trip on the trip map view. Computes positions
 * and distributions each frame, then delegates all rendering to [TripVehicleOverlay].
 */
class TripExtrapolationController
internal constructor(
        private val vehicleOverlay: TripVehicleOverlay,
        private val trip: Trip
) {
    private val frameLoop = ThrottledFrameLoop(::doFrame)

    fun start() = frameLoop.start()

    fun stop() = frameLoop.stop()

    private fun doFrame() {
        try {
            val now = System.currentTimeMillis()
            val shapeData = trip.polyline ?: return
            val result = trip.extrapolate(now)

            when (result) {
                is ExtrapolationResult.Success -> {
                    val distribution = result.distribution
                    val loc = shapeData.interpolate(distribution.median())
                    if (loc != null) {
                        vehicleOverlay.updateVehiclePosition(loc, trip.anchor, now)
                    }
                    vehicleOverlay.updateEstimateOverlays(distribution)
                }
                else -> {
                    vehicleOverlay.hideVehicleMarker()
                    vehicleOverlay.hideEstimateOverlays()
                }
            }

            trip.history.lastOrNull()?.let {
                vehicleOverlay.showOrUpdateDataReceivedMarker(it, now)
            }
        } catch (_: Exception) {
        }
    }
}
