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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline as MapPolyline
import java.util.concurrent.TimeUnit
import org.onebusaway.android.R
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.map.googlemapsv2.MapHelpV2
import org.onebusaway.android.map.googlemapsv2.StampedPolylineFactory
import org.onebusaway.android.util.Polyline
import org.onebusaway.android.util.UIUtils

private const val STOP_STROKE_WIDTH = 4f
private const val STOP_STROKE_COLOR = 0xFF242424.toInt()
private const val POLYLINE_WIDTH_PX = 44f

/**
 * Renders the static route skeleton on the trip map: the stamped route polyline
 * and stop circle markers. Created once per trip view; does not update per-frame.
 */
class TripRouteOverlay(
        private val map: GoogleMap,
        private val context: Context,
        private val tripId: String,
        private val shapeData: Polyline,
        private val schedule: ObaTripSchedule,
        private val routeColor: Int,
        private val stopNames: Map<String, String>,
        private val selectedStopId: String?,
        private val scheduleDeviation: Long
) {
    private val stampFactory = StampedPolylineFactory(
            context.resources, R.drawable.ic_navigation_expand_more, 4)

    private val tripPolylines = mutableListOf<MapPolyline>()
    private val tripStopMarkers = mutableListOf<Marker>()
    private val stopInfoMap = mutableMapOf<Marker, StopInfo>()

    private val stopCircleIcon by lazy { makeStopCircleIcon() }
    private val bullseyeIcon by lazy { makeBullseyeIcon() }

    private data class StopInfo(val name: String, val arrivalTimeSec: Long)

    fun activate() {
        tripPolylines.add(map.addPolyline(
                stampFactory.create(shapeData.points, routeColor, POLYLINE_WIDTH_PX)))
        val stopTimes = schedule.stopTimes ?: return
        for (st in stopTimes) {
            val loc = shapeData.interpolate(st.distanceAlongTrip) ?: continue
            val isSelected = st.stopId == selectedStopId
            map.addMarker(MarkerOptions()
                    .position(LatLng(loc.latitude, loc.longitude))
                    .icon(if (isSelected) bullseyeIcon else stopCircleIcon)
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .zIndex(if (isSelected) 1.5f else 1f)
            )?.let { marker ->
                tripStopMarkers.add(marker)
                stopInfoMap[marker] = StopInfo(
                        stopNames[st.stopId] ?: st.stopId, st.arrivalTime)
            }
        }
    }

    fun deactivate() {
        tripPolylines.forEach { it.remove() }
        tripPolylines.clear()
        tripStopMarkers.forEach { it.remove() }
        tripStopMarkers.clear()
        stopInfoMap.clear()
    }

    fun fitCameraToShape() {
        val bounds = MapHelpV2.getBounds(shapeData.points) ?: return
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        } catch (_: IllegalStateException) { }
    }

    fun handleStopMarkerClick(marker: Marker): Boolean {
        val info = stopInfoMap[marker] ?: return false
        marker.title = info.name
        marker.snippet = computeEtaSnippet(info.arrivalTimeSec, System.currentTimeMillis())
        marker.showInfoWindow()
        return true
    }

    private fun computeEtaSnippet(arrivalTimeSec: Long, now: Long): String? {
        val serviceDate = TripDataManager.getServiceDate(tripId) ?: return null
        val predictedMs = serviceDate + arrivalTimeSec * 1000 + scheduleDeviation * 1000
        val diffMs = predictedMs - now
        val diffMin = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val clockTime = UIUtils.formatTime(context, predictedMs)
        return when {
            diffMs <= 0 -> "$clockTime (departed)"
            diffMin < 1 -> "$clockTime (< 1 min)"
            else -> "$clockTime ($diffMin min)"
        }
    }

    // --- Stop circle icons ---

    private fun makeStopCircleIcon(): BitmapDescriptor =
            drawCircleIcon { _, _ -> }

    private fun makeBullseyeIcon(): BitmapDescriptor =
            drawCircleIcon { canvas, r ->
                canvas.drawCircle(r, r, r * 0.4f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STOP_STROKE_COLOR })
            }

    private fun drawCircleIcon(drawInner: (Canvas, Float) -> Unit): BitmapDescriptor {
        val size = POLYLINE_WIDTH_PX.toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size / 2f
        val fillRadius = r - STOP_STROKE_WIDTH / 2f
        canvas.drawCircle(r, r, fillRadius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(r, r, fillRadius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = STOP_STROKE_WIDTH
                    color = STOP_STROKE_COLOR
                })
        drawInner(canvas, r)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }
}
