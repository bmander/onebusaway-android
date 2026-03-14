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
package org.onebusaway.android.map.googlemapsv2;

import android.location.Location;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import org.onebusaway.android.speed.DistanceExtrapolator;
import org.onebusaway.android.speed.GammaSpeedModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the gamma speed PDF as polyline segments with varying opacity
 * on top of the trip polyline. Each segment's alpha is proportional to the
 * PDF value at its midpoint, normalized so the peak is fully opaque.
 */
public final class PdfOverlayRenderer {

    private static final int SEGMENT_COUNT = 6;
    private static final float WIDTH_PX = 50f;
    private static final float Z_INDEX = 2f;
    private static final int BASE_COLOR = 0x00FF0000; // red, alpha set per-frame

    private final GoogleMap mMap;
    private Polyline[] mSegments;

    // Pre-allocated arrays to avoid per-frame allocation
    private final double[] mSegDists = new double[SEGMENT_COUNT + 1];
    private final double[] mPdfValues = new double[SEGMENT_COUNT];
    private final Location mReusableLoc = new Location("pdf");

    public PdfOverlayRenderer(GoogleMap map) {
        mMap = map;
    }

    /** Creates the polyline segments on the map. */
    public void create() {
        mSegments = new Polyline[SEGMENT_COUNT];
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            mSegments[i] = mMap.addPolyline(new PolylineOptions()
                    .width(WIDTH_PX)
                    .color(BASE_COLOR)
                    .zIndex(Z_INDEX));
        }
    }

    /** Removes the segments from the map and clears state. */
    public void destroy() {
        if (mSegments == null) return;
        for (Polyline p : mSegments) p.remove();
        mSegments = null;
    }

    /** Hides all segments without removing them. */
    public void hide() {
        if (mSegments == null) return;
        for (Polyline p : mSegments) p.setVisible(false);
    }

    public boolean isActive() {
        return mSegments != null;
    }

    /**
     * Per-frame update: positions segments and sets opacities from the gamma PDF.
     *
     * @param params  gamma distribution parameters (for PDF evaluation)
     * @param dist10  slow estimate distance (10th percentile)
     * @param dist90  fast estimate distance (90th percentile)
     * @param lastDist AVL distance along the trip
     * @param dtSec   seconds since last AVL update
     * @param shape   decoded polyline points
     * @param cumDist precomputed cumulative distances
     */
    public void update(GammaSpeedModel.GammaParams params,
                       double dist10, double dist90,
                       double lastDist, double dtSec,
                       List<Location> shape, double[] cumDist) {
        if (mSegments == null) return;

        // Compute boundary distances and PDF values at each segment midpoint
        double maxPdf = 0;
        for (int i = 0; i <= SEGMENT_COUNT; i++) {
            mSegDists[i] = dist10 + (dist90 - dist10) * i / SEGMENT_COUNT;
        }
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            double midDist = (mSegDists[i] + mSegDists[i + 1]) / 2.0;
            double speedMps = (midDist - lastDist) / dtSec;
            double speedMph = speedMps * GammaSpeedModel.MPS_TO_MPH;
            mPdfValues[i] = GammaSpeedModel.pdf(speedMph, params);
            if (mPdfValues[i] > maxPdf) maxPdf = mPdfValues[i];
        }

        // Update each segment's geometry and opacity
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            updateSegment(i, mSegDists[i], mSegDists[i + 1],
                    mPdfValues[i], maxPdf, shape, cumDist);
        }
    }

    private void updateSegment(int index, double segStart, double segEnd,
                               double pdfValue, double maxPdf,
                               List<Location> shape, double[] cumDist) {
        List<LatLng> pts = new ArrayList<>();

        if (!DistanceExtrapolator.interpolateAlongPolyline(
                shape, cumDist, segStart, mReusableLoc)) {
            mSegments[index].setVisible(false); return;
        }
        pts.add(new LatLng(mReusableLoc.getLatitude(), mReusableLoc.getLongitude()));

        int[] range = DistanceExtrapolator.findVertexRange(cumDist, segStart, segEnd);
        if (range != null) {
            for (int j = range[0]; j < range[1]; j++) {
                Location v = shape.get(j);
                pts.add(new LatLng(v.getLatitude(), v.getLongitude()));
            }
        }

        if (!DistanceExtrapolator.interpolateAlongPolyline(
                shape, cumDist, segEnd, mReusableLoc)) {
            mSegments[index].setVisible(false); return;
        }
        pts.add(new LatLng(mReusableLoc.getLatitude(), mReusableLoc.getLongitude()));

        int alpha = maxPdf > 0 ? (int) (255 * pdfValue / maxPdf) : 0;
        mSegments[index].setPoints(pts);
        mSegments[index].setColor((alpha << 24) | BASE_COLOR);
        mSegments[index].setVisible(true);
    }
}
