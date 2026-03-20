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

import org.onebusaway.android.map.googlemapsv2.AnimationUtil
import org.onebusaway.android.map.googlemapsv2.MapHelpV2
import org.onebusaway.android.map.googlemapsv2.MapIconUtils
import org.onebusaway.android.map.googlemapsv2.StampedPolylineFactory
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
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
import org.onebusaway.android.extrapolation.math.ProbDistribution
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.bestDistanceAlongTrip
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.UIUtils

private const val STOP_STROKE_WIDTH = 4f
private const val STOP_STROKE_COLOR = 0xFF242424.toInt()
private const val DATA_RECEIVED_Z_INDEX = 3f
private const val DATA_RECEIVED_TITLE = "Most recent data"
private const val ANIMATE_DURATION_MS = 600
private const val VEHICLE_MARKER_Z_INDEX = 3f

/**
 * Owns all trip-specific map rendering: trip polyline, stop dots, estimate overlays, vehicle
 * marker, and data-received marker. Activated when a vehicle is selected and deactivated when
 * deselected.
 */
class TripMapRenderer
internal constructor(
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
        var deviationColor: Int = 0,
        var scheduleDeviation: Long = 0
) {
    companion object {
        @JvmField val TRIP_BASE_WIDTH_PX = 44f
    }

    private val stampFactory =
            StampedPolylineFactory(context.resources, R.drawable.ic_navigation_expand_more, 4)

    private val tripPolylines = mutableListOf<Polyline>()
    private val tripStopMarkers = mutableListOf<Marker>()
    private val stopInfoMap = mutableMapOf<Marker, StopInfo>()

    private data class StopInfo(val name: String, val arrivalTimeSec: Long)

    private var estimateOverlay: SpeedEstimateOverlay? = null

    private var dataReceivedMarker: Marker? = null
    private var lastDataReceivedLabel: String? = null
    private var lastDataReceivedUpdateTime = 0L
    private var cachedCircleIcon: BitmapDescriptor? = null

    private var vehicleMarker: Marker? = null
    private var vehicleIcon: BitmapDescriptor? = null
    private var lastFixTime = 0L
    private var animatingUntil = 0L

    private var active = false

    // --- Lifecycle ---

    fun activate(vehiclePosition: LatLng?) {
        if (active) deactivate()
        active = true

        showTripPolyline(shape, routeColor)
        showTripStopCircles()
        val lastState = TripDataManager.getLastState(tripId)
        if (lastState != null) {
            showOrUpdateDataReceivedMarker(lastState)
        }
        createEstimateOverlays(vehiclePosition)
    }

    fun deactivate() {
        if (!active) return
        removeTripPolylines()
        removeTripStopCircles()
        removeDataReceivedMarker()
        removeVehicleMarker()
        destroyEstimateOverlays()
        active = false
    }

    // --- Trip polyline ---

    private fun showTripPolyline(shape: List<Location>, color: Int) {
        removeTripPolylines()
        tripPolylines.add(map.addPolyline(stampFactory.create(shape, color, TRIP_BASE_WIDTH_PX)))
    }

    private fun removeTripPolylines() {
        tripPolylines.forEach { it.remove() }
        tripPolylines.clear()
    }

    // --- Trip stop circles ---

    private fun showTripStopCircles() {
        val stopTimes = schedule.stopTimes ?: return

        val icon = makeStopCircleIcon()
        val selectedIcon = if (selectedStopId != null) makeBullseyeIcon() else null

        for (st in stopTimes) {
            val loc =
                    LocationUtils.interpolateAlongPolyline(shape, cumDist, st.distanceAlongTrip)
                            ?: continue
            val isSelected = st.stopId == selectedStopId
            val m =
                    map.addMarker(
                            MarkerOptions()
                                    .position(LatLng(loc.latitude, loc.longitude))
                                    .icon(if (isSelected) selectedIcon else icon)
                                    .anchor(0.5f, 0.5f)
                                    .flat(true)
                                    .zIndex(if (isSelected) 1.5f else 1f)
                    )
            if (m != null) {
                tripStopMarkers.add(m)
                val name = stopNames[st.stopId] ?: st.stopId
                stopInfoMap[m] = StopInfo(name, st.arrivalTime)
            }
        }
    }

    private fun makeStopCircleIcon(): BitmapDescriptor {
        val size = TRIP_BASE_WIDTH_PX.toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        c.drawCircle(
                r,
                r,
                r - STOP_STROKE_WIDTH / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        )
        c.drawCircle(
                r,
                r,
                r - STOP_STROKE_WIDTH / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = STOP_STROKE_WIDTH
                    color = STOP_STROKE_COLOR
                }
        )
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun makeBullseyeIcon(): BitmapDescriptor {
        val size = TRIP_BASE_WIDTH_PX.toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        c.drawCircle(
                r,
                r,
                r - STOP_STROKE_WIDTH / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        )
        c.drawCircle(
                r,
                r,
                r - STOP_STROKE_WIDTH / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = STOP_STROKE_WIDTH
                    color = STOP_STROKE_COLOR
                }
        )
        c.drawCircle(
                r,
                r,
                r * 0.4f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STOP_STROKE_COLOR }
        )
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun removeTripStopCircles() {
        tripStopMarkers.forEach { it.remove() }
        tripStopMarkers.clear()
        stopInfoMap.clear()
    }

    fun handleStopMarkerClick(marker: Marker): Boolean {
        val info = stopInfoMap[marker] ?: return false
        marker.title = info.name
        marker.snippet = computeEtaSnippet(info.arrivalTimeSec)
        marker.showInfoWindow()
        return true
    }

    private fun computeEtaSnippet(arrivalTimeSec: Long): String? {
        val serviceDate = TripDataManager.getServiceDate(tripId) ?: return null

        val predictedMs = serviceDate + arrivalTimeSec * 1000 + scheduleDeviation * 1000
        val now = System.currentTimeMillis()
        val diffMs = predictedMs - now
        val diffMin = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val clockTime = UIUtils.formatTime(context, predictedMs)

        return when {
            diffMs <= 0 -> "$clockTime (departed)"
            diffMin < 1 -> "$clockTime (< 1 min)"
            else -> "$clockTime ($diffMin min)"
        }
    }

    // --- Vehicle marker ---

    fun updateVehiclePosition(location: Location?, newestValid: ObaTripStatus?, now: Long) {
        if (location == null) return

        if (vehicleMarker == null) {
            if (vehicleIcon == null) {
                vehicleIcon = MapIconUtils.createCircleIcon(context, R.drawable.ic_vehicle_position)
            }
            vehicleMarker =
                    map.addMarker(
                            MarkerOptions()
                                    .position(MapHelpV2.makeLatLng(location))
                                    .icon(vehicleIcon)
                                    .title("Best estimate")
                                    .snippet("50th percentile")
                                    .anchor(0.5f, 0.5f)
                                    .flat(true)
                                    .zIndex(VEHICLE_MARKER_Z_INDEX)
                    )
            return
        }

        val target = MapHelpV2.makeLatLng(location)
        val fixTime = newestValid?.lastLocationUpdateTime ?: 0L
        val freshData = lastFixTime != 0L && fixTime != lastFixTime
        lastFixTime = fixTime

        if (freshData) {
            AnimationUtil.animateMarkerTo(vehicleMarker, target, ANIMATE_DURATION_MS)
            animatingUntil = now + ANIMATE_DURATION_MS
        } else if (now >= animatingUntil) {
            vehicleMarker?.position = target
        }
    }

    private fun removeVehicleMarker() {
        vehicleMarker?.remove()
        vehicleMarker = null
    }

    // --- Estimate overlays ---

    fun updateEstimateOverlays(
            distribution: ProbDistribution?,
            newestValid: ObaTripStatus?,
            now: Long
    ) {
        val overlay = estimateOverlay ?: return

        if (distribution == null || newestValid == null) {
            hideEstimateOverlays()
            return
        }

        val lastDist = newestValid.bestDistanceAlongTrip
        val lastTime = newestValid.lastLocationUpdateTime
        if (lastDist == null || lastTime <= 0) {
            hideEstimateOverlays()
            return
        }

        val dtSec = (now - lastTime) / 1000.0
        if (dtSec < 0.5) {
            hideEstimateOverlays()
            return
        }

        overlay.update(distribution, shape, cumDist, lastDist, dtSec, deviationColor)
    }

    fun hideEstimateOverlays() {
        estimateOverlay?.hide()
    }

    fun handleEstimateLabelClick(marker: Marker) = estimateOverlay?.handleClick(marker) ?: false

    fun handleDataReceivedClick(marker: Marker): Boolean {
        if (marker == dataReceivedMarker) {
            marker.showInfoWindow()
            return true
        }
        return false
    }

    private fun createEstimateOverlays(vehiclePosition: LatLng?) {
        if (routeType != null && ObaRoute.isGradeSeparated(routeType)) return
        if (vehiclePosition == null) return

        val overlay = SpeedEstimateOverlay(map, context, TRIP_BASE_WIDTH_PX)
        overlay.create(vehiclePosition)
        estimateOverlay = overlay
    }

    private fun destroyEstimateOverlays() {
        estimateOverlay?.destroy()
        estimateOverlay = null
    }

    // --- Data-received marker ---

    fun showOrUpdateDataReceivedMarker(latest: ObaTripStatus) {
        val updateTime = latest.lastLocationUpdateTime
        val newData = updateTime != lastDataReceivedUpdateTime

        refreshDataReceivedLabel(updateTime)

        if (!newData && dataReceivedMarker != null) return
        lastDataReceivedUpdateTime = updateTime

        val pos = latest.position ?: latest.lastKnownLocation ?: return
        val latLng = MapHelpV2.makeLatLng(pos)

        if (dataReceivedMarker != null) {
            dataReceivedMarker?.position = latLng
        } else {
            createDataReceivedMarker(latLng)
        }
    }

    private fun refreshDataReceivedLabel(updateTime: Long) {
        val label =
                if (updateTime > 0)
                        UIUtils.formatElapsedTime(System.currentTimeMillis() - updateTime)
                else ""
        val marker = dataReceivedMarker
        if (marker != null && label != lastDataReceivedLabel && !marker.isInfoWindowShown) {
            marker.snippet = label
        }
        lastDataReceivedLabel = label
    }

    private fun createDataReceivedMarker(latLng: LatLng) {
        if (cachedCircleIcon == null) {
            cachedCircleIcon =
                    MapIconUtils.createCircleIcon(
                            context,
                            R.drawable.ic_signal_indicator,
                            0xFF616161.toInt()
                    )
        }
        dataReceivedMarker =
                map.addMarker(
                        MarkerOptions()
                                .position(latLng)
                                .icon(cachedCircleIcon)
                                .anchor(0.5f, 0.5f)
                                .flat(true)
                                .title(DATA_RECEIVED_TITLE)
                                .snippet(lastDataReceivedLabel)
                                .zIndex(DATA_RECEIVED_Z_INDEX)
                )
    }

    fun removeDataReceivedMarker() {
        dataReceivedMarker?.remove()
        dataReceivedMarker = null
        lastDataReceivedLabel = null
        lastDataReceivedUpdateTime = 0
    }
}
