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
import android.os.Handler
import android.os.Looper
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
 * Creates and activates a [TripMapRenderer] from an API response. Handles all response parsing,
 * data caching, and shape fetching. The renderer is constructed with immutable trip data and
 * activated when the shape is available.
 *
 * [onReady] is always called asynchronously (posted to main thread) with the fully constructed and
 * activated renderer.
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

        val routeColor = resolveRouteColor(context, route)
        val routeType = route?.type
        val vehiclePosition = resolveVehiclePosition(tripId, status)
        val deviationColor = resolveDeviationColor(context, status)
        val scheduleDeviation = resolveScheduleDeviation(tripId, status)
        val stopNames = buildStopNameMap(schedule, refs)

        cacheResponseData(tripId, schedule, status)

        val shapeId = trip.shapeId

        fun buildAndActivate(sd: TripDataManager.ShapeData) {
            val renderer =
                    TripMapRenderer(
                            map,
                            context,
                            tripId,
                            sd.points,
                            sd.cumulativeDistances,
                            schedule,
                            routeColor,
                            routeType,
                            stopNames,
                            selectedStopId
                    )
            renderer.deviationColor = deviationColor
            renderer.scheduleDeviation = scheduleDeviation
            renderer.activate(vehiclePosition)
            onReady(renderer)
        }

        // Try cached shape first
        val cached = TripDataManager.getShapeWithDistances(tripId)
        if (cached != null) {
            Handler(Looper.getMainLooper()).post { buildAndActivate(cached) }
            return
        }

        // Fetch shape in background
        if (shapeId == null) return
        Thread {
                    try {
                        val ctx = Application.get().applicationContext
                        val points = ObaShapeRequest.newRequest(ctx, shapeId).call()?.points
                        if (points.isNullOrEmpty()) return@Thread
                        TripDataManager.putShape(tripId, points)
                        val sd = TripDataManager.getShapeWithDistances(tripId) ?: return@Thread
                        Handler(Looper.getMainLooper()).post { buildAndActivate(sd) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch shape for $tripId", e)
                    }
                }
                .start()
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

    private fun resolveScheduleDeviation(tripId: String, status: ObaTripStatus?) =
            if (status != null && tripId == status.activeTripId) status.scheduleDeviation else 0L

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

    private fun cacheResponseData(
            tripId: String,
            schedule: ObaTripSchedule,
            status: ObaTripStatus?
    ) {
        TripDataManager.putSchedule(tripId, schedule)
        if (status != null && status.serviceDate > 0) {
            TripDataManager.putServiceDate(tripId, status.serviceDate)
        }
    }

    private fun buildStopNameMap(
            schedule: ObaTripSchedule,
            refs: ObaReferences
    ): Map<String, String> {
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
}
