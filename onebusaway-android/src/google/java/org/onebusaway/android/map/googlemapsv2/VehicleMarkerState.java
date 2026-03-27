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

import com.google.android.gms.maps.model.Marker;

import org.onebusaway.android.extrapolation.Extrapolator;
import org.onebusaway.android.io.elements.ObaTripStatus;

/**
 * Pure data container for per-vehicle state on the main map view.
 * One instance per tracked trip. Contains no map rendering logic — all marker
 * creation and animation is handled by {@link VehicleMapController}.
 */
class VehicleMarkerState {

    private final String mTripId;
    private ObaTripStatus mStatus;

    private Extrapolator mExtrapolator;
    private boolean mIsExtrapolating;
    private long mLastFixTimeMs;
    boolean animating;

    // Marker references — created and managed by VehicleMapController
    Marker vehicleMarker;
    Marker dataReceivedMarker;
    long dataReceivedFixTime;

    boolean selected;

    VehicleMarkerState(String tripId, ObaTripStatus status) {
        mTripId = tripId;
        mStatus = status;
    }

    String getTripId() { return mTripId; }

    ObaTripStatus getStatus() { return mStatus; }
    void setStatus(ObaTripStatus status) { mStatus = status; }

    Extrapolator getExtrapolator() { return mExtrapolator; }
    void setExtrapolator(Extrapolator extrapolator) { mExtrapolator = extrapolator; }

    boolean isExtrapolating() { return mIsExtrapolating; }
    void setExtrapolating(boolean extrapolating) { mIsExtrapolating = extrapolating; }

    long getLastFixTimeMs() { return mLastFixTimeMs; }
    void setLastFixTimeMs(long lastFixTimeMs) { mLastFixTimeMs = lastFixTimeMs; }
}
