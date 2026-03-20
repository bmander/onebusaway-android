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
package org.onebusaway.android.map.googlemapsv2.tripmap

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Marker
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.data.TripDetailsPoller
import org.onebusaway.android.map.googlemapsv2.MapHelpV2

/**
 * Standalone map fragment for displaying a single trip's route, stops,
 * vehicle position, and speed estimate overlays within TripDetailsActivity.
 *
 * Self-contained: reads trip data from [TripDataManager] using the tripId
 * passed as a fragment argument. Delegates rendering to [TripMapRenderer],
 * per-frame extrapolation to [TripExtrapolationController], and API polling
 * to [TripDetailsPoller].
 */
class TripMapFragment : SupportMapFragment(), OnMapReadyCallback, GoogleMap.OnMarkerClickListener {

    companion object {
        const val TAG = "TripMapFragment"
        private const val ARG_TRIP_ID = "tripId"
        private const val ARG_STOP_ID = "stopId"
        private const val DEFAULT_INITIAL_ZOOM = 12f

        @JvmStatic
        fun newInstance(tripId: String, stopId: String? = null): TripMapFragment {
            val options = GoogleMapOptions()
            val shape = TripDataManager.getShape(tripId)
            val bounds = MapHelpV2.getBounds(shape)
            if (bounds != null) {
                options.camera(CameraPosition(bounds.center, DEFAULT_INITIAL_ZOOM, 0f, 0f))
            }
            val args = Bundle()
            args.putParcelable("MapOptions", options)
            args.putString(ARG_TRIP_ID, tripId)
            if (stopId != null) args.putString(ARG_STOP_ID, stopId)
            return TripMapFragment().apply { arguments = args }
        }
    }

    private val tripId: String get() = requireArguments().getString(ARG_TRIP_ID)!!
    private val selectedStopId: String? get() = arguments?.getString(ARG_STOP_ID)

    private var map: GoogleMap? = null
    private var tripRenderer: TripMapRenderer? = null
    private var extrapolationController: TripExtrapolationController? = null
    private val poller = TripDetailsPoller()

    // --- Lifecycle ---

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        getMapAsync(this)
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.setOnMarkerClickListener(this)
        googleMap.uiSettings.isZoomControlsEnabled = true
        MapHelpV2.applyMapStyle(googleMap, requireContext())
        if (hasLocationPermission()) {
            googleMap.isMyLocationEnabled = true
        }

        activate()
    }

    override fun onResume() {
        super.onResume()
        extrapolationController?.start()
        poller.start(tripId)
    }

    override fun onPause() {
        extrapolationController?.stop()
        poller.stop()
        super.onPause()
    }

    override fun onDestroyView() {
        poller.stop()
        extrapolationController?.stop()
        extrapolationController = null
        tripRenderer?.deactivate()
        tripRenderer = null
        map = null
        super.onDestroyView()
    }

    // --- Activation ---

    private fun activate() {
        val m = map ?: return
        val response = TripDataManager.getTripDetails(tripId)
        if (response == null) {
            Log.w(TAG, "No cached trip details for $tripId")
            return
        }

        tripRenderer?.deactivate()
        extrapolationController?.stop()

        poller.start(tripId)

        TripMapRendererFactory.create(m, requireContext(), tripId,
                selectedStopId, response) { renderer ->
            tripRenderer = renderer
            extrapolationController = TripExtrapolationController(renderer, tripId)
            fitCameraToShape()
            extrapolationController?.start()
        }
    }

    private fun fitCameraToShape() {
        val renderer = tripRenderer ?: return
        val m = map ?: return
        val sd = TripDataManager.getShapeWithDistances(renderer.tripId) ?: return
        val bounds = MapHelpV2.getBounds(sd.points) ?: return
        try {
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fit camera to shape bounds", e)
        }
    }

    // --- Marker click handling ---

    override fun onMarkerClick(marker: Marker): Boolean {
        val renderer = tripRenderer ?: return false
        if (renderer.handleDataReceivedClick(marker)) return true
        if (renderer.handleEstimateLabelClick(marker)) return true
        if (renderer.handleStopMarkerClick(marker)) return true
        return false
    }

    private fun hasLocationPermission(): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
