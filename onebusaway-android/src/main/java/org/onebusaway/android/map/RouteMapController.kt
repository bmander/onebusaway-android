/*
 * Copyright (C) 2011-2014 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com), and individual contributors.
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
package org.onebusaway.android.map

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.request.ObaStopsForRouteResponse
import org.onebusaway.android.io.request.ObaTripsForRouteResponse
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.UIUtils
import java.util.concurrent.TimeUnit

/** How often the real-time vehicle positions are refreshed while a route is shown. */
internal val VEHICLE_REFRESH_PERIOD_MS = TimeUnit.SECONDS.toMillis(10)

/**
 * The delay before the next vehicle refresh when (re)starting the poll — e.g. on resume. Ported
 * from the legacy `onResume` timing math so resuming mid-period waits only the remainder. Pure and
 * unit-tested; [lastUpdated] and [now] are nanosecond timestamps (UIUtils.getCurrentTimeForComparison).
 *
 *  - never loaded ([lastUpdated] == 0) → a full period
 *  - already overdue → a near-immediate refresh (100 ms)
 *  - otherwise → the remaining time in the current period
 */
internal fun nextVehicleDelay(lastUpdated: Long, now: Long): Long {
    if (lastUpdated == 0L) {
        return VEHICLE_REFRESH_PERIOD_MS
    }
    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(now - lastUpdated)
    return if (elapsedMillis > VEHICLE_REFRESH_PERIOD_MS) {
        100L
    } else {
        VEHICLE_REFRESH_PERIOD_MS - elapsedMillis
    }
}

/**
 * Shows a route's stops + shape and its real-time vehicles on the map. The two `AsyncTaskLoader`s
 * (route shapes, polled vehicles) and the `Handler`-based 10s vehicle refresh are replaced by
 * coroutines on a controller-owned [scope]; the imperative route-mode header ([RoutePopup], which
 * mutates `R.id.route_info`) is carried over unchanged.
 */
class RouteMapController(callback: MapModeController.Callback) : MapModeController {

    private val mFragment: MapModeController.Callback = callback

    private var mRouteId: String? = null

    private var mZoomToRoute = false

    private var mZoomIncludeClosestVehicle = false

    private var mLineOverlayColor: Int =
        callback.activity.resources.getColor(R.color.route_line_color_default)

    private val mShortAnimationDuration: Int =
        callback.activity.resources.getInteger(android.R.integer.config_shortAnimTime)

    private val mRoutePopup = RoutePopup()

    private var mLastUpdatedTimeVehicles: Long = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val repository = DefaultRouteMapRepository(callback.activity)

    private var routeJob: Job? = null

    private var vehicleJob: Job? = null

    /** Reused single-route set passed to the vehicle overlay. */
    private val routes = HashSet<String>(1)

    override fun setState(args: Bundle?) {
        requireNotNull(args) { "args cannot be null" }
        val routeId = args.getString(MapParams.ROUTE_ID)

        // If the previous map zoom isn't the default, then zoom to that level as a start.
        val mapZoom = args.getFloat(MapParams.ZOOM, MapParams.DEFAULT_ZOOM.toFloat())
        if (mapZoom != MapParams.DEFAULT_ZOOM.toFloat()) {
            mFragment.mapView.setZoom(mapZoom)
        }

        mZoomToRoute = args.getBoolean(MapParams.ZOOM_TO_ROUTE, false)
        mZoomIncludeClosestVehicle = args.getBoolean(MapParams.ZOOM_INCLUDE_CLOSEST_VEHICLE, false)
        if (routeId != mRouteId) {
            if (mRouteId != null) {
                clearCurrentState()
            }

            // Set up the new route
            mRouteId = routeId
            mRoutePopup.showLoading()
            mFragment.showProgress(true)
            loadRoute()
            startVehiclePolling(0L)
        } else {
            // Returning to the route view with the route already set: just show the header.
            mRoutePopup.show()
        }
    }

    /** Clears the current state of the controller, so a new route can be loaded. */
    private fun clearCurrentState() {
        // Stop loads + the vehicle poll.
        routeJob?.cancel()
        vehicleJob?.cancel()

        // Clear the existing route and vehicle overlays.
        mFragment.mapView.removeRouteOverlay()
        mFragment.mapView.removeVehicleOverlay()

        // Clear the existing stop icons, but leave the currently focused stop.
        mFragment.mapView.removeStopOverlay(false)
    }

