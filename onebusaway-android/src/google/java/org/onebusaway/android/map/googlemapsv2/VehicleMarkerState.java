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
package org.onebusaway.android.map.googlemapsv2;

import android.content.Context;
import android.location.Location;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.onebusaway.android.R;
import org.onebusaway.android.extrapolation.Extrapolator;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.util.UIUtils;

/**
 * Consolidated per-vehicle state for a marker on the main map view.
 * One instance per tracked trip, referenced from both tripId and Marker lookups.
 */
class VehicleMarkerState {

    private final String mTripId;
    private final Marker mMarker;
    private ObaTripStatus mStatus;

    private Extrapolator mExtrapolator;
    private boolean mIsExtrapolating;
    private long mLastFixTimeMs;
    boolean animating;

    // --- Data-received marker (per-vehicle, shown when selected + extrapolating) ---

    private Marker mDataReceivedMarker;
    private long mDataReceivedFixTime;

    /** True when the user has tapped this vehicle and the info window is open. */
    boolean selected;

    VehicleMarkerState(String tripId, Marker marker, ObaTripStatus status) {
        mTripId = tripId;
        mMarker = marker;
        mStatus = status;
    }

    String getTripId() {
        return mTripId;
    }

    Marker getMarker() {
        return mMarker;
    }

    ObaTripStatus getStatus() {
        return mStatus;
    }

    void setStatus(ObaTripStatus status) {
        mStatus = status;
    }

    Extrapolator getExtrapolator() {
        return mExtrapolator;
    }

    void setExtrapolator(Extrapolator extrapolator) {
        mExtrapolator = extrapolator;
    }

    boolean isExtrapolating() {
        return mIsExtrapolating;
    }

    void setExtrapolating(boolean extrapolating) {
        mIsExtrapolating = extrapolating;
    }

    long getLastFixTimeMs() {
        return mLastFixTimeMs;
    }

    void setLastFixTimeMs(long lastFixTimeMs) {
        mLastFixTimeMs = lastFixTimeMs;
    }

    Marker getDataReceivedMarker() {
        return mDataReceivedMarker;
    }

    void showDataReceivedMarker(GoogleMap map, BitmapDescriptor icon, Context context) {
        removeDataReceivedMarker();
        Location loc = mStatus.getPosition();
        if (loc == null) return;
        if (!mStatus.isPredicted() || mStatus.getLastLocationUpdateTime() <= 0) return;
        long elapsed = System.currentTimeMillis() - mStatus.getLastLocationUpdateTime();
        mDataReceivedMarker = map.addMarker(new MarkerOptions()
                .position(MapHelpV2.makeLatLng(loc))
                .icon(icon)
                .title(context.getString(R.string.marker_most_recent_data))
                .snippet(UIUtils.formatElapsedTime(elapsed))
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(3.1f));
        mDataReceivedFixTime = mStatus.getLastLocationUpdateTime();
    }

    void updateDataReceivedMarker(ObaTripStatus newestValid, long now, int animDurationMs) {
        if (mDataReceivedMarker == null || newestValid == null) return;
        long fixTime = newestValid.getLastLocationUpdateTime();
        if (fixTime != mDataReceivedFixTime) {
            mDataReceivedFixTime = fixTime;
            Location loc = newestValid.getPosition();
            if (loc != null) {
                AnimationUtil.animateMarkerTo(mDataReceivedMarker, MapHelpV2.makeLatLng(loc), animDurationMs);
            }
        }
        // Always refresh the elapsed-time snippet so it stays current
        if (mDataReceivedFixTime > 0) {
            mDataReceivedMarker.setSnippet(UIUtils.formatElapsedTime(now - mDataReceivedFixTime));
        }
    }

    void removeDataReceivedMarker() {
        if (mDataReceivedMarker != null) {
            mDataReceivedMarker.remove();
            mDataReceivedMarker = null;
        }
        mDataReceivedFixTime = 0;
    }

    void destroy() {
        mMarker.remove();
        removeDataReceivedMarker();
    }
}
