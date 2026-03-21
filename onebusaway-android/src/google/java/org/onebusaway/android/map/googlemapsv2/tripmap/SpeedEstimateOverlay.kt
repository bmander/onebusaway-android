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

import org.onebusaway.android.map.googlemapsv2.MapIconUtils
import android.content.Context
import android.location.Location
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import org.onebusaway.android.R
import org.onebusaway.android.extrapolation.math.ProbDistribution
import org.onebusaway.android.util.LocationUtils

private const val MARKER_Z_INDEX = 3f
private const val SEGMENT_Z_INDEX = 2f
private const val FAST_ESTIMATE_QUANTILE = 0.90
private const val PDF_LOW_QUANTILE = 0.01
private const val PDF_HIGH_QUANTILE = 0.99
private const val DEFAULT_SEGMENT_COUNT = 15

/**
 * Renders the speed estimate visualization on the trip map: a set of opacity-graded
 * polyline segments showing the PDF, plus a fast-estimate icon marker at the 90th
 * percentile position. Takes a [ProbDistribution] each frame and handles all quantile
 * computation, distance conversion, polyline geometry, and marker positioning.
 */
class SpeedEstimateOverlay @JvmOverloads constructor(
        private val map: GoogleMap,
        private val context: Context,
        private val polylineWidth: Float,
        private val segmentCount: Int = DEFAULT_SEGMENT_COUNT
) {
    // --- Pre-allocated per-frame arrays ---
    private val edgeDistances = DoubleArray(segmentCount + 1)
    private val pdfValues = DoubleArray(segmentCount)
    private val reusableLoc = Location("overlay")

    // --- Map objects ---
    private var segments: Array<Polyline>? = null
    private val segmentPoints = List(segmentCount) { mutableListOf<LatLng>() }
    private var fastEstimateMarker: Marker? = null
    private val fastEstimateIcon = MapIconUtils.createCircleIcon(context, R.drawable.ic_fast_estimate)

    // --- Lifecycle ---

    fun create(initialPosition: LatLng) {
        segments = Array(segmentCount) {
            map.addPolyline(PolylineOptions()
                    .width(polylineWidth)
                    .color(0)
                    .zIndex(SEGMENT_Z_INDEX))
        }
        fastEstimateMarker = map.addMarker(MarkerOptions()
                .position(initialPosition)
                .icon(fastEstimateIcon)
                .title(context.getString(R.string.marker_fast_estimate))
                .snippet(context.getString(R.string.marker_fast_estimate_snippet))
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(MARKER_Z_INDEX)
                .visible(false))
    }

    fun destroy() {
        segments?.forEach { it.remove() }
        segments = null
        fastEstimateMarker?.remove()
        fastEstimateMarker = null
    }

    fun hide() {
        segments?.forEach { it.isVisible = false }
        fastEstimateMarker?.isVisible = false
    }

    // --- Per-frame update ---

    /**
     * Updates all overlay geometry from the distance distribution. Quantiles are distances
     * along the trip; PDF values determine segment opacity.
     */
    fun update(distribution: ProbDistribution,
               shape: List<Location>, cumDist: DoubleArray, baseColor: Int) {
        val segs = segments ?: return

        // Compute edge distances and PDF values at midpoints
        var maxPdf = 0.0
        for (i in 0..segmentCount) {
            val p = PDF_LOW_QUANTILE + (PDF_HIGH_QUANTILE - PDF_LOW_QUANTILE) * i / segmentCount
            edgeDistances[i] = distribution.quantile(p)
        }
        for (i in 0 until segmentCount) {
            val midP = PDF_LOW_QUANTILE + (PDF_HIGH_QUANTILE - PDF_LOW_QUANTILE) * (i + 0.5) / segmentCount
            val midDist = distribution.quantile(midP)
            pdfValues[i] = distribution.pdf(midDist)
            if (pdfValues[i] > maxPdf) maxPdf = pdfValues[i]
        }

        // Update polyline segments
        val rgb = baseColor and 0x00FFFFFF
        for (i in 0 until segmentCount) {
            updateSegment(segs[i], segmentPoints[i],
                    edgeDistances[i], edgeDistances[i + 1],
                    pdfValues[i], maxPdf, rgb, shape, cumDist)
        }

        // Update fast-estimate marker
        updateMarkerPosition(distribution.quantile(FAST_ESTIMATE_QUANTILE), shape, cumDist)
    }

    /** Returns true if the clicked marker was the fast estimate icon. */
    fun handleClick(marker: Marker): Boolean {
        val m = fastEstimateMarker ?: return false
        if (marker != m) return false
        if (m.isInfoWindowShown) m.hideInfoWindow() else m.showInfoWindow()
        return true
    }

    // --- Segment rendering ---

    private fun updateSegment(polyline: Polyline, pts: MutableList<LatLng>,
                               segStart: Double, segEnd: Double,
                               pdfValue: Double, maxPdf: Double, rgb: Int,
                               shape: List<Location>, cumDist: DoubleArray) {
        pts.clear()

        if (!LocationUtils.interpolateAlongPolyline(shape, cumDist, segStart, reusableLoc)) {
            polyline.isVisible = false; return
        }
        pts.add(LatLng(reusableLoc.latitude, reusableLoc.longitude))

        val range = LocationUtils.findVertexRange(cumDist, segStart, segEnd)
        if (range != null) {
            for (j in range[0] until range[1]) {
                val v = shape[j]
                pts.add(LatLng(v.latitude, v.longitude))
            }
        }

        if (!LocationUtils.interpolateAlongPolyline(shape, cumDist, segEnd, reusableLoc)) {
            polyline.isVisible = false; return
        }
        pts.add(LatLng(reusableLoc.latitude, reusableLoc.longitude))

        val alpha = if (maxPdf > 0) (255 * pdfValue / maxPdf).toInt() else 0
        polyline.points = pts
        polyline.color = (alpha shl 24) or rgb
        polyline.isVisible = true
    }

    // --- Marker positioning ---

    private fun updateMarkerPosition(distance: Double,
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
