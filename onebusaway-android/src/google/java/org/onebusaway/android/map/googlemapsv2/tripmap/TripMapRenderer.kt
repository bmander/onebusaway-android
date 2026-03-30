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

import android.content.Context
import android.location.Location
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.prob.ProbDistribution
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.util.Polyline

/**
 * Facade that coordinates the two trip map layers: [TripRouteOverlay] (static route
 * skeleton) and [TripVehicleOverlay] (live vehicle data). Provides a unified API
 * for [TripMapFragment] and [TripExtrapolationController].
 */
class TripMapRenderer internal constructor(
        map: GoogleMap,
        context: Context,
        val tripId: String,
        val shapeData: Polyline,
        schedule: ObaTripSchedule,
        routeColor: Int,
        routeType: Int?,
        stopNames: Map<String, String>,
        selectedStopId: String?,
        val scheduleDeviation: Long
) {
    companion object {
        @JvmField val TRIP_BASE_WIDTH_PX = 44f
    }

    private val routeOverlay = TripRouteOverlay(
            map, context, tripId, shapeData, schedule,
            routeColor, stopNames, selectedStopId, scheduleDeviation)

    private val vehicleOverlay = TripVehicleOverlay(
            map, context, shapeData, routeColor, routeType)

    // --- Lifecycle ---

    fun activate(vehiclePosition: LatLng?) {
        routeOverlay.activate()
        TripDataManager.getLastState(tripId)?.let {
            vehicleOverlay.showOrUpdateDataReceivedMarker(it, System.currentTimeMillis())
        }
        vehicleOverlay.activate(vehiclePosition)
    }

    fun deactivate() {
        routeOverlay.deactivate()
        vehicleOverlay.deactivate()
    }

    fun fitCameraToShape() = routeOverlay.fitCameraToShape()

    // --- Per-frame vehicle updates (called by TripExtrapolationController) ---

    fun updateVehiclePosition(location: Location?, newestValid: ObaTripStatus?, now: Long) =
            vehicleOverlay.updateVehiclePosition(location, newestValid, now)

    fun hideVehicleMarker() = vehicleOverlay.hideVehicleMarker()

    fun updateEstimateOverlays(distribution: ProbDistribution?) =
            vehicleOverlay.updateEstimateOverlays(distribution)

    fun hideEstimateOverlays() = vehicleOverlay.hideEstimateOverlays()

    fun showOrUpdateDataReceivedMarker(latest: ObaTripStatus, now: Long) =
            vehicleOverlay.showOrUpdateDataReceivedMarker(latest, now)

    // --- Click handling (called by TripMapFragment) ---

    fun handleStopMarkerClick(marker: Marker) = routeOverlay.handleStopMarkerClick(marker)
    fun handleEstimateLabelClick(marker: Marker) = vehicleOverlay.handleEstimateLabelClick(marker)
    fun handleDataReceivedClick(marker: Marker) = vehicleOverlay.handleDataReceivedClick(marker)
}
