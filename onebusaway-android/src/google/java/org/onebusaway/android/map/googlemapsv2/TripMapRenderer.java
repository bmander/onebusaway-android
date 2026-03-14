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
import android.location.Location;

import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.RoundCap;

import org.onebusaway.android.R;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaTripSchedule;
import org.onebusaway.android.speed.DistanceExtrapolator;
import org.onebusaway.android.speed.GammaSpeedModel;
import org.onebusaway.android.speed.VehicleHistoryEntry;
import org.onebusaway.android.speed.VehicleTrajectoryTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Owns all trip-specific map rendering: trip polyline, stop dots, estimate
 * overlays, and data-received markers. Activated when a vehicle is selected
 * and deactivated when deselected.
 */
final class TripMapRenderer {

    // --- Constants ---

    public static final float TRIP_BASE_WIDTH_PX = 44f;
    static final float TRIP_STOP_DIAMETER_PX = TRIP_BASE_WIDTH_PX * 2.5f;
    private static final float TRIP_WIDTH_FULL_ZOOM = 16f;
    private static final float TRIP_WIDTH_SHRINK_RATE = 0.5f;

    private static final float INFO_LABEL_Z_INDEX = 0.5f;
    private static final String DATA_RECEIVED_TITLE = "Most recent data";
    private static final int DATA_ICON_RADIUS_DP = 13;
    private static final int DATA_ICON_INNER_DP = 20;
    private static final int DATA_ICON_GAP_DP = 3;

    // --- Fields ---

    private final GoogleMap mMap;
    private final Context mContext;
    private final ChevronPolylineHelper mChevronHelper;

    private final ArrayList<Polyline> mTripPolylines = new ArrayList<>();
    private final ArrayList<Polyline> mTripStopDots = new ArrayList<>();

    private EstimateOverlayManager mEstimateOverlay;

    private Marker mDataReceivedIconMarker;
    private Marker mDataReceivedLabelMarker;
    private String mLastDataReceivedLabel;
    private float mDataReceivedLabelAnchorX;
    private BitmapDescriptor mCachedCircleIcon;

    private float mLastTripPolylineWidth = -1f;
    private boolean mActive;
    private String mActiveTripId;
    private int mRouteColor;

    TripMapRenderer(GoogleMap map, Context context, ChevronPolylineHelper chevronHelper) {
        mMap = map;
        mContext = context;
        mChevronHelper = chevronHelper;
    }

    // --- Lifecycle ---

    void activate(String tripId, List<Location> shape, double[] cumDist,
                  ObaTripSchedule schedule, int routeColor, LatLng vehiclePosition,
                  Integer routeType) {
        if (mActive) {
            deactivate();
        }
        mActive = true;
        mActiveTripId = tripId;
        mRouteColor = routeColor;

        if (shape != null && mMap != null) {
            showTripPolyline(shape, routeColor);
        }
        showTripStopCircles(schedule, shape, cumDist, routeColor);
        List<VehicleHistoryEntry> history = VehicleTrajectoryTracker.getInstance()
                .getHistoryReadOnly(tripId);
        showOrUpdateDataReceivedMarker(tripId, shape, cumDist, history);
        createEstimateOverlays(tripId, vehiclePosition, routeType);
    }

    void deactivate() {
        if (!mActive) return;
        removeTripPolylines();
        removeTripStopCircles();
        removeDataReceivedMarker();
        destroyEstimateOverlays();
        mActive = false;
        mActiveTripId = null;
        mLastTripPolylineWidth = -1f;
    }

    boolean isActive() {
        return mActive;
    }

    String getActiveTripId() {
        return mActiveTripId;
    }

    // --- Trip polyline ---

    private void showTripPolyline(List<Location> tripShape, int color) {
        removeTripPolylines();
        float width = tripPolylineWidth(mMap.getCameraPosition().zoom);
        mChevronHelper.addArrowPolyline(mMap, mTripPolylines, tripShape, color, width, 4,
                mContext.getResources());
    }

    private void removeTripPolylines() {
        for (Polyline p : mTripPolylines) {
            p.remove();
        }
        mTripPolylines.clear();
    }

    // --- Trip stop circles ---

