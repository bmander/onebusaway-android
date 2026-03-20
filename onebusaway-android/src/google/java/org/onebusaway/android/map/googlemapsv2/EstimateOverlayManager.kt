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
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import org.onebusaway.android.R
import org.onebusaway.android.extrapolation.math.ProbDistribution
import org.onebusaway.android.util.LocationUtils

/**
 * Manages all estimate-related map overlays for a selected vehicle:
 * the fast estimate icon marker and the PDF opacity segments.
 * Recomputes quantile speeds every frame since the zero-inflation
 * probability decays continuously with time since last AVL update.
 */
class EstimateOverlayManager @JvmOverloads constructor(
        private val map: GoogleMap,
        private val context: Context,
        private val labelHighQuantile: Double = 0.90,
        private val pdfLowQuantile: Double = 0.01,
        private val pdfHighQuantile: Double = 0.99,
        private val segmentCount: Int = 15
) {
    companion object {
        private const val MARKER_Z_INDEX = 3f
    }

    private val pdfOverlay = PdfOverlayRenderer(map, segmentCount, TripMapRenderer.TRIP_BASE_WIDTH_PX)
    private val pdfEdgeSpeedsMps = DoubleArray(segmentCount + 1)
    private val pdfMidPdfValues = DoubleArray(segmentCount)
    private var labelSpeedHighMps = 0.0

    private var fastEstimateMarker: Marker? = null
    private var fastEstimateIcon = MapIconUtils.createCircleIcon(context, R.drawable.ic_fast_estimate)
    private val reusableLoc = Location("label")

    /** Creates all overlays at the given initial position. */
    fun create(initialPosition: LatLng) {
        fastEstimateMarker = map.addMarker(MarkerOptions()
                .position(initialPosition)
                .icon(fastEstimateIcon)
                .title("Fast estimate")
                .snippet("90th percentile speed")
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(MARKER_Z_INDEX)
                .visible(false))
        pdfOverlay.create()
    }

    /** Removes all overlays from the map. */
    fun destroy() {
        fastEstimateMarker?.remove()
        fastEstimateMarker = null
        pdfOverlay.destroy()
    }

    /** Hides all overlays without removing them. */
    fun hide() {
        fastEstimateMarker?.isVisible = false
        pdfOverlay.hide()
    }

    /** Per-frame update for all estimate overlays. */
    fun update(distribution: ProbDistribution,
               shape: List<Location>, cumDist: DoubleArray,
               lastDist: Double, dtSec: Double, baseColor: Int) {
        labelSpeedHighMps = distribution.quantile(labelHighQuantile)

        for (i in 0..segmentCount) {
            val p = pdfLowQuantile + (pdfHighQuantile - pdfLowQuantile) * i / segmentCount
            pdfEdgeSpeedsMps[i] = distribution.quantile(p)
        }
        for (i in 0 until segmentCount) {
            pdfMidPdfValues[i] = distribution.pdf(
                    (pdfEdgeSpeedsMps[i] + pdfEdgeSpeedsMps[i + 1]) / 2.0)
        }

        updateFastEstimatePosition(lastDist + labelSpeedHighMps * dtSec, shape, cumDist)
        pdfOverlay.update(pdfEdgeSpeedsMps, pdfMidPdfValues,
                lastDist, dtSec, baseColor, shape, cumDist)
    }

    /** Returns true if the clicked marker was the fast estimate icon. */
    fun handleClick(marker: Marker): Boolean {
        val m = fastEstimateMarker ?: return false
        if (marker != m) return false
        if (m.isInfoWindowShown) m.hideInfoWindow() else m.showInfoWindow()
        return true
    }

    private fun updateFastEstimatePosition(distance: Double,
                                            shape: List<Location>, cumDist: DoubleArray) {
        val marker = fastEstimateMarker ?: return
        if (!LocationUtils.interpolateAlongPolyline(shape, cumDist, distance, reusableLoc)) {
            marker.isVisible = false
            return
        }
        marker.position = LatLng(reusableLoc.latitude, reusableLoc.longitude)
        marker.isVisible = true
    }
}