    override fun getMode(): String = MapParams.MODE_ROUTE

    override fun destroy() {
        mRoutePopup.hide()
        mFragment.mapView.removeRouteOverlay()
        scope.cancel()
        mFragment.mapView.removeVehicleOverlay()
    }

    override fun onPause() {
        // Stop the vehicle poll; the scope (and any in-flight route load) survives the pause.
        vehicleJob?.cancel()
    }

    /**
     * Called when the host hides/shows the map. Hide the route header when hidden, show it otherwise.
     */
    override fun onHidden(hidden: Boolean) {
        if (hidden) {
            mRoutePopup.hide()
        } else {
            mRoutePopup.show()
        }
    }

    override fun onResume() {
        if (vehicleJob?.isActive == true) {
            // Already polling (e.g. just started by setState) — don't restart and cancel an
            // in-flight load.
            return
        }
        startVehiclePolling(
            nextVehicleDelay(mLastUpdatedTimeVehicles, UIUtils.getCurrentTimeForComparison())
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(MapParams.ROUTE_ID, mRouteId)
        outState.putBoolean(MapParams.ZOOM_TO_ROUTE, mZoomToRoute)
        outState.putBoolean(MapParams.ZOOM_INCLUDE_CLOSEST_VEHICLE, mZoomIncludeClosestVehicle)

        val centerLocation = mFragment.mapView.mapCenterAsLocation
        outState.putDouble(MapParams.CENTER_LAT, centerLocation.latitude)
        outState.putDouble(MapParams.CENTER_LON, centerLocation.longitude)
        outState.putFloat(MapParams.ZOOM, mFragment.mapView.zoomLevelAsFloat)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            return
        }

        val stopId = savedInstanceState.getString(MapParams.STOP_ID)
        if (stopId == null) {
            // If there is no focused stop then restore the map state, otherwise let the map host
            // handle the map state with the focused stop.
            val mapZoom = savedInstanceState.getFloat(MapParams.ZOOM, MapParams.DEFAULT_ZOOM.toFloat())
            if (mapZoom != MapParams.DEFAULT_ZOOM.toFloat()) {
                mFragment.mapView.setZoom(mapZoom)
            }

            val lat = savedInstanceState.getDouble(MapParams.CENTER_LAT)
            val lon = savedInstanceState.getDouble(MapParams.CENTER_LON)
            if (lat != 0.0 && lon != 0.0) {
                val location = LocationUtils.makeLocation(lat, lon)
                mFragment.mapView.setMapCenter(location, false, false)
            }
        }
    }

    override fun onLocation() {
        // Don't care
    }

    override fun onNoLocation() {
        // Don't care
    }

    override fun notifyMapChanged() {
        // Don't care
    }

    //
    // Loads
    //

    private fun loadRoute() {
        val routeId = mRouteId ?: return
        routeJob?.cancel()
        routeJob = scope.launch {
            onRouteLoaded(repository.getRoute(routeId).getOrNull())
        }
    }

    private fun onRouteLoaded(response: ObaStopsForRouteResponse?) {
        val obaMapView = mFragment.mapView

        if (response == null || response.code != ObaApi.OBA_OK) {
            MapUtils.showMapError(response)
            return
        }

        val route = response.getRoute(response.routeId)

        mRoutePopup.show(route, response.getAgency(route.agencyId).name)

        route.color?.let { mLineOverlayColor = it }

        obaMapView.setRouteOverlay(mLineOverlayColor, response.shapes)

        // Set the stops for this route
        mFragment.showStops(response.stops, response)
        mFragment.showProgress(false)

        if (mZoomToRoute) {
            obaMapView.zoomToRoute()
            mZoomToRoute = false
        }
        obaMapView.postInvalidate()
    }

    /**
     * (Re)starts the real-time vehicle poll: after [initialDelayMs], reload vehicles every
     * [VEHICLE_REFRESH_PERIOD_MS]. The period is measured from each load's completion (so the
     * network time is excluded), matching the legacy `postDelayed`-after-`onLoadFinished` cadence.
     * The loop continues on a fixed cadence even if a load fails (the legacy stopped rescheduling on
     * error but relied on the `onResume` tick as a backstop).
     */
    private fun startVehiclePolling(initialDelayMs: Long) {
        vehicleJob?.cancel()
        vehicleJob = scope.launch {
            if (initialDelayMs > 0L) {
                delay(initialDelayMs)
            }
            while (isActive) {
                val routeId = mRouteId
                if (routeId != null) {
                    onVehiclesLoaded(routeId, repository.getVehicles(routeId).getOrNull())
                }
                delay(VEHICLE_REFRESH_PERIOD_MS)
            }
        }
    }

