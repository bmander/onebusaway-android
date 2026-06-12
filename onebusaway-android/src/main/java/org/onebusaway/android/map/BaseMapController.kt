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

import android.location.Location
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.onebusaway.android.util.UIUtils

/**
 * Shared base for the stop + bikeshare map controllers. Owns the [scope] that replaces the old
 * `AsyncTaskLoader` machinery — data loads launch on it and [destroy] cancels it — and the
 * [MapWatcher] fallback that polls the map for pan/zoom when the map can't report changes itself.
 *
 * Subclasses kick off their first load from their own `init` block (after their fields are set),
 * and implement [updateData] to (re)load when the viewport changes.
 */
abstract class BaseMapController(
    protected val mCallback: MapModeController.Callback,
) : MapModeController, MapWatcher.Listener {

    private var mapWatcher: MapWatcher? = null

    /**
     * Scope for this controller's data loads. Main-dispatcher so the result continuations (which
     * touch the overlays/Views) run on the UI thread — replacing the old `Activity.runOnUiThread`.
     * [SupervisorJob] so one failed load doesn't tear the scope down; cancelled in [destroy], which
     * the host calls when it tears down the controller set on a map-mode switch.
     */
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Sets the initial state of where the map is focused, and its zoom level.
     */
    override fun setState(args: Bundle?) {
        val mapView = mCallback.mapView
        if (args != null) {
            val center = UIUtils.getMapCenter(args)

            // If the STOP_ID was set in the bundle, then we should focus on that stop
            val stopId = args.getString(MapParams.STOP_ID)
            if (stopId != null && center != null) {
                mapView.setZoom(MapParams.DEFAULT_ZOOM.toFloat())
                setMapCenter(center)
                return
            }

            // Try to set map based on real-time location, unless state says no
            if (!args.getBoolean(MapParams.DO_N0T_CENTER_ON_LOCATION)) {
                if (mCallback.setMyLocation(true, false)) {
                    return
                }
            }

            // If we have a previous map view, center map on that
            if (center != null) {
                mapView.setZoom(args.getFloat(MapParams.ZOOM, MapParams.DEFAULT_ZOOM.toFloat()))
                setMapCenter(center)
                return
            }
        } else {
            // We don't have any state info - just center on last known location
            if (mCallback.setMyLocation(false, false)) {
                return
            }
        }
        // If all else fails, just center on the region
        mCallback.zoomToRegion()
    }

    /** Sets the map center and loads stops for the new map view. */
    private fun setMapCenter(center: Location) {
        mCallback.mapView.setMapCenter(center, false, false)
        onLocation()
    }

    override fun destroy() {
        scope.cancel()
        watchMap(false)
    }

    override fun onPause() {
        watchMap(false)
    }

    override fun onResume() {
        watchMap(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {}

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        // Already handled in HomeActivity when the bus stop is selected on the map.
    }

    override fun onLocation() {
        refresh()
    }

    override fun onNoLocation() {}

    private fun refresh() {
        // The old refresh() posted updateData() to the UI thread; updateData() now launches on the
        // Main-dispatcher [scope], so we can call it directly.
        updateData()
    }

    /** (Re)loads this controller's data for the current viewport. */
    protected abstract fun updateData()

    //
    // Map watcher
    //
    private fun watchMap(watch: Boolean) {
        // Only instantiate our own map watcher if the mapView isn't capable of watching itself.
        if (watch && !mCallback.mapView.canWatchMapChanges()) {
            if (mapWatcher == null) {
                mapWatcher = MapWatcher(mCallback.mapView, this)
            }
            mapWatcher?.start()
        } else {
            mapWatcher?.stop()
            mapWatcher = null
        }
    }

    override fun onMapZoomChanging() {}

    override fun onMapZoomChanged() {
        refresh()
    }

    override fun onMapCenterChanging() {}

    override fun onMapCenterChanged() {
        refresh()
    }

    override fun notifyMapChanged() {
        refresh()
    }
}
