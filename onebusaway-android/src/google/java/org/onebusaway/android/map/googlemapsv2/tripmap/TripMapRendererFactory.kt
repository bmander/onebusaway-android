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
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import org.onebusaway.android.R
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.io.elements.ObaReferences
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.isLocationRealtime
import org.onebusaway.android.io.elements.isRealtimeSpeedEstimable
import org.onebusaway.android.io.request.ObaTripDetailsResponse
import org.onebusaway.android.map.googlemapsv2.MapHelpV2
import org.onebusaway.android.map.googlemapsv2.VehicleOverlay

private const val TAG = "TripMapRendererFactory"
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * Creates and activates a [TripMapRenderer] from an API response.
 *
 * Returns null if the response lacks required data (schedule, refs, trip).
 * If the shape is already cached, the renderer is ready immediately.
 * If the shape needs fetching, [TripDataManager.ensureShape] handles it
 * and [onShapeAvailable] is called once the shape arrives.
 */
internal object TripMapRendererFactory {

    fun create(
            map: GoogleMap,
            context: Context,
            tripId: String,
            selectedStopId: String?,
            response: ObaTripDetailsResponse,
            onReady: (TripMapRenderer) -> Unit
    ) {
        val schedule = response.schedule ?: return
        val status = response.status
        val refs = response.refs ?: return
        val trip = refs.getTrip(tripId) ?: return
        val route = refs.getRoute(trip.routeId)

        cacheResponseData(tripId, schedule, status)

        val routeColor = resolveRouteColor(context, route)
        val vehiclePosition = resolveVehiclePosition(tripId, status)
        val deviationColor = resolveDeviationColor(context, status)
        val scheduleDeviation = resolveScheduleDeviation(tripId, status)
        val stopNames = buildStopNameMap(schedule, refs)

        // Ensure shape is available (uses TripDataManager's thread pool + dedup)
        val shapeId = trip.shapeId
        if (shapeId != null) {
            TripDataManager.ensureShape(tripId, shapeId)
        }

        fun buildRenderer(sd: TripDataManager.ShapeData): TripMapRenderer {
            val renderer = TripMapRenderer(map, context, tripId,
                    sd.points, sd.cumulativeDistances, schedule,
                    routeColor, route?.type, stopNames, selectedStopId,
                    deviationColor, scheduleDeviation)
            renderer.activate(vehiclePosition)
            return renderer
        }

        // If shape is already cached, build and deliver synchronously
        val cached = TripDataManager.getShapeWithDistances(tripId)
        if (cached != null) {
            onReady(buildRenderer(cached))
            return
        }

        // Shape not yet available — poll until ensureShape delivers it
        if (shapeId != null) {
            pollForShape(tripId, ::buildRenderer, onReady)
        }
    }

    /**
     * Polls TripDataManager for shape data at short intervals until available.
     * This avoids duplicating the fetch logic that ensureShape already handles.
     */
    private fun pollForShape(
            tripId: String,
            buildRenderer: (TripDataManager.ShapeData) -> TripMapRenderer,
            onReady: (TripMapRenderer) -> Unit
    ) {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val sd = TripDataManager.getShapeWithDistances(tripId)
                if (sd != null) {
                    onReady(buildRenderer(sd))
                } else {
                    mainHandler.postDelayed(this, 200)
                }
            }
        }, 200)
    }

    // --- Response parsing ---

    private fun resolveRouteColor(context: Context, route: ObaRoute?): Int =
            route?.color ?: ContextCompat.getColor(context, R.color.route_line_color_default)

    private fun resolveVehiclePosition(tripId: String, status: ObaTripStatus?): LatLng? {
        if (status == null || tripId != status.activeTripId) return null
        val loc = status.lastKnownLocation ?: status.position ?: return null
        return MapHelpV2.makeLatLng(loc)
    }

    private fun resolveScheduleDeviation(tripId: String, status: ObaTripStatus?) =
            if (status != null && tripId == status.activeTripId) status.scheduleDeviation else 0L

    private fun resolveDeviationColor(context: Context, status: ObaTripStatus?): Int {
        if (status == null) return ContextCompat.getColor(context, R.color.stop_info_scheduled_time)
        val now = System.currentTimeMillis()
        val realtime = status.isLocationRealtime || status.isRealtimeSpeedEstimable(now)
        val colorRes = VehicleOverlay.getDeviationColorResource(realtime, status)
        return ContextCompat.getColor(context, colorRes)
    }

    private fun cacheResponseData(tripId: String, schedule: ObaTripSchedule, status: ObaTripStatus?) {
        TripDataManager.putSchedule(tripId, schedule)
        if (status != null && status.serviceDate > 0) {
            TripDataManager.putServiceDate(tripId, status.serviceDate)
        }
    }

    private fun buildStopNameMap(schedule: ObaTripSchedule, refs: ObaReferences): Map<String, String> =
            schedule.stopTimes
                    ?.mapNotNull { st -> refs.getStop(st.stopId)?.let { st.stopId to it.name } }
                    ?.toMap()
                    ?: emptyMap()
}
