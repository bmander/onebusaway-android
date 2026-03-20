/*
 * Copyright (C) 2024 Open Transit Software Foundation
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
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.speed.VehicleTrajectoryTracker
import org.onebusaway.android.io.elements.ObaReferences
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.isLocationRealtime
import org.onebusaway.android.io.elements.isRealtimeSpeedEstimable
import org.onebusaway.android.io.request.ObaShapeRequest
import org.onebusaway.android.io.request.ObaTripDetailsResponse

/**
 * Standalone map fragment for displaying a single trip's route, stops,
 * vehicle position, and speed estimate overlays within TripDetailsActivity.
 */
class TripMapFragment : SupportMapFragment(), OnMapReadyCallback, GoogleMap.OnMarkerClickListener {

    interface Callback {
        fun onShowList()
    }

    companion object {
        const val TAG = "TripMapFragment"
        private const val DEFAULT_INITIAL_ZOOM = 12f

        @JvmStatic
        fun newInstance(tripId: String): TripMapFragment {
            val options = GoogleMapOptions()
            val shape = TripDataManager.getShape(tripId)
            val bounds = MapHelpV2.getBounds(shape)
            if (bounds != null) {
                options.camera(CameraPosition(bounds.center, DEFAULT_INITIAL_ZOOM, 0f, 0f))
            }
            val args = Bundle()
            // "MapOptions" is the internal key SupportMapFragment uses to read
            // GoogleMapOptions from arguments (see SupportMapFragment.newInstance()).
            args.putParcelable("MapOptions", options)
            return TripMapFragment().apply { arguments = args }
        }
    }

    private var map: GoogleMap? = null
    private var tripRenderer: TripMapRenderer? = null
    private var vehicleMarker: Marker? = null
    private val reusableLocation = Location("extrapolated")

    private var extrapolationTicking = false
    private val frameCallback = Choreographer.FrameCallback { onExtrapolationFrame() }

    private var tripId: String? = null
    private var selectedStopId: String? = null
    /** Held only to replay activation if the map isn't ready yet. */
    private var deferredTripDetails: ObaTripDetailsResponse? = null
    private var deviationColor = 0

    private var callback: Callback? = null

    // --- Lifecycle ---

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? Callback
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        tripRenderer = TripMapRenderer(googleMap, requireContext())
        googleMap.setOnMarkerClickListener(this)
        googleMap.uiSettings.isZoomControlsEnabled = true
        MapHelpV2.applyMapStyle(googleMap, requireContext())

