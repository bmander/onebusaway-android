/*
 * Copyright (C) 2014 University of South Florida (sjbarbeau@gmail.com)
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

import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.map.render.VehicleBitmaps;

/**
 * Google-flavor adapter that wraps the shared {@link VehicleBitmaps} generation as a
 * {@link BitmapDescriptor} for {@code ObaMapContent}'s vehicle markers. The bitmap logic + caches
 * now live in {@code src/main} so the maplibre flavor reuses them.
 */
public final class VehicleIconFactory {

    private VehicleIconFactory() {
    }

    /** The vehicle marker icon for the given status, as a Google {@link BitmapDescriptor}. */
    public static BitmapDescriptor getVehicleIcon(Context context, boolean isRealtime,
                                                  ObaTripStatus status, ObaTripsForRouteResponse response) {
        return BitmapDescriptorFactory.fromBitmap(
                VehicleBitmaps.vehicleBitmap(context, isRealtime, status, response));
    }

    /** True if there is real-time location info for the status (used by the info window). */
    public static boolean isLocationRealtime(ObaTripStatus status) {
        return VehicleBitmaps.isLocationRealtime(status);
    }

    /** Distance in meters between two points, for the animate-vs-snap decision. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        float[] results = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, results);
        return results[0];
    }
}
