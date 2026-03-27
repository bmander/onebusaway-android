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
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import java.util.concurrent.TimeUnit
import org.onebusaway.android.R
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.prob.ProbDistribution
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.map.googlemapsv2.AnimationUtil
import org.onebusaway.android.map.googlemapsv2.MapHelpV2
import org.onebusaway.android.map.googlemapsv2.MapIconUtils
import org.onebusaway.android.map.googlemapsv2.StampedPolylineFactory
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.UIUtils

/** Shifts hue by 180 degrees to produce a color that contrasts with the input. */
private fun contrastingColor(color: Int): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(color or 0xFF000000.toInt(), hsv)
    hsv[0] = (hsv[0] + 180f) % 360f
    return Color.HSVToColor(hsv)
}

private const val STOP_STROKE_WIDTH = 4f
private const val STOP_STROKE_COLOR = 0xFF242424.toInt()
private const val ANIMATE_DURATION_MS = 600
private const val MARKER_Z_INDEX = 3f

/**
 * Owns all trip-specific map rendering: trip polyline, stop dots, estimate overlays,
 * vehicle marker, and data-received marker. Constructed with immutable trip data;
 * only vehicle position and overlays update per-frame.
 */
class TripMapRenderer internal constructor(
        private val map: GoogleMap,
        private val context: Context,
        val tripId: String,
        val shape: List<Location>,
        private val cumDist: DoubleArray,
        private val schedule: ObaTripSchedule,
        private val routeColor: Int,
        private val routeType: Int?,
        private val stopNames: Map<String, String>,
        private val selectedStopId: String?,
        val deviationColor: Int,
        val scheduleDeviation: Long
) {
    companion object {
        @JvmField val TRIP_BASE_WIDTH_PX = 44f
    }

    private val stampFactory = StampedPolylineFactory(
            context.resources, R.drawable.ic_navigation_expand_more, 4)

    private val overlayColor = contrastingColor(routeColor)

    // --- Map objects (mutable rendering state) ---

    private val tripPolylines = mutableListOf<Polyline>()
    private val tripStopMarkers = mutableListOf<Marker>()
    private val stopInfoMap = mutableMapOf<Marker, StopInfo>()
    private var estimateOverlay: DistanceEstimateOverlay? = null
    private var dataReceivedMarker: Marker? = null
    private var dataReceivedInfoShown = false
    private var lastDataReceivedUpdateTime = 0L
    private var vehicleMarker: Marker? = null
    private var lastFixTime = 0L
    private var animatingUntil = 0L

    // --- Lazy icon creation ---

    private val vehicleIcon by lazy {
        MapIconUtils.createCircleIcon(context, R.drawable.ic_vehicle_position)
    }
    private val dataReceivedIcon by lazy {
        MapIconUtils.createCircleIcon(context, R.drawable.ic_signal_indicator, 0xFF616161.toInt())
    }
    private val stopCircleIcon by lazy { makeStopCircleIcon() }
    private val bullseyeIcon by lazy { makeBullseyeIcon() }

    private data class StopInfo(val name: String, val arrivalTimeSec: Long)

    // --- Lifecycle ---

    fun activate(vehiclePosition: LatLng?) {
        showTripPolyline()
        showTripStopCircles()
        TripDataManager.getLastState(tripId)?.let {
            showOrUpdateDataReceivedMarker(it, System.currentTimeMillis())
        }
        createEstimateOverlays(vehiclePosition)
    }

    fun fitCameraToShape() {
        val bounds = MapHelpV2.getBounds(shape) ?: return
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        } catch (e: IllegalStateException) {
            Log.w("TripMapRenderer", "Map not laid out yet, skipping camera fit", e)
        }
    }

    fun deactivate() {
        tripPolylines.forEach { it.remove() }
        tripPolylines.clear()
        tripStopMarkers.forEach { it.remove() }
        tripStopMarkers.clear()
        stopInfoMap.clear()
        removeDataReceivedMarker()
        vehicleMarker?.remove()
        vehicleMarker = null
        estimateOverlay?.destroy()
        estimateOverlay = null
    }

    // --- Trip polyline ---

    private fun showTripPolyline() {
        tripPolylines.add(map.addPolyline(
                stampFactory.create(shape, routeColor, TRIP_BASE_WIDTH_PX)))
    }

    // --- Stop circles ---

    private fun showTripStopCircles() {
        val stopTimes = schedule.stopTimes ?: return
        for (st in stopTimes) {
            val loc = LocationUtils.interpolateAlongPolyline(
                    shape, cumDist, st.distanceAlongTrip) ?: continue
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
            drawCircleIcon { _, _ -> /* no inner dot */ }

    private fun makeBullseyeIcon(): BitmapDescriptor =
            drawCircleIcon { canvas, r ->
                canvas.drawCircle(r, r, r * 0.4f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STOP_STROKE_COLOR })
            }

    private fun drawCircleIcon(drawInner: (Canvas, Float) -> Unit): BitmapDescriptor {
        val size = TRIP_BASE_WIDTH_PX.toInt()
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

    // --- Vehicle marker ---

    fun hideVehicleMarker() {
        vehicleMarker?.isVisible = false
    }

    fun updateVehiclePosition(location: Location?, newestValid: ObaTripStatus?, now: Long) {
        if (location == null) return

        val marker = vehicleMarker
        if (marker == null) {
            vehicleMarker = map.addMarker(MarkerOptions()
                    .position(MapHelpV2.makeLatLng(location))
                    .icon(vehicleIcon)
                    .title(context.getString(R.string.marker_best_estimate))
                    .snippet(context.getString(R.string.marker_best_estimate_snippet))
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .zIndex(MARKER_Z_INDEX))
            return
        }

        marker.isVisible = true

        val target = MapHelpV2.makeLatLng(location)
        val fixTime = newestValid?.lastLocationUpdateTime ?: 0L
        val freshData = lastFixTime != 0L && fixTime != lastFixTime
        lastFixTime = fixTime

        when {
            freshData -> {
                AnimationUtil.animateMarkerTo(marker, target, ANIMATE_DURATION_MS)
                animatingUntil = now + ANIMATE_DURATION_MS
            }
            now >= animatingUntil -> marker.position = target
        }
    }

    // --- Estimate overlays ---

    fun updateEstimateOverlays(distribution: ProbDistribution?) {
        val overlay = estimateOverlay ?: return
        if (distribution == null) { overlay.hide(); return }
        overlay.update(distribution, shape, cumDist, overlayColor)
    }

    fun hideEstimateOverlays() { estimateOverlay?.hide() }

    fun handleEstimateLabelClick(marker: Marker) = estimateOverlay?.handleClick(marker) ?: false

    fun handleDataReceivedClick(marker: Marker): Boolean {
        if (marker != dataReceivedMarker) return false
        dataReceivedInfoShown = !dataReceivedInfoShown
        if (dataReceivedInfoShown) marker.showInfoWindow() else marker.hideInfoWindow()
        return true
    }

    private fun createEstimateOverlays(vehiclePosition: LatLng?) {
        if (routeType != null && ObaRoute.isGradeSeparated(routeType)) return
        if (vehiclePosition == null) return
        estimateOverlay = DistanceEstimateOverlay(map, context, TRIP_BASE_WIDTH_PX).also {
            it.create(vehiclePosition)
        }
    }

    // --- Data-received marker ---

    fun showOrUpdateDataReceivedMarker(latest: ObaTripStatus, now: Long) {
        val updateTime = latest.lastLocationUpdateTime
        val newData = updateTime != lastDataReceivedUpdateTime
        if (!newData && dataReceivedMarker != null) return
        lastDataReceivedUpdateTime = updateTime

        val label = if (updateTime > 0)
            UIUtils.formatElapsedTime(now - updateTime) else ""

        val pos = latest.position ?: latest.lastKnownLocation ?: return
        val latLng = MapHelpV2.makeLatLng(pos)

        val marker = dataReceivedMarker
        if (marker != null) {
            marker.position = latLng
            marker.snippet = label
            if (dataReceivedInfoShown) marker.showInfoWindow()
        } else {
            createDataReceivedMarker(latLng, label)
        }
    }

    private fun createDataReceivedMarker(latLng: LatLng, snippet: String) {
        dataReceivedMarker = map.addMarker(MarkerOptions()
                .position(latLng)
                .icon(dataReceivedIcon)
                .anchor(0.5f, 0.5f)
                .flat(true)
                .title(context.getString(R.string.marker_most_recent_data))
                .snippet(snippet)
                .zIndex(MARKER_Z_INDEX))
    }

    fun removeDataReceivedMarker() {
        dataReceivedMarker?.remove()
        dataReceivedMarker = null
        dataReceivedInfoShown = false
        lastDataReceivedUpdateTime = 0
    }
}
