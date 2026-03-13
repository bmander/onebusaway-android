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
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;

import org.onebusaway.android.speed.GammaSpeedModel;
import org.onebusaway.android.speed.PdfSpineEvaluator;
import org.onebusaway.android.speed.RouteSpine;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the Maps Polygon, transition animation, and per-frame vertex construction
 * for the gamma PDF overlay. Uses {@link RouteSpine} + {@link PdfSpineEvaluator}
 * for geometry and PDF math.
 */
public final class PdfPolygonRenderer {

    private static final float HIGHLIGHT_Z_INDEX = -1f;
    private static final int HIGHLIGHT_RGB = 0xAB47BC;
    private static final int PDF_FILL_ALPHA = 0x66;
    private static final int PDF_STROKE_RGB = 0xE1BEE7;
    private static final int PDF_STROKE_ALPHA = 0x88;
    private static final double PDF_UPPER_QUANTILE = 0.99;
    private static final long PDF_TRANSITION_MS = 600;
    private static final long PDF_RIPPLE_DELAY_MS = 1000;

    private final GoogleMap mMap;
    private final RouteSpine mSpine = new RouteSpine();
    private final PdfSpineEvaluator mEvaluator = new PdfSpineEvaluator();

    private Polygon mPdfPolygon;
    private final ArrayList<LatLng> mPolygonPoints = new ArrayList<>();
    private double[] mDLatsBuf = new double[0];
    private double[] mDLngsBuf = new double[0];

    // Transition animation state
    private double[] mOldPdfOffsets;
    private double[] mOldPdfSpineDistances;
    private long mPdfTransitionStartMs;
    private double mOldLastDist = Double.NaN;
    private double mLastInvScaleDt;
    /** Tracks the previous lastDist for transition continuity. */
    private double mPrevLastDist = Double.NaN;

    // Gamma cache
    private GammaSpeedModel.GammaParams mCachedParams;
    private double mCachedSpeed99Mps;
    private double mCachedInvScale;

    public PdfPolygonRenderer(GoogleMap map) {
        mMap = map;
    }

    /** Adds the polygon to the map. */
    public void create() {
        int fillColor = (PDF_FILL_ALPHA << 24) | HIGHLIGHT_RGB;
        int strokeColor = (PDF_STROKE_ALPHA << 24) | PDF_STROKE_RGB;
        mPdfPolygon = mMap.addPolygon(new PolygonOptions()
                .add(new LatLng(0, 0))
                .fillColor(fillColor)
                .strokeColor(strokeColor)
                .strokeWidth(4f)
                .zIndex(HIGHLIGHT_Z_INDEX)
                .visible(false));
    }

    /** Removes the polygon and clears all state. */
    public void destroy() {
        if (mPdfPolygon != null) {
            mPdfPolygon.remove();
            mPdfPolygon = null;
        }
        mCachedParams = null;
        mOldPdfOffsets = null;
        mOldPdfSpineDistances = null;
        mPdfTransitionStartMs = 0;
        mOldLastDist = Double.NaN;
        mPrevLastDist = Double.NaN;
    }

    /** Hides the polygon without removing it. */
    public void hide() {
        if (mPdfPolygon != null) mPdfPolygon.setVisible(false);
    }

    public boolean isActive() {
        return mPdfPolygon != null;
    }

