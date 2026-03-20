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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.location.Location;

import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.onebusaway.android.R;
import org.onebusaway.android.util.LocationUtils;

import java.util.List;

/**
 * Owns the fast estimate icon marker on the trip polyline.
 * Tapping the icon shows an info window with the estimate description.
 */
public final class EstimateLabelManager {

    static final float INFO_LABEL_Z_INDEX = 3f;
    private static final int ICON_SIZE_DP = 28;
    private static final int ICON_PADDING_DP = 4;
    private static final float STROKE_WIDTH_DP = 2f;

    private final GoogleMap mMap;
    private final Context mContext;

    private Marker mFastEstimateMarker;
    private BitmapDescriptor mIcon;

    private final Location mReusableLoc = new Location("label");

    public EstimateLabelManager(GoogleMap map, Context context) {
        mMap = map;
        mContext = context;
    }

    /** Creates the marker at the given initial position. */
    public void create(LatLng initialPosition) {
        mIcon = createCircleIcon();
        mFastEstimateMarker = mMap.addMarker(new MarkerOptions()
                .position(initialPosition)
                .icon(mIcon)
                .title("Fast estimate")
                .snippet("90th percentile speed")
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(INFO_LABEL_Z_INDEX)
                .visible(false)
        );
    }

    /** Removes the marker and clears state. */
    public void destroy() {
        if (mFastEstimateMarker != null) {
            mFastEstimateMarker.remove();
            mFastEstimateMarker = null;
        }
        mIcon = null;
    }

    /** Hides the marker without removing it. */
    public void hide() {
        if (mFastEstimateMarker != null) mFastEstimateMarker.setVisible(false);
    }

    public boolean isActive() {
        return mFastEstimateMarker != null;
    }

    /**
     * Per-frame update: positions the icon at the given distance along the polyline.
     */
    public void update(double dist90,
                       List<Location> shape, double[] cumDist) {
        if (mFastEstimateMarker == null) return;

        if (!LocationUtils.interpolateAlongPolyline(
                shape, cumDist, dist90, mReusableLoc)) {
            mFastEstimateMarker.setVisible(false);
            return;
        }
        mFastEstimateMarker.setPosition(new LatLng(
                mReusableLoc.getLatitude(),
                mReusableLoc.getLongitude()));
        mFastEstimateMarker.setVisible(true);
    }

    /**
     * Handles a click on the estimate marker by showing/hiding its info window.
     * Returns true if the marker was the estimate icon.
     */
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

    /**
     * Creates a circular icon with the vector drawable centered inside.
     */
    private BitmapDescriptor createCircleIcon() {
        float d = mContext.getResources().getDisplayMetrics().density;
        int sizePx = (int) (ICON_SIZE_DP * d);
        int padding = (int) (ICON_PADDING_DP * d);
        float strokeWidth = STROKE_WIDTH_DP * d;
        float cx = sizePx / 2f;
        float cy = sizePx / 2f;

        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Stroke
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(0xFF616161);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        canvas.drawCircle(cx, cy, cx - strokeWidth / 2, strokePaint);

        // Fill
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(0xDDFFFFFF);
        fillPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, cx - strokeWidth, fillPaint);

        // Icon
        Drawable icon = ContextCompat.getDrawable(mContext, R.drawable.ic_fast_estimate);
        if (icon != null) {
            icon.setBounds(padding, padding, sizePx - padding, sizePx - padding);
            icon.draw(canvas);
        }

        return BitmapDescriptorFactory.fromBitmap(bmp);
    }
}