    private void showTripStopCircles(ObaTripSchedule schedule,
                                     List<Location> shape, double[] cumDist, int color) {
        if (mMap == null || schedule == null || shape == null || cumDist == null) return;
        ObaTripSchedule.StopTime[] stopTimes = schedule.getStopTimes();
        if (stopTimes == null) return;

        float zoom = mMap.getCameraPosition().zoom;
        float stopWidth = tripPolylineWidth(zoom) / TRIP_BASE_WIDTH_PX * TRIP_STOP_DIAMETER_PX;
        double delta = 1.0;
        for (ObaTripSchedule.StopTime st : stopTimes) {
            double dist = st.getDistanceAlongTrip();
            Location locA = DistanceExtrapolator.interpolateAlongPolyline(
                    shape, cumDist, dist - delta);
            Location locB = DistanceExtrapolator.interpolateAlongPolyline(
                    shape, cumDist, dist + delta);
            if (locA == null || locB == null) continue;
            Polyline dot = mMap.addPolyline(new PolylineOptions()
                    .add(new LatLng(locA.getLatitude(), locA.getLongitude()),
                         new LatLng(locB.getLatitude(), locB.getLongitude()))
                    .width(stopWidth)
                    .color(color)
                    .startCap(new RoundCap())
                    .endCap(new RoundCap())
                    .zIndex(0f));
            mTripStopDots.add(dot);
        }
    }

    private void removeTripStopCircles() {
        for (Polyline p : mTripStopDots) {
            p.remove();
        }
        mTripStopDots.clear();
    }

    // --- Zoom-adaptive width ---

    static float tripPolylineWidth(float zoom) {
        if (zoom >= TRIP_WIDTH_FULL_ZOOM) return TRIP_BASE_WIDTH_PX;
        float dz = TRIP_WIDTH_FULL_ZOOM - zoom;
        return (float) (TRIP_BASE_WIDTH_PX * Math.pow(2, -dz * TRIP_WIDTH_SHRINK_RATE));
    }

    void onCameraZoomChanged(float zoom) {
        if (mTripStopDots.isEmpty() || mTripPolylines.isEmpty()) return;
        float width = tripPolylineWidth(zoom);
        if (width == mLastTripPolylineWidth) return;
        mLastTripPolylineWidth = width;
        float stopWidth = width / TRIP_BASE_WIDTH_PX * TRIP_STOP_DIAMETER_PX;
        for (Polyline p : mTripPolylines) {
            p.setWidth(width);
        }
        for (Polyline p : mTripStopDots) {
            p.setWidth(stopWidth);
        }
    }

    // --- Estimate overlays ---

    void updateEstimateOverlays(GammaSpeedModel.GammaParams params,
                                List<Location> shape, double[] cumDist,
                                List<VehicleHistoryEntry> history, long now,
                                int baseColor) {
        if (mEstimateOverlay == null) return;

        if (params == null || shape == null || cumDist == null
                || history == null || history.isEmpty()) {
            hideEstimateOverlays();
            return;
        }

        VehicleHistoryEntry newest = DistanceExtrapolator.findNewestValidEntry(history);
        if (newest == null) {
            hideEstimateOverlays();
            return;
        }

        Double lastDist = newest.getBestDistanceAlongTrip();
        long lastTime = newest.getLastLocationUpdateTime();
        if (lastDist == null || lastTime <= 0) {
            hideEstimateOverlays();
            return;
        }

        double dtSec = (now - lastTime) / 1000.0;
        if (dtSec < 0.5) {
            hideEstimateOverlays();
            return;
        }

        mEstimateOverlay.update(params, shape, cumDist, lastDist, dtSec, baseColor);
    }

    void hideEstimateOverlays() {
        if (mEstimateOverlay != null) mEstimateOverlay.hide();
    }

    boolean handleEstimateLabelClick(Marker marker) {
        return mEstimateOverlay != null && mEstimateOverlay.handleClick(marker);
    }

    private void createEstimateOverlays(String tripId, LatLng vehiclePosition,
                                        Integer routeType) {
        if (tripId == null) return;
        if (routeType != null && ObaRoute.isGradeSeparated(routeType)) return;
        if (vehiclePosition == null) return;

        mEstimateOverlay = new EstimateOverlayManager(mMap, mContext);
        mEstimateOverlay.create(vehiclePosition);
    }

    private void destroyEstimateOverlays() {
        if (mEstimateOverlay != null) {
            mEstimateOverlay.destroy();
            mEstimateOverlay = null;
        }
    }

    // --- Data-received marker ---

