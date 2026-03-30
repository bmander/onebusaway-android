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
 * Per-vehicle state on the main map view. One instance per tracked trip.
 * All fields are package-private — owned and managed by {@link VehicleMapController}.
 */
class VehicleMarkerState {

    final String tripId;
    ObaTripStatus status;
    Extrapolator extrapolator;
    boolean extrapolating;
    long lastFixTimeMs;
    boolean animating;
    boolean selected;

    Marker vehicleMarker;
    Marker dataReceivedMarker;
    long dataReceivedFixTime;

    VehicleMarkerState(String tripId, ObaTripStatus status) {
        this.tripId = tripId;
        this.status = status;
    }
}
