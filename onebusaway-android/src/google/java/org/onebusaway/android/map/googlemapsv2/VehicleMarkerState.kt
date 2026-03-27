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
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import org.onebusaway.android.R
import org.onebusaway.android.extrapolation.Extrapolator
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.util.UIUtils

/**
 * Consolidated per-vehicle state for a marker on the main map view.
 * One instance per tracked trip, referenced from both tripId and Marker lookups.
 */
class VehicleMarkerState(
        val tripId: String,
        val marker: Marker,
        var status: ObaTripStatus
) {
    var extrapolator: Extrapolator? = null
    var isExtrapolating: Boolean = false
    var lastFixTimeMs: Long = 0
    @JvmField var animating: Boolean = false

    // --- Data-received marker (per-vehicle, shown when selected + extrapolating) ---

    var dataReceivedMarker: Marker? = null
        private set
    private var dataReceivedFixTime: Long = 0

    /** True when the user has tapped this vehicle and the info window is open. */
    @JvmField var selected: Boolean = false

    fun showDataReceivedMarker(map: GoogleMap, icon: BitmapDescriptor, context: Context) {
        removeDataReceivedMarker()
        val loc = status.position ?: return
        if (!status.isPredicted || status.lastLocationUpdateTime <= 0) return
        val elapsed = System.currentTimeMillis() - status.lastLocationUpdateTime
        dataReceivedMarker = map.addMarker(MarkerOptions()
                .position(MapHelpV2.makeLatLng(loc))
                .icon(icon)
                .title(context.getString(R.string.marker_most_recent_data))
                .snippet(UIUtils.formatElapsedTime(elapsed))
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(3.1f))
        dataReceivedFixTime = status.lastLocationUpdateTime
    }

    fun updateDataReceivedMarker(newestValid: ObaTripStatus?, now: Long, animDurationMs: Int) {
        val drm = dataReceivedMarker ?: return
        if (newestValid == null) return
        val fixTime = newestValid.lastLocationUpdateTime
        if (fixTime == dataReceivedFixTime) return
        dataReceivedFixTime = fixTime
        val loc = newestValid.position ?: return
        AnimationUtil.animateMarkerTo(drm, MapHelpV2.makeLatLng(loc), animDurationMs)
        drm.snippet = UIUtils.formatElapsedTime(now - fixTime)
    }

    fun removeDataReceivedMarker() {
        dataReceivedMarker?.remove()
        dataReceivedMarker = null
        dataReceivedFixTime = 0
    }

    fun destroy() {
        marker.remove()
        removeDataReceivedMarker()
    }
}
