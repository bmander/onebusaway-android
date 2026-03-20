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
package org.onebusaway.android.map.googlemapsv2

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.io.elements.ObaReferences
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.isLocationRealtime
import org.onebusaway.android.io.elements.isRealtimeSpeedEstimable
import org.onebusaway.android.io.request.ObaShapeRequest
import org.onebusaway.android.io.request.ObaTripDetailsResponse

private const val TAG = "TripMapRendererFactory"

/**
 * Creates and activates a [TripMapRenderer] from an API response. Handles all
 * response parsing, data caching, shape fetching, and renderer configuration.
 * Returns the renderer immediately; if the shape needs fetching, the renderer
 * is activated asynchronously when the shape arrives.
 */
internal object TripMapRendererFactory {

    /**
     * Creates a renderer, parses the response, caches data, and activates.
     * [onActivated] is called (on the main thread) once the renderer is fully active.
     */
    fun create(
            map: GoogleMap,
            context: Context,
            tripId: String,
            selectedStopId: String?,
            response: ObaTripDetailsResponse,
            onActivated: (TripMapRenderer) -> Unit
    ): TripMapRenderer {
        val renderer = TripMapRenderer(map, context)

        val schedule = response.schedule ?: return renderer
        val status = response.status
        val refs = response.refs ?: return renderer

        val trip = refs.getTrip(tripId) ?: return renderer
        val route = refs.getRoute(trip.routeId)

        val routeColor = resolveRouteColor(context, route)
        val routeType = route?.type
        val vehiclePosition = resolveVehiclePosition(tripId, status)
        val scheduleDeviation = resolveScheduleDeviation(tripId, status)
        renderer.deviationColor = resolveDeviationColor(context, status)

        cacheResponseData(tripId, schedule, status)
        val stopNames = buildStopNameMap(schedule, refs)

        activateWithShape(renderer, tripId, trip.shapeId, schedule, routeColor,
                vehiclePosition, routeType, stopNames, scheduleDeviation,
                selectedStopId, onActivated)

        return renderer
    }

    // --- Response parsing helpers ---

    private fun resolveRouteColor(context: Context, route: ObaRoute?): Int {
        val defaultColor = ContextCompat.getColor(context, R.color.route_line_color_default)
        return if (route?.color != null) route.color else defaultColor
    }

    private fun resolveVehiclePosition(tripId: String, status: ObaTripStatus?): LatLng? {
        if (status == null || tripId != status.activeTripId) return null
        val loc = status.lastKnownLocation ?: status.position ?: return null
        return MapHelpV2.makeLatLng(loc)
    }

    private fun resolveScheduleDeviation(tripId: String, status: ObaTripStatus?): Long =
            if (status != null && tripId == status.activeTripId) status.scheduleDeviation else 0

    private fun resolveDeviationColor(context: Context, status: ObaTripStatus?): Int {
        return if (status != null) {
            val now = System.currentTimeMillis()
            val realtime = status.isLocationRealtime || status.isRealtimeSpeedEstimable(now)
            val colorRes = VehicleOverlay.getDeviationColorResource(realtime, status)
            ContextCompat.getColor(context, colorRes)
        } else {
            ContextCompat.getColor(context, R.color.stop_info_scheduled_time)
        }
    }

    private fun cacheResponseData(tripId: String, schedule: ObaTripSchedule, status: ObaTripStatus?) {
        TripDataManager.putSchedule(tripId, schedule)
        if (status != null && status.serviceDate > 0) {
            TripDataManager.putServiceDate(tripId, status.serviceDate)
        }
    }

    private fun buildStopNameMap(schedule: ObaTripSchedule, refs: ObaReferences): Map<String, String> {
        val stopNames = mutableMapOf<String, String>()
        val stopTimes = schedule.stopTimes ?: return stopNames
        for (st in stopTimes) {
            val stop = refs.getStop(st.stopId)
            if (stop != null) {
                stopNames[st.stopId] = stop.name
            }
        }
        return stopNames
    }

    // --- Shape fetching and activation ---

    private fun activateWithShape(
            renderer: TripMapRenderer, tripId: String, shapeId: String?,
            schedule: ObaTripSchedule, routeColor: Int, vehiclePosition: LatLng?,
            routeType: Int?, stopNames: Map<String, String>, scheduleDeviation: Long,
            selectedStopId: String?, onActivated: (TripMapRenderer) -> Unit
    ) {
        val cached = TripDataManager.getShapeWithDistances(tripId)
        if (cached != null) {
            renderer.activate(tripId, cached.points, cached.cumulativeDistances, schedule,
                    routeColor, vehiclePosition, routeType, stopNames, scheduleDeviation,
                    selectedStopId)
            // Post instead of calling synchronously so the caller can finish
            // initializing (e.g., creating the extrapolation controller) before
            // the callback fires.
            android.os.Handler(android.os.Looper.getMainLooper()).post { onActivated(renderer) }
            return
        }

        if (shapeId == null) return

        Thread {
            try {
                val ctx = Application.get().applicationContext
                val points = ObaShapeRequest.newRequest(ctx, shapeId).call()?.points
                if (points.isNullOrEmpty()) return@Thread
                TripDataManager.putShape(tripId, points)
                val sd = TripDataManager.getShapeWithDistances(tripId) ?: return@Thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    renderer.activate(tripId, sd.points, sd.cumulativeDistances, schedule,
                            routeColor, vehiclePosition, routeType, stopNames,
                            scheduleDeviation, selectedStopId)
                    onActivated(renderer)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch shape for $tripId", e)
            }
        }.start()
    }
}