        val deferred = deferredTripDetails
        if (deferred != null) {
            deferredTripDetails = null
            doActivateTrip(deferred)
        }
    }

    override fun onResume() {
        super.onResume()
        startExtrapolationTicking()
    }

    override fun onPause() {
        stopExtrapolationTicking()
        super.onPause()
    }

    override fun onDestroyView() {
        tripRenderer?.deactivate()
        tripRenderer = null
        vehicleMarker?.remove()
        vehicleMarker = null
        map = null
        super.onDestroyView()
    }

    override fun onDetach() {
        super.onDetach()
        callback = null
    }

    // --- Menu ---

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.trip_details_map, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.show_list) {
            callback?.onShowList()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // --- Public API ---

    fun activateTrip(tripId: String, stopId: String?, tripDetails: ObaTripDetailsResponse) {
        this.tripId = tripId
        this.selectedStopId = stopId
        if (map != null && tripRenderer != null) {
            doActivateTrip(tripDetails)
        } else {
            deferredTripDetails = tripDetails
        }
    }

    // --- Internal activation ---

    private fun doActivateTrip(response: ObaTripDetailsResponse) {
        val tid = tripId ?: return

        val schedule = response.schedule ?: return
        val status = response.status
        val refs = response.refs ?: return

        val trip = refs.getTrip(tid) ?: return
        val route = refs.getRoute(trip.routeId)

        val routeColor = resolveRouteColor(route)
        val routeType = route?.type
        val vehiclePosition = resolveVehiclePosition(status)
        val scheduleDeviation = resolveScheduleDeviation(status)
        updateDeviationColor(status)
        cacheResponseData(tid, schedule, status)
        val stopNames = buildStopNameMap(schedule, refs)

        activateWithShape(trip.shapeId, schedule, routeColor, vehiclePosition,
                routeType, stopNames, scheduleDeviation)
    }

    private fun resolveRouteColor(route: ObaRoute?): Int {
        val defaultColor = ContextCompat.getColor(requireContext(), R.color.route_line_color_default)
        return if (route?.color != null) route.color else defaultColor
    }

    private fun resolveVehiclePosition(status: ObaTripStatus?): LatLng? {
        if (status == null || tripId != status.activeTripId) return null
        val loc = status.lastKnownLocation ?: status.position ?: return null
        return MapHelpV2.makeLatLng(loc)
    }

    private fun resolveScheduleDeviation(status: ObaTripStatus?): Long {
        if (status != null && tripId == status.activeTripId) {
            return status.scheduleDeviation
        }
        return 0
    }

    private fun updateDeviationColor(status: ObaTripStatus?) {
        if (status != null) {
            val now = System.currentTimeMillis()
            val realtime = status.isLocationRealtime || status.isRealtimeSpeedEstimable(now)
            val colorRes = VehicleOverlay.getDeviationColorResource(realtime, status)
            deviationColor = ContextCompat.getColor(requireContext(), colorRes)
        } else {
            deviationColor = ContextCompat.getColor(requireContext(),
                    R.color.stop_info_scheduled_time)
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

    private fun activateWithShape(shapeId: String?, schedule: ObaTripSchedule, routeColor: Int,
                                   vehiclePosition: LatLng?, routeType: Int?,
                                   stopNames: Map<String, String>, scheduleDeviation: Long) {
        val tid = tripId ?: return
        val cached = TripDataManager.getShapeWithDistances(tid)
        if (cached != null) {
            activateRenderer(cached.points, cached.cumulativeDistances, schedule, routeColor,
                    vehiclePosition, routeType, stopNames, scheduleDeviation)
            return
        }

        if (shapeId == null) return

        Thread {
            try {
                val ctx = Application.get().applicationContext
                val points = ObaShapeRequest.newRequest(ctx, shapeId).call()?.points
                if (points.isNullOrEmpty()) return@Thread
                TripDataManager.putShape(tid, points)
                val activity = activity ?: return@Thread
                activity.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    val sd = TripDataManager.getShapeWithDistances(tid) ?: return@runOnUiThread
                    activateRenderer(sd.points, sd.cumulativeDistances, schedule, routeColor,
                            vehiclePosition, routeType, stopNames, scheduleDeviation)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch shape for $tid", e)
            }
        }.start()
    }

    private fun activateRenderer(shape: List<Location>, cumDist: DoubleArray,
                                  schedule: ObaTripSchedule, routeColor: Int,
                                  vehiclePosition: LatLng?, routeType: Int?,
                                  stopNames: Map<String, String>, scheduleDeviation: Long) {
        val renderer = tripRenderer ?: return
        if (map == null) return

        renderer.activate(tripId, shape, cumDist, schedule, routeColor,
                vehiclePosition, routeType, stopNames, scheduleDeviation,
                selectedStopId)

        fitCameraToShape(shape)
        startExtrapolationTicking()
    }

    private fun fitCameraToShape(shape: List<Location>) {
        val m = map ?: return
        val bounds = MapHelpV2.getBounds(shape) ?: return
        try {
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fit camera to shape bounds", e)
        }
    }

    // --- Choreographer frame callback ---

    private fun startExtrapolationTicking() {
        if (!extrapolationTicking && map != null && tripId != null) {
            extrapolationTicking = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun stopExtrapolationTicking() {
        extrapolationTicking = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun onExtrapolationFrame() {
        if (!extrapolationTicking || tripId == null || map == null) {
            extrapolationTicking = false
            return
        }

        val now = System.currentTimeMillis()
        val tracker = VehicleTrajectoryTracker

        extrapolateVehicleMarker(tracker, now)
        updateOverlays(tracker, now)

        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun extrapolateVehicleMarker(tracker: VehicleTrajectoryTracker, now: Long) {
        val tid = tripId ?: return
        if (!tracker.extrapolatePosition(tid, now, reusableLocation)) return

        val m = map ?: return
        val marker = vehicleMarker
        if (marker == null) {
            vehicleMarker = m.addMarker(MarkerOptions()
                    .position(MapHelpV2.makeLatLng(reusableLocation))
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .zIndex(2f))
        } else {
            val pos = MapHelpV2.makeLatLng(reusableLocation)
            val cur = marker.position
            if (cur == null || cur.latitude != pos.latitude || cur.longitude != pos.longitude) {
                marker.position = pos
            }
        }
    }

    private fun updateOverlays(tracker: VehicleTrajectoryTracker, now: Long) {
        val renderer = tripRenderer ?: return
        val tid = tripId ?: return
        val sd = TripDataManager.getShapeWithDistances(tid) ?: return
        val history = TripDataManager.getHistory(tid)
        val distribution = tracker.getEstimatedDistribution(tid, now)
        renderer.updateEstimateOverlays(distribution, sd.points, sd.cumulativeDistances,
                history, now, deviationColor)
        renderer.showOrUpdateDataReceivedMarker(tid, sd.points, sd.cumulativeDistances,
                history)
    }

    // --- Marker click handling ---

    override fun onMarkerClick(marker: Marker): Boolean {
        val renderer = tripRenderer ?: return false
        if (renderer.handleDataReceivedClick(marker)) return true
        if (renderer.handleEstimateLabelClick(marker)) return true
        if (renderer.handleStopMarkerClick(marker)) return true
        return false
    }
}
