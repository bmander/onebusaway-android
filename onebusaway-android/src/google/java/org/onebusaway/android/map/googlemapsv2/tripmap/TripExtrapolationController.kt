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

import android.location.Location
import android.view.Choreographer
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.speed.VehicleTrajectoryTracker
import org.onebusaway.android.util.LocationUtils

private const val FRAME_INTERVAL_MS = 50L // 20fps

/**
 * Owns the per-frame extrapolation loop for a single trip on the trip map view. Computes positions
 * and distributions each frame, then delegates all rendering to [TripMapRenderer].
 */
class TripExtrapolationController
internal constructor(private val renderer: TripMapRenderer, private val tripId: String) {
    private val choreographer: Choreographer = Choreographer.getInstance()
    private val reusableLocation = Location("extrapolated")
    @Volatile private var ticking = false
    private var lastFrameTimeMs = 0L
    private val frameCallback = Choreographer.FrameCallback { onFrame() }

    fun start() {
        if (!ticking) {
            ticking = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    fun stop() {
        ticking = false
        choreographer.removeFrameCallback(frameCallback)
    }

    // --- Frame callback ---

    private fun onFrame() {
        if (!ticking) return

        val now = System.currentTimeMillis()
        if (now - lastFrameTimeMs >= FRAME_INTERVAL_MS) {
            lastFrameTimeMs = now
            doFrame(now)
        }

        if (ticking) choreographer.postFrameCallback(frameCallback)
    }

    private fun doFrame(now: Long) {
        val snapshot = TripDataManager.getSnapshot(tripId)
        val shapeData = snapshot.shapeData ?: return
        val distribution = VehicleTrajectoryTracker.extrapolate(tripId, now, snapshot)

        if (distribution != null) {
            if (LocationUtils.interpolateAlongPolyline(
                            shapeData.points, shapeData.cumulativeDistances,
                            distribution.median(), reusableLocation)) {
                renderer.updateVehiclePosition(reusableLocation, snapshot.newestValid, now)
            }
            renderer.updateEstimateOverlays(distribution)
        } else {
            renderer.hideEstimateOverlays()
        }

        snapshot.lastState?.let { renderer.showOrUpdateDataReceivedMarker(it, now) }
    }
}