    private fun onVehiclesLoaded(routeId: String, response: ObaTripsForRouteResponse?) {
        val obaMapView = mFragment.mapView

        if (response == null || response.code != ObaApi.OBA_OK) {
            MapUtils.showMapError(response)
            return
        }

        routes.clear()
        routes.add(routeId)

        obaMapView.updateVehicles(routes, response)

        if (mZoomIncludeClosestVehicle) {
            obaMapView.zoomIncludeClosestVehicle(routes, response)
            mZoomIncludeClosestVehicle = false
        }

        mLastUpdatedTimeVehicles = UIUtils.getCurrentTimeForComparison()
    }

    //
    // Map popup (route-mode header) — stays imperative; mutates R.id.route_info directly.
    //
    private inner class RoutePopup {

        private val mActivity: Activity = mFragment.activity

        private val mView: View

        private val mRouteShortName: TextView

        private val mRouteLongName: TextView

        private val mAgencyName: TextView

        private val mProgressBar: ProgressBar

        /** Prevents completely hiding vehicle markers at the top of the route. */
        private val vehicleMarkerPadding: Int

        init {
            val paddingDp =
                mActivity.resources.getDimension(R.dimen.map_route_vehicle_markers_padding) /
                        mActivity.resources.displayMetrics.density
            vehicleMarkerPadding = UIUtils.dpToPixels(mActivity, paddingDp)
            mView = mActivity.findViewById(R.id.route_info)
            mFragment.mapView.setPadding(null, mView.height + vehicleMarkerPadding, null, null)
            mRouteShortName = mView.findViewById(R.id.short_name)
            mRouteLongName = mView.findViewById(R.id.long_name)
            mAgencyName = mView.findViewById(R.id.agency)
            mProgressBar = mView.findViewById(R.id.route_info_loading_spinner)

            // Make sure the cancel button is shown
            val cancel = mView.findViewById<View>(R.id.cancel_route_mode)
            cancel.visibility = View.VISIBLE
            cancel.setOnClickListener {
                val obaMapView = mFragment.mapView
                // We want to preserve the current zoom and center.
                val bundle = Bundle()
                bundle.putBoolean(MapParams.DO_N0T_CENTER_ON_LOCATION, true)
                bundle.putFloat(MapParams.ZOOM, obaMapView.zoomLevelAsFloat)
                val point = obaMapView.mapCenterAsLocation
                bundle.putDouble(MapParams.CENTER_LAT, point.latitude)
                bundle.putDouble(MapParams.CENTER_LON, point.longitude)
                mFragment.setMapMode(MapParams.MODE_STOP, bundle)
            }
        }

        fun showLoading() {
            mFragment.mapView.setPadding(null, mView.height + vehicleMarkerPadding, null, null)
            UIUtils.hideViewWithoutAnimation(mRouteShortName)
            UIUtils.hideViewWithoutAnimation(mRouteLongName)
            UIUtils.showViewWithoutAnimation(mView)
            UIUtils.showViewWithoutAnimation(mProgressBar)
        }

        /** Show the route header and populate it with the provided information. */
        fun show(route: ObaRoute, agencyName: String?) {
            mRouteShortName.text = UIUtils.formatDisplayText(UIUtils.getRouteDisplayName(route))
            mRouteLongName.text = UIUtils.formatDisplayText(UIUtils.getRouteDescription(route))
            mAgencyName.text = agencyName
            show()
        }

        /** Show the route header with the existing route information. */
        fun show() {
            UIUtils.hideViewWithAnimation(mProgressBar, mShortAnimationDuration)
            UIUtils.showViewWithAnimation(mRouteShortName, mShortAnimationDuration)
            UIUtils.showViewWithAnimation(mRouteLongName, mShortAnimationDuration)
            UIUtils.showViewWithAnimation(mView, mShortAnimationDuration)
            mFragment.mapView.setPadding(null, mView.height + vehicleMarkerPadding, null, null)
        }

        fun hide() {
            mFragment.mapView.setPadding(null, 0, null, null)
            UIUtils.hideViewWithAnimation(mView, mShortAnimationDuration)
        }
    }
}
