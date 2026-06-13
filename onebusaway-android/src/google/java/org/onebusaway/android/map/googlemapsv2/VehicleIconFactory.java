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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;

import androidx.collection.LruCache;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.util.ArrivalInfoUtils;
import org.onebusaway.android.util.MathUtils;
import org.onebusaway.android.util.UIUtils;

import java.util.concurrent.TimeUnit;

/**
 * Stateless factory for vehicle marker icons. This is the icon half of the old {@code VehicleOverlay}
 * (the black direction-arrow template bitmaps, tinted by schedule deviation), lifted out so the
 * declarative {@code ObaMapContent} renderer can ask for a {@link BitmapDescriptor} per vehicle while
 * the imperative marker bookkeeping (add/move/remove) goes away. The two LRU caches are static, so
 * the costs are shared across the app exactly as before.
 */
public final class VehicleIconFactory {

    private VehicleIconFactory() {
    }

    private static final int NORTH = 0;  // directions are clockwise, consistent with MathUtils class

    private static final int NORTH_EAST = 1;

    private static final int EAST = 2;

    private static final int SOUTH_EAST = 3;

    private static final int SOUTH = 4;

    private static final int SOUTH_WEST = 5;

    private static final int WEST = 6;

    private static final int NORTH_WEST = 7;

    private static final int NUM_DIRECTIONS = 9; // 8 directions + undirected vehicles

    private static final int DEFAULT_VEHICLE_TYPE = ObaRoute.TYPE_BUS; // fall back on bus

    private static final int MAX_CACHE_SIZE = 15;

    // Vehicle type (if available) -> black template icon set
    private static final LruCache<String, Bitmap> sUncoloredIcons = new LruCache<>(MAX_CACHE_SIZE);

    // Colored (schedule-deviation tinted) versions of the template icons
    private static final LruCache<String, Bitmap> sColoredIconCache = new LruCache<>(MAX_CACHE_SIZE);

    /**
     * Returns the icon for the given vehicle status, ready to drop on a marker. Mirrors the legacy
     * {@code VehicleOverlay.MarkerData.getVehicleIcon}.
     */
    public static BitmapDescriptor getVehicleIcon(Context context, boolean isRealtime, ObaTripStatus status,
                                           ObaTripsForRouteResponse response) {
        String routeId = response.getTrip(status.getActiveTripId()).getRouteId();
        ObaRoute route = response.getRoute(routeId);
        int vehicleType = route.getType();

        int colorResource;
        if (isRealtime) {
            long deviationMin = TimeUnit.SECONDS.toMinutes(status.getScheduleDeviation());
            colorResource = ArrivalInfoUtils.computeColorFromDeviation(deviationMin);
        } else {
            colorResource = R.color.stop_info_scheduled_time;
        }
        double direction = MathUtils.toDirection(status.getOrientation());
        int halfWind = MathUtils.getHalfWindIndex((float) direction, NUM_DIRECTIONS - 1);

        Bitmap b = getBitmap(context, vehicleType, colorResource, halfWind);
        return BitmapDescriptorFactory.fromBitmap(b);
    }

    /**
     * Returns true if there is real-time location information for the given status. Mirrors the
     * populate-time decision in the legacy overlay (last-known location present + predicted).
     */
    public static boolean isLocationRealtime(ObaTripStatus status) {
        boolean isRealtime = status.getLastKnownLocation() != null;
        if (!status.isPredicted()) {
            isRealtime = false;
        }
        return isRealtime;
    }

    private static Bitmap getBitmap(Context context, int vehicleType, int colorResource, int halfWind) {
        int color = ContextCompat.getColor(context, colorResource);

        // Use tram icon for cablecar
        if (vehicleType == ObaRoute.TYPE_CABLECAR) {
            vehicleType = ObaRoute.TYPE_TRAM;
        }

        String key = createBitmapCacheKey(vehicleType, halfWind, colorResource);
        Bitmap b = sColoredIconCache.get(key);
        if (b == null) {
            // Cache miss - create Bitmap and add to cache
            b = UIUtils.colorBitmap(getIcon(halfWind, vehicleType), color);
            if (sColoredIconCache.get(key) == null) {
                sColoredIconCache.put(key, b);
            }
        }
        return b;
    }

    private static String createBitmapCacheKey(int vehicleType, int halfWind, int colorResource) {
        if (!supportedVehicleType(vehicleType)) {
            vehicleType = DEFAULT_VEHICLE_TYPE;
        }
        return vehicleType + " " + halfWind + " " + colorResource;
    }

