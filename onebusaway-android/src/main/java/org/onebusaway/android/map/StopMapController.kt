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
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.io.request.ObaStopsForLocationResponse
import org.onebusaway.android.util.RegionUtils

/** A snapshot of the map viewport used to request stops: center + lat/lon span + zoom. */
internal class StopsRequest(view: MapModeController.ObaMapView) {
    val center: Location? = view.mapCenterAsLocation
    val latSpan: Double = view.latitudeSpanInDecDegrees
    val lonSpan: Double = view.longitudeSpanInDecDegrees
    val zoomLevel: Double = view.zoomLevelAsFloat.toDouble()
}

/**
 * Pairs a [StopsRequest] with the response it produced, so [fulfills] can decide whether a new
 * viewport is already satisfied by the last load (avoiding a redundant network call on pan/zoom).
 */
internal class StopsResponse(
    val request: StopsRequest,
    val response: ObaStopsForLocationResponse?,
) {
    /** Returns true if [newReq] is already satisfied by this response. */
    fun fulfills(newReq: StopsRequest): Boolean {
        val center = request.center ?: return false
        if (center != newReq.center) {
            return false
        }
        return zoomFulfills(
            hasResponse = response != null,
            lastLimitExceeded = response?.limitExceeded ?: false,
            lastZoom = request.zoomLevel,
            newZoom = newReq.zoomLevel,
        )
    }
}

/**
 * Loads and displays the stops in the current map viewport. The old nested `StopsLoader`
 * `AsyncTaskLoader` is replaced by a coroutine on the base [scope]; the redundant-load avoidance
 * ([StopsResponse.fulfills]) and the out-of-range handling are preserved verbatim.
 */
class StopMapController(callback: MapModeController.Callback) : BaseMapController(callback) {

    private val repository: StopsRepository = DefaultStopsRepository(mCallback.activity)

    private var lastResponse: StopsResponse? = null

    private var loadJob: Job? = null

    init {
        updateData()
    }

    override fun getMode(): String = MapParams.MODE_STOP

    override fun onHidden(hidden: Boolean) {
        // No op for this controller
    }

    override fun updateData() {
        val request = StopsRequest(mCallback.mapView)
        // Reuse the last response if it still satisfies this viewport (same center, compatible zoom).
        if (lastResponse?.fulfills(request) == true) {
            return
        }
        val center = request.center ?: return
        // A newer pan/zoom supersedes any in-flight load.
        loadJob?.cancel()
        loadJob = scope.launch {
            mCallback.showProgress(true)
            val response = repository.getStops(center, request.latSpan, request.lonSpan).getOrNull()
            mCallback.showProgress(false)
            lastResponse = StopsResponse(request, response)
            onLoadFinished(response)
        }
    }

    private fun onLoadFinished(response: ObaStopsForLocationResponse?) {
        if (response == null) {
            // Initial install can generate a null response if all is still ok, so do nothing (#615)
            return
        }

        if (response.code != ObaApi.OBA_OK) {
            MapUtils.showMapError(response)
            return
        }

        if (response.outOfRange) {
            mCallback.notifyOutOfRange()
            return
        }

        // Workaround for https://github.com/OneBusAway/onebusaway-application-modules/issues/59
        // where the outOfRange element is false even if the location was out of range. We need to
        // also make sure the list of stops is empty, otherwise we screen out valid responses.
        val myLocation = Application.getLastKnownLocation(mCallback.activity)
        val region = Application.get().currentRegion
        if (myLocation != null && region != null) {
            var inRegion = true // Assume user is in region unless we detect otherwise
            try {
                inRegion = RegionUtils.isLocationWithinRegion(myLocation, region)
            } catch (e: IllegalArgumentException) {
                // Issue #69 - some devices are providing invalid lat/long coordinates
                Log.e(
                    TAG, "Invalid latitude or longitude - lat = " + myLocation.latitude +
                            ", long = " + myLocation.longitude
                )
            }

            if (!inRegion && response.stops.isEmpty()) {
                Log.d(TAG, "Device location is outside region range, notifying...")
                mCallback.notifyOutOfRange()
                return
            }
        }

        mCallback.showStops(response.stops.toList(), response)
    }

    companion object {
        private const val TAG = "StopMapController"
    }
}