    /**
     * Per-frame update: rebuilds spine if needed, evaluates PDF, handles
     * transition animation, and sets polygon vertices.
     *
     * @param params  gamma distribution parameters
     * @param shape   decoded polyline points
     * @param cumDist precomputed cumulative distances
     * @param lastDist AVL distance along the trip
     * @param dtSec   seconds since last AVL update
     * @param nowMs   current time in milliseconds
     */
    public void update(GammaSpeedModel.GammaParams params,
                       List<Location> shape, double[] cumDist,
                       double lastDist, double dtSec, long nowMs) {
        if (mPdfPolygon == null) return;

        // Cache gamma-derived values; snapshot old offsets on param change
        if (!params.equals(mCachedParams)) {
            snapshotOldPdfOffsets();
            mOldLastDist = mPrevLastDist;
            mPdfTransitionStartMs = nowMs;

            mCachedParams = params;
            mCachedSpeed99Mps = GammaSpeedModel.quantileMps(PDF_UPPER_QUANTILE, params);
            mCachedInvScale = 1.0 / params.scale;
        }
        mPrevLastDist = lastDist;

        double distEnd = lastDist + mCachedSpeed99Mps * dtSec;

        // Lazily build spine
        if (mSpine.needsRebuild(shape, lastDist)) {
            mSpine.build(shape, cumDist, RouteSpine.DEFAULT_SPACING_METERS, lastDist);
        }
        if (mSpine.size() == 0) {
            mPdfPolygon.setVisible(false);
            return;
        }

        // Ensure evaluator caches are current
        mEvaluator.ensureTable(params.alpha);
        mEvaluator.ensureDistDeltas(mSpine, lastDist);

        double invScaleDt = mCachedInvScale / dtSec;
        mLastInvScaleDt = invScaleDt;

        // Check transition state
        long transElapsed = 0;
        boolean transitioning = false;
        if (mPdfTransitionStartMs > 0 && mOldPdfOffsets != null) {
            transElapsed = nowMs - mPdfTransitionStartMs;
            if (transElapsed >= PDF_TRANSITION_MS + PDF_RIPPLE_DELAY_MS) {
                mPdfTransitionStartMs = 0;
                mOldPdfOffsets = null;
                mOldPdfSpineDistances = null;
                mOldLastDist = Double.NaN;
            } else {
                transitioning = true;
            }
        }

        // Determine spine range
        int startIdx = mSpine.findIndex(lastDist);
        if (transitioning && mOldPdfSpineDistances != null
                && mOldPdfSpineDistances.length > 0) {
            double oldStart = mOldPdfSpineDistances[0];
            if (oldStart < lastDist) {
                int oldStartIdx = mSpine.findIndex(oldStart);
                startIdx = Math.min(startIdx, oldStartIdx);
            }
        }

        int endIdx = mSpine.findIndex(distEnd);
        if (endIdx < mSpine.size()
                && mSpine.distanceAt(endIdx) == distEnd) {
            endIdx++;
        }

        if (startIdx >= endIdx || startIdx >= mSpine.size()) {
            mPdfPolygon.setVisible(false);
            return;
        }
        endIdx = Math.min(endIdx, mSpine.size());

        mPolygonPoints.clear();
        int rangeSize = endIdx - startIdx;

        if (mDLatsBuf.length < rangeSize) {
            mDLatsBuf = new double[rangeSize];
            mDLngsBuf = new double[rangeSize];
        }

        double rippleInvSpan = 0;
        double rippleStart = lastDist;
        double invTransitionMs = 1.0 / PDF_TRANSITION_MS;
        int oldCursor = 0;
        if (transitioning) {
            rippleStart = !Double.isNaN(mOldLastDist) ? mOldLastDist : lastDist;
            double rippleSpan = distEnd - rippleStart;
            rippleInvSpan = (rippleSpan > 0) ? 1.0 / rippleSpan : 1.0;
        }

        for (int i = startIdx; i < endIdx; i++) {
            double newOffset = (mSpine.distanceAt(i) >= lastDist)
                    ? mEvaluator.computeOffset(i, invScaleDt) : 0;

            double offset;
            if (transitioning) {
                double frac = (mSpine.distanceAt(i) - rippleStart) * rippleInvSpan;
                if (frac < 0) frac = 0;
                else if (frac > 1.0) frac = 1.0;
                long pointElapsed = transElapsed
                        - (long) (frac * PDF_RIPPLE_DELAY_MS);
                double blend;
                if (pointElapsed <= 0) {
                    blend = 0;
                } else if (pointElapsed >= PDF_TRANSITION_MS) {
                    blend = 1.0;
                } else {
                    double t = pointElapsed * invTransitionMs;
                    blend = t * t * (3.0 - 2.0 * t);
                }
                double oldOffset = resampleOldOffsetLinear(
                        mSpine.distanceAt(i), oldCursor);
                if (mOldPdfSpineDistances != null) {
                    while (oldCursor < mOldPdfSpineDistances.length - 1
                            && mOldPdfSpineDistances[oldCursor + 1]
                               <= mSpine.distanceAt(i)) {
                        oldCursor++;
                    }
                }
                offset = oldOffset + blend * (newOffset - oldOffset);
            } else {
                offset = newOffset;
            }

            int j = i - startIdx;
            mDLatsBuf[j] = offset * mSpine.perpCos[i];
            mDLngsBuf[j] = offset * mSpine.perpSin[i] * mSpine.latCosInv[i];

            mPolygonPoints.add(new LatLng(
                    mSpine.lats[i] + mDLatsBuf[j],
                    mSpine.lngs[i] + mDLngsBuf[j]));
        }

        // Backward pass: mirror across route
        for (int i = endIdx - 1; i >= startIdx; i--) {
            int j = i - startIdx;
            mPolygonPoints.add(new LatLng(
                    mSpine.lats[i] - mDLatsBuf[j],
                    mSpine.lngs[i] - mDLngsBuf[j]));
        }

        if (mPolygonPoints.size() >= 3) {
            mPdfPolygon.setPoints(mPolygonPoints);
            mPdfPolygon.setVisible(true);
        } else {
            mPdfPolygon.setVisible(false);
        }
    }

    private void snapshotOldPdfOffsets() {
        if (mSpine.distances == null || mLastInvScaleDt == 0) {
            mOldPdfOffsets = null;
            mOldPdfSpineDistances = null;
            return;
        }
        double invScaleDt = mLastInvScaleDt;
        int n = mSpine.size();
        mOldPdfOffsets = new double[n];
        mOldPdfSpineDistances = new double[n];
        for (int i = 0; i < n; i++) {
            mOldPdfSpineDistances[i] = mSpine.distanceAt(i);
            mOldPdfOffsets[i] = mEvaluator.computeOffset(i, invScaleDt);
        }
    }

    private double resampleOldOffsetLinear(double distance, int cursor) {
        if (mOldPdfOffsets == null || mOldPdfSpineDistances == null) return 0;
        int n = mOldPdfOffsets.length;
        if (n == 0) return 0;

        int ins = cursor;
        while (ins < n && mOldPdfSpineDistances[ins] < distance) {
            ins++;
        }

        if (ins >= n) return mOldPdfOffsets[n - 1];
        if (ins == 0) return mOldPdfOffsets[0];
        if (mOldPdfSpineDistances[ins] == distance) return mOldPdfOffsets[ins];

        double d0 = mOldPdfSpineDistances[ins - 1];
        double d1 = mOldPdfSpineDistances[ins];
        double segLen = d1 - d0;
        if (segLen <= 0) return mOldPdfOffsets[ins - 1];
        double frac = (distance - d0) / segLen;
        return mOldPdfOffsets[ins - 1]
                + frac * (mOldPdfOffsets[ins] - mOldPdfOffsets[ins - 1]);
    }
}