    private static Bitmap getIcon(int halfWind, int vehicleType) {
        if (!supportedVehicleType(vehicleType)) {
            vehicleType = DEFAULT_VEHICLE_TYPE;
        }

        String cacheKey = String.format("%d %d", halfWind, vehicleType);
        Bitmap b = sUncoloredIcons.get(cacheKey);
        if (b == null) {  // cache miss
            switch (vehicleType) {
                case ObaRoute.TYPE_BUS:
                    b = createBusIcon(halfWind);
                    break;
                case ObaRoute.TYPE_FERRY:
                    b = createFerryIcon(halfWind);
                    break;
                case ObaRoute.TYPE_TRAM:
                    b = createTramIcon(halfWind);
                    break;
                case ObaRoute.TYPE_SUBWAY:
                    b = createSubwayIcon(halfWind);
                    break;
                case ObaRoute.TYPE_RAIL:
                    b = createRailIcon(halfWind);
                    break;
                // default: not needed, since supported vehicles are checked prior
            }
        }
        sUncoloredIcons.put(cacheKey, b);
        return b;
    }

    private static boolean supportedVehicleType(int vehicleType) {
        return vehicleType == ObaRoute.TYPE_BUS ||
                vehicleType == ObaRoute.TYPE_FERRY ||
                vehicleType == ObaRoute.TYPE_TRAM ||
                vehicleType == ObaRoute.TYPE_SUBWAY ||
                vehicleType == ObaRoute.TYPE_RAIL;
    }

    private static Bitmap createBusIcon(int halfWind) {
        Resources r = Application.get().getResources();
        switch (halfWind) {
            case NORTH:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_bus_smaller_north_inside);
            case NORTH_EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_bus_smaller_north_east_inside);
            case EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_bus_smaller_east_inside);
            case SOUTH_EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_bus_smaller_south_east_inside);
            case SOUTH:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_bus_smaller_south_inside);
            case SOUTH_WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_bus_smaller_south_west_inside);
            case WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_bus_smaller_west_inside);
            case NORTH_WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_bus_smaller_north_west_inside);
            default:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_bus_smaller_none_inside);
        }
    }

    private static Bitmap createTramIcon(int halfWind) {
        Resources r = Application.get().getResources();
        switch (halfWind) {
            case NORTH:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_tram_smaller_north_inside);
            case NORTH_EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_tram_smaller_north_east_inside);
            case EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_tram_smaller_east_inside);
            case SOUTH_EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_tram_smaller_south_east_inside);
            case SOUTH:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_tram_smaller_south_inside);
            case SOUTH_WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_tram_smaller_south_west_inside);
            case WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_tram_smaller_west_inside);
            case NORTH_WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_tram_smaller_north_west_inside);
            default:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_tram_smaller_none_inside);
        }
    }

    private static Bitmap createRailIcon(int halfWind) {
        Resources r = Application.get().getResources();
        switch (halfWind) {
            case NORTH:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_train_smaller_north_inside);
            case NORTH_EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_train_smaller_north_east_inside);
            case EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_train_smaller_east_inside);
            case SOUTH_EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_train_smaller_south_east_inside);
            case SOUTH:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_train_smaller_south_inside);
            case SOUTH_WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_train_smaller_south_west_inside);
            case WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_train_smaller_west_inside);
            case NORTH_WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_train_smaller_north_west_inside);
            default:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_train_smaller_none_inside);
        }
    }

    private static Bitmap createFerryIcon(int halfWind) {
        Resources r = Application.get().getResources();
        switch (halfWind) {
            case NORTH:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_boat_smaller_north_inside);
            case NORTH_EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_boat_smaller_north_east_inside);
            case EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_boat_smaller_east_inside);
            case SOUTH_EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_boat_smaller_south_east_inside);
            case SOUTH:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_boat_smaller_south_inside);
            case SOUTH_WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_boat_smaller_south_west_inside);
            case WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_boat_smaller_west_inside);
            case NORTH_WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_boat_smaller_north_west_inside);
            default:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_boat_smaller_none_inside);
        }
    }

    private static Bitmap createSubwayIcon(int halfWind) {
        Resources r = Application.get().getResources();
        switch (halfWind) {
            case NORTH:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_subway_smaller_north_inside);
            case NORTH_EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_subway_smaller_north_east_inside);
            case EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_subway_smaller_east_inside);
            case SOUTH_EAST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_subway_smaller_south_east_inside);
            case SOUTH:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_subway_smaller_south_inside);
            case SOUTH_WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_subway_smaller_south_west_inside);
            case WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_subway_smaller_west_inside);
            case NORTH_WEST:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_subway_smaller_north_west_inside);
            default:
                return BitmapFactory.decodeResource(r, R.drawable.ic_marker_with_subway_smaller_none_inside);
        }
    }

    /** Used by the static color (meters) helper for animate-vs-snap; distance in meters between points. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        float[] results = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, results);
        return results[0];
    }
}
