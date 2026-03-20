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

import android.content.Context;
import android.location.Location;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.onebusaway.android.R;
import org.onebusaway.android.extrapolation.math.ProbDistribution;
import org.onebusaway.android.util.LocationUtils;

import java.util.List;

/**
 * Manages all estimate-related map overlays for a selected vehicle:
 * the fast estimate icon marker and the PDF opacity segments.
 * Computes quantile speeds once per param change and shares them.
 */
public final class EstimateOverlayManager {

    private static final float MARKER_Z_INDEX = 3f;

    private final GoogleMap mMap;
    private final Context mContext;
    private final PdfOverlayRenderer mPdfOverlay;
    private final double mLabelHighQuantile;
    private final double mPdfLowQuantile;
    private final double mPdfHighQuantile;

    private final int mSegmentCount;
    private ProbDistribution mCachedDistribution;
    private double mCachedLabelSpeedHighMps;
    private final double[] mCachedPdfEdgeSpeedsMps;
    private final double[] mCachedPdfMidPdfValues;

    private Marker mFastEstimateMarker;
    private BitmapDescriptor mFastEstimateIcon;
    private final Location mReusableLoc = new Location("label");

    public EstimateOverlayManager(GoogleMap map, Context context) {
        this(map, context, 0.90, 0.01, 0.99, 9);
    }

    /**
     * @param labelHighQuantile quantile for the fast estimate label (e.g. 0.90)
     * @param pdfLowQuantile    quantile for the PDF overlay start (e.g. 0.01)
     * @param pdfHighQuantile   quantile for the PDF overlay end (e.g. 0.99)
     * @param segmentCount      number of opacity segments in the PDF overlay
     */
    public EstimateOverlayManager(GoogleMap map, Context context,
                                  double labelHighQuantile,
                                  double pdfLowQuantile, double pdfHighQuantile,
                                  int segmentCount) {
        mMap = map;
        mContext = context;
        mPdfOverlay = new PdfOverlayRenderer(map, segmentCount,
                TripMapRenderer.TRIP_BASE_WIDTH_PX);
        mSegmentCount = segmentCount;
        mCachedPdfEdgeSpeedsMps = new double[segmentCount + 1];
        mCachedPdfMidPdfValues = new double[segmentCount];
        mLabelHighQuantile = labelHighQuantile;
        mPdfLowQuantile = pdfLowQuantile;
        mPdfHighQuantile = pdfHighQuantile;
    }

    /** Creates all overlays at the given initial position. */
    public void create(LatLng initialPosition) {
        mFastEstimateIcon = MapIconUtils.createCircleIcon(mContext, R.drawable.ic_fast_estimate);
        mFastEstimateMarker = mMap.addMarker(new MarkerOptions()
                .position(initialPosition)
                .icon(mFastEstimateIcon)
                .title("Fast estimate")
                .snippet("90th percentile speed")
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(MARKER_Z_INDEX)
                .visible(false)
        );
        mPdfOverlay.create();
        mCachedDistribution = null;
    }

    /** Removes all overlays from the map. */
    public void destroy() {
        if (mFastEstimateMarker != null) {
            mFastEstimateMarker.remove();
            mFastEstimateMarker = null;
        }
        mFastEstimateIcon = null;
        mPdfOverlay.destroy();
        mCachedDistribution = null;
    }

    /** Hides all overlays without removing them. */
    public void hide() {
        if (mFastEstimateMarker != null) mFastEstimateMarker.setVisible(false);
        mPdfOverlay.hide();
    }

    /**
     * Per-frame update for all estimate overlays.
     */
    public void update(ProbDistribution distribution,
                       List<Location> shape, double[] cumDist,
                       double lastDist, double dtSec, int baseColor) {
        if (!distribution.equals(mCachedDistribution)) {
            mCachedDistribution = distribution;
            mCachedLabelSpeedHighMps = distribution.quantile(mLabelHighQuantile);

            for (int i = 0; i <= mSegmentCount; i++) {
                double p = mPdfLowQuantile
                        + (mPdfHighQuantile - mPdfLowQuantile) * i / mSegmentCount;
                mCachedPdfEdgeSpeedsMps[i] = distribution.quantile(p);
            }
            for (int i = 0; i < mSegmentCount; i++) {
                double midSpeedMps = (mCachedPdfEdgeSpeedsMps[i]
                        + mCachedPdfEdgeSpeedsMps[i + 1]) / 2.0;
                mCachedPdfMidPdfValues[i] = distribution.pdf(midSpeedMps);
            }
        }

        updateFastEstimatePosition(lastDist + mCachedLabelSpeedHighMps * dtSec, shape, cumDist);
        mPdfOverlay.update(mCachedPdfEdgeSpeedsMps, mCachedPdfMidPdfValues,
                lastDist, dtSec, baseColor, shape, cumDist);
    }

    /** Returns true if the clicked marker was the fast estimate icon. */
    public boolean handleClick(Marker marker) {
        if (marker.equals(mFastEstimateMarker)) {
            if (mFastEstimateMarker.isInfoWindowShown()) {
                mFastEstimateMarker.hideInfoWindow();
            } else {
                mFastEstimateMarker.showInfoWindow();
            }
            return true;
        }
        return false;
    }

    private void updateFastEstimatePosition(double distance,
                                             List<Location> shape, double[] cumDist) {
        if (mFastEstimateMarker == null) return;
        if (!LocationUtils.interpolateAlongPolyline(shape, cumDist, distance, mReusableLoc)) {
            mFastEstimateMarker.setVisible(false);
            return;
        }
        mFastEstimateMarker.setPosition(new LatLng(
                mReusableLoc.getLatitude(), mReusableLoc.getLongitude()));
        mFastEstimateMarker.setVisible(true);
    }
}