    void showOrUpdateDataReceivedMarker(String tripId,
                                        List<Location> shape, double[] cumDist,
                                        List<VehicleHistoryEntry> history) {
        if (tripId == null || history == null || history.isEmpty()) return;

        VehicleHistoryEntry latest = history.get(history.size() - 1);
        Location pos = latest.getPosition();
        if (pos == null) return;

        LatLng latLng = MapHelpV2.makeLatLng(pos);
        String label = formatElapsedTime(latest.getLastLocationUpdateTime());

        // Compute label rotation from polyline heading
        float labelRotation = 0f;
        Double lastDist = latest.getBestDistanceAlongTrip();
        if (lastDist != null && shape != null && cumDist != null) {
            double heading = DistanceExtrapolator.headingAlongPolyline(
                    shape, cumDist, lastDist);
            if (!Double.isNaN(heading)) {
                double labelAz = EstimateLabelManager.clampedLabelAzimuth(heading);
                labelRotation = (float) (labelAz - 90.0);
            }
        }

        // Icon marker: unrotated circle with signal icon
        if (mDataReceivedIconMarker != null) {
            mDataReceivedIconMarker.setPosition(latLng);
        } else {
            if (mCachedCircleIcon == null) {
                mCachedCircleIcon = createDataReceivedCircleIcon();
            }
            mDataReceivedIconMarker = mMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .icon(mCachedCircleIcon)
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .zIndex(INFO_LABEL_Z_INDEX + 0.1f)
            );
        }

        // Label marker: rotated bubble
        if (mDataReceivedLabelMarker != null) {
            mDataReceivedLabelMarker.setPosition(latLng);
            mDataReceivedLabelMarker.setRotation(labelRotation);
            if (!label.equals(mLastDataReceivedLabel)) {
                mLastDataReceivedLabel = label;
                mDataReceivedLabelMarker.setIcon(createDataReceivedLabelIcon(label));
            }
        } else {
            mLastDataReceivedLabel = label;
            BitmapDescriptor labelIcon = createDataReceivedLabelIcon(label);
            mDataReceivedLabelMarker = mMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .icon(labelIcon)
                    .anchor(mDataReceivedLabelAnchorX, 0.5f)
                    .flat(true)
                    .rotation(labelRotation)
                    .zIndex(INFO_LABEL_Z_INDEX)
            );
        }
    }

    void removeDataReceivedMarker() {
        if (mDataReceivedIconMarker != null) {
            mDataReceivedIconMarker.remove();
            mDataReceivedIconMarker = null;
        }
        if (mDataReceivedLabelMarker != null) {
            mDataReceivedLabelMarker.remove();
            mDataReceivedLabelMarker = null;
        }
        mLastDataReceivedLabel = null;
    }

    private String formatElapsedTime(long lastUpdateTime) {
        if (lastUpdateTime <= 0) return "";
        long elapsedSec = TimeUnit.MILLISECONDS.toSeconds(
                System.currentTimeMillis() - lastUpdateTime);
        if (elapsedSec < 0) elapsedSec = 0;
        if (elapsedSec < 60) {
            return elapsedSec + " sec ago";
        }
        long elapsedMin = TimeUnit.SECONDS.toMinutes(elapsedSec);
        long secMod60 = elapsedSec % 60;
        return elapsedMin + " min " + secMod60 + " sec ago";
    }

    private BitmapDescriptor createDataReceivedCircleIcon() {
        float d = mContext.getResources().getDisplayMetrics().density;
        int circleRadius = (int) (DATA_ICON_RADIUS_DP * d);
        int size = circleRadius * 2;

        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(0xFF616161);
        circlePaint.setStyle(Paint.Style.FILL);
        c.drawCircle(circleRadius, circleRadius, circleRadius, circlePaint);

        int iconSize = (int) (DATA_ICON_INNER_DP * d);
        android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(
                mContext, R.drawable.ic_signal_indicator);
        if (icon != null) {
            int iconLeft = (size - iconSize) / 2;
            int iconTop = (size - iconSize) / 2;
            icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            icon.draw(c);
        }

        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    private BitmapDescriptor createDataReceivedLabelIcon(String timeLine) {
        String[] lines = {DATA_RECEIVED_TITLE, timeLine};
        float[] widthOut = new float[1];
        BitmapDescriptor icon = EstimateLabelManager.createInfoLabelIcon(
                mContext, lines, widthOut);
        float d = mContext.getResources().getDisplayMetrics().density;
        float offsetPx = (DATA_ICON_RADIUS_DP + DATA_ICON_GAP_DP) * d;
        mDataReceivedLabelAnchorX = -offsetPx / widthOut[0];
        return icon;
    }
}
