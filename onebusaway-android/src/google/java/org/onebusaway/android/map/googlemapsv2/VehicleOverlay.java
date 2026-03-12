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

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Handler;
import android.util.Log;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.collection.LruCache;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaTrip;
import org.onebusaway.android.io.elements.ObaTripDetails;
import org.onebusaway.android.io.elements.ObaTripSchedule;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.elements.OccupancyState;
import org.onebusaway.android.io.elements.Status;
import org.onebusaway.android.io.request.ObaShapeRequest;
import org.onebusaway.android.io.request.ObaShapeResponse;
import org.onebusaway.android.io.request.ObaTripDetailsRequest;
import org.onebusaway.android.io.request.ObaTripDetailsResponse;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.speed.DistanceExtrapolator;
import org.onebusaway.android.speed.GammaSpeedModel;
import org.onebusaway.android.speed.VehicleHistoryEntry;
import org.onebusaway.android.speed.VehicleTrajectoryTracker;
import org.onebusaway.android.speed.VehicleState;
import org.onebusaway.android.ui.TripDetailsActivity;
import org.onebusaway.android.ui.TripDetailsListFragment;
import org.onebusaway.android.util.ArrivalInfoUtils;
import org.onebusaway.android.util.MathUtils;
import org.onebusaway.android.util.UIUtils;

import android.animation.ValueAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A map overlay that shows vehicle positions on the map
 */
public class VehicleOverlay implements GoogleMap.OnInfoWindowClickListener, MarkerListeners  {

    interface Controller {
        String getFocusedStopId();
    }

    private static final String TAG = "VehicleOverlay";

    private GoogleMap mMap;

    private MarkerData mMarkerData;

    private final Activity mActivity;

    private ObaTripsForRouteResponse mLastResponse;

    private CustomInfoWindowAdapter mCustomInfoWindowAdapter;

    private Controller mController;

    private boolean mExtrapolationTicking;
    private final Choreographer.FrameCallback mFrameCallback = this::onExtrapolationFrame;

    private static final int NORTH = 0;  // directions are clockwise, consistent with MathUtils class

    private static final int NORTH_EAST = 1;

    private static final int EAST = 2;

    private static final int SOUTH_EAST = 3;

    private static final int SOUTH = 4;

    private static final int SOUTH_WEST = 5;

    private static final int WEST = 6;

    private static final int NORTH_WEST = 7;

    private static final int NO_DIRECTION = 8;

    private static final int NUM_DIRECTIONS = 9; // 8 directions + undirected mVehicles

    private static final int DEFAULT_VEHICLE_TYPE = ObaRoute.TYPE_BUS; // fall back on bus

    // Vehicle type (if available) -> icon set
    private static LruCache<String, Bitmap> mVehicleUncoloredIcons;

    private static LruCache<String, Bitmap> mVehicleColoredIconCache;
    // Colored versions of vehicle_icons

    /**
     * If a vehicle moves less than this distance (in meters), it will be animated, otherwise it
     * will just disappear and then re-appear
     */
    private static final double MAX_VEHICLE_ANIMATION_DISTANCE = 400;

    /**
     * z-index used to show vehicle markers on top of stop markers (default marker z-index is 0)
     */
    private static final float VEHICLE_MARKER_Z_INDEX = 1;

    public VehicleOverlay(Activity activity, GoogleMap map) {
        mActivity = activity;
        mMap = map;
        loadIcons();
        // Set adapter for custom info window that appears when tapping on vehicle markers
        mCustomInfoWindowAdapter = new CustomInfoWindowAdapter(mActivity);
        setupInfoWindow();
    }

    private void setupInfoWindow() {
        mMap.setInfoWindowAdapter(mCustomInfoWindowAdapter);
        mMap.setOnInfoWindowClickListener(this);
    }

    public void setController(Controller controller) {
        mController = controller;
    }

    /**
     * Updates vehicles for the provided routeIds from the status info from the given
     * ObaTripsForRouteResponse
     *
     * @param routeIds routeIds for which to add vehicle markers to the map.  If a vehicle is
     *                 running a route that is not contained in this list, the vehicle won't be
     *                 shown on the map.
     * @param response response that contains the real-time status info
     */
    public void updateVehicles(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
        // Make sure that the MarkerData has been initialized
        setupMarkerData();
        // Cache the response, so when a marker is tapped we can look up route names from routeIds, etc.
        mLastResponse = response;
        // Show the markers on the map
        mMarkerData.populate(routeIds, response);
        startExtrapolationTicking();
    }

    public synchronized int size() {
        if (mMarkerData != null) {
            return mMarkerData.size();
        } else {
            return 0;
        }
    }

    /**
     * Clears any vehicle markers from the map
     */
    public synchronized void clear() {
        stopExtrapolationTicking();
        if (mMarkerData != null) {
            mMarkerData.clear();
            mMarkerData = null;
        }
    }

    /**
     * Cache the core black template Bitmaps used for vehicle icons
     */
    private static final void loadIcons() {
        /**
         * Cache for colored versions of the vehicle icons.  Total possible number of entries is
         * 9 directions * 4 color types (early, ontime, delayed, scheduled) = 36.  In a test,
         * the RouteMapController used around 15 bitmaps over a 30 min period for 4 vehicles on the
         * map at 10 sec refresh rate.  This can be more depending on the route configuration (if
         * the route has lots of curves) and number of vehicles.  To conserve memory, we'll set the
         * max cache size at 15.
         */
        final int MAX_CACHE_SIZE = 15;

        if (mVehicleUncoloredIcons == null) {
            mVehicleUncoloredIcons = new LruCache<>(MAX_CACHE_SIZE);
        }

        if (mVehicleColoredIconCache == null) {
            mVehicleColoredIconCache = new LruCache<>(MAX_CACHE_SIZE);
        }
    }

    /**
     * Gets the icon, ready to color for the given direction and vehicle type
     *
     * @param halfWind    an index between 0 and numHalfWinds-1 that can be used to retrieve
     *                    the direction name for that heading (known as "boxing the compass", down to the half-wind
     *                    level).
     * @param vehicleType type as defined by GTFS spec. Acceptable values contained in OBARoute.TYPE_*
     *
     * @return the icon ready to color
     */
    private static Bitmap getIcon(int halfWind, int vehicleType) {
        if (!supportedVehicleType(vehicleType)) {
            vehicleType = DEFAULT_VEHICLE_TYPE;
        }

        String cacheKey = String.format("%d %d", halfWind, vehicleType);

        Bitmap b = mVehicleUncoloredIcons.get(cacheKey);

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

        mVehicleUncoloredIcons.put(cacheKey, b);

        return b;
    }

    private static boolean supportedVehicleType(int vehicleType) {
        return vehicleType == ObaRoute.TYPE_BUS ||
                vehicleType == ObaRoute.TYPE_FERRY ||
                vehicleType == ObaRoute.TYPE_TRAM ||
                vehicleType == ObaRoute.TYPE_SUBWAY ||
                vehicleType == ObaRoute.TYPE_RAIL;

    }

    /**
     * Create the bus icon with the given direction arrows or without a direction arrow
     * for direction of NO_DIRECTION.  Color is black so they can be tinted later.
     *
     * @return vehicle icon bitmap with the arrow pointing the appropriate direction, or with
     * no arrow for NO_DIRECTION
     */
    private static Bitmap createBusIcon(int halfWind) {
        Resources r = Application.get().getResources();
        switch (halfWind) {
            case NORTH:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_north_inside);
            case NORTH_EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_north_east_inside);
            case EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_east_inside);
            case SOUTH_EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_south_east_inside);
            case SOUTH:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_south_inside);
            case SOUTH_WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_south_west_inside);
            case WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_west_inside);
            case NORTH_WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_north_west_inside);
            default:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_none_inside);

        }
    }

    /**
     * Create the tram icon with the given direction arrows or without a direction arrow
     * for direction of NO_DIRECTION.  Color is black so they can be tinted later.
     *
     * @return vehicle icon bitmap with the arrow pointing the appropriate direction, or with
     * no arrow for NO_DIRECTION
     */
    private static Bitmap createTramIcon(int halfWind) {
        Resources r = Application.get().getResources();
        switch (halfWind) {
            case NORTH:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_tram_smaller_north_inside);
            case NORTH_EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_tram_smaller_north_east_inside);
            case EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_tram_smaller_east_inside);
            case SOUTH_EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_tram_smaller_south_east_inside);
            case SOUTH:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_tram_smaller_south_inside);
            case SOUTH_WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_tram_smaller_south_west_inside);
            case WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_tram_smaller_west_inside);
            case NORTH_WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_tram_smaller_north_west_inside);
            default:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_tram_smaller_none_inside);
        }
    }

    /**
     * Create the rail icon with the given direction arrows or without a direction arrow
     * for direction of NO_DIRECTION.  Color is black so they can be tinted later.
     *
     * @return vehicle icon bitmap with the arrow pointing the appropriate direction, or with
     * no arrow for NO_DIRECTION
     */
    private static Bitmap createRailIcon(int halfWind) {
        Resources r = Application.get().getResources();
        switch (halfWind) {
            case NORTH:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_train_smaller_north_inside);
            case NORTH_EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_train_smaller_north_east_inside);
            case EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_train_smaller_east_inside);
            case SOUTH_EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_train_smaller_south_east_inside);
            case SOUTH:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_train_smaller_south_inside);
            case SOUTH_WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_train_smaller_south_west_inside);
            case WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_train_smaller_west_inside);
            case NORTH_WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_train_smaller_north_west_inside);
            default:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_train_smaller_none_inside);
        }
    }

    /**
     * Create the ferry icon with the given direction arrows or without a direction arrow
     * for direction of NO_DIRECTION.  Color is black so they can be tinted later.
     *
     * @return vehicle icon bitmap with the arrow pointing the appropriate direction, or with
     * no arrow for NO_DIRECTION
     */
    private static Bitmap createFerryIcon(int halfWind) {
        Resources r = Application.get().getResources();
        switch (halfWind) {
            case NORTH:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_boat_smaller_north_inside);
            case NORTH_EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_boat_smaller_north_east_inside);
            case EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_boat_smaller_east_inside);
            case SOUTH_EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_boat_smaller_south_east_inside);
            case SOUTH:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_boat_smaller_south_inside);
            case SOUTH_WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_boat_smaller_south_west_inside);
            case WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_boat_smaller_west_inside);
            case NORTH_WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_boat_smaller_north_west_inside);
            default:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_boat_smaller_none_inside);
        }
    }

    /**
     * Create the subway icon with the given direction arrows or without a direction arrow
     * for direction of NO_DIRECTION.  Color is black so they can be tinted later.
     *
     * @return vehicle icon bitmap with the arrow pointing the appropriate direction, or with
     * no arrow for NO_DIRECTION
     */
    private static Bitmap createSubwayIcon(int halfWind) {
        Resources r = Application.get().getResources();
        switch (halfWind) {
            case NORTH:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_subway_smaller_north_inside);
            case NORTH_EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_subway_smaller_north_east_inside);
            case EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_subway_smaller_east_inside);
            case SOUTH_EAST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_subway_smaller_south_east_inside);
            case SOUTH:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_subway_smaller_south_inside);
            case SOUTH_WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_subway_smaller_south_west_inside);
            case WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_subway_smaller_west_inside);
            case NORTH_WEST:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_subway_smaller_north_west_inside);
            default:
                return BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_subway_smaller_none_inside);
        }
    }

    /**
     * Add a Bitmap for a colored vehicle icon to the cache
     *
     * @param key    Key for the Bitmap to be added, created by createBitmapCacheKey(halfWind, colorResource)
     * @param bitmap Bitmap to be added that is a colored version of the core black vehicle icons
     */
    private void addBitmapToCache(String key, Bitmap bitmap) {
        // Only add if its not already in the cache
        if (getBitmapFromCache(key) == null) {
            mVehicleColoredIconCache.put(key, bitmap);
        }
    }

    /**
     * Get a Bitmap for a colored vehicle icon from the cache
     *
     * @param key Key for the Bitmap, created by createBitmapCacheKey(halfWind, colorResource)
     * @return Bitmap that is a colored version of the core black vehicle icons corresponding to the given key
     */
    private Bitmap getBitmapFromCache(String key) {
        return mVehicleColoredIconCache.get(key);
    }

    /**
     * Creates a key for the vehicle colored icons cache, based on the halfWind (direction) and
     * colorResource
     *
     * @param vehicleType   The type of vehicle based on the GTFS value
     *
     * @param halfWind      an index between 0 and numHalfWinds-1 that can be used to retrieve
     *                      the direction name for that heading (known as "boxing the compass", down to the half-wind
     *                      level).
     * @param colorResource the color resource ID for the schedule deviation
     * @return a String key for this direction and color vehicle bitmap icon
     */
    private String createBitmapCacheKey(int vehicleType, int halfWind, int colorResource) {
        if (!supportedVehicleType(vehicleType)) {
            vehicleType = DEFAULT_VEHICLE_TYPE;
        }

        return String.valueOf(vehicleType) + " " + String.valueOf(halfWind) + " " + String.valueOf(colorResource);
    }

    /**
     * Get the bitmap, using the cache where possible
     * @param vehicleType the vehicle type, as defined by the GTFS value
     * @param colorResource color resource ID for schedule deviation
     * @param halfWind the direction pointed for the icon
     * @return The bitmap representing the vehicle type with the color and direction
     */
    private Bitmap getBitmap(int vehicleType, int colorResource, int halfWind) {
        int color = ContextCompat.getColor(mActivity, colorResource);

        // Use tram icon for cablecar
        if (vehicleType == ObaRoute.TYPE_CABLECAR) {
            vehicleType = ObaRoute.TYPE_TRAM;
        }

        String key = createBitmapCacheKey(vehicleType, halfWind, colorResource);
        Bitmap b = getBitmapFromCache(key);
        if (b == null) {
            // Cache miss - create Bitmap and add to cache
            b = UIUtils.colorBitmap(getIcon(halfWind, vehicleType), color);
            addBitmapToCache(key, b);
        }
        return b;
    }

    @Override
    public void onInfoWindowClick(Marker marker) {
        if (mMarkerData != null) {
            // Show trip details screen for the vehicle associated with this marker
            ObaTripStatus status = mMarkerData.getStatusFromMarker(marker);
            if (status != null) {
                if (mController != null && mController.getFocusedStopId() != null) {
                    new TripDetailsActivity.Builder(mActivity, status.getActiveTripId())
                            .setStopId(mController.getFocusedStopId())
                            .setScrollMode(TripDetailsListFragment.SCROLL_MODE_VEHICLE)
                            .setUpMode("back")
                            .start();
                } else {
                    new TripDetailsActivity.Builder(mActivity, status.getActiveTripId())
                            .setScrollMode(TripDetailsListFragment.SCROLL_MODE_VEHICLE)
                            .setUpMode("back")
                            .start();
                }
            }
        }
    }

    private void startExtrapolationTicking() {
        if (!mExtrapolationTicking) {
            mExtrapolationTicking = true;
            Choreographer.getInstance().postFrameCallback(mFrameCallback);
        }
    }

    private void stopExtrapolationTicking() {
        mExtrapolationTicking = false;
        Choreographer.getInstance().removeFrameCallback(mFrameCallback);
    }

    private void onExtrapolationFrame(long frameTimeNanos) {
        if (!mExtrapolationTicking || mMarkerData == null) {
            mExtrapolationTicking = false;
            return;
        }
        mMarkerData.extrapolatePositions();
        Choreographer.getInstance().postFrameCallback(mFrameCallback);
    }

    private void setupMarkerData() {
        if (mMarkerData == null) {
            mMarkerData = new MarkerData();
        }
    }


    @Override
    public boolean markerClicked(Marker marker) {
        if(mMarkerData == null) return false;
        ObaTripStatus status = mMarkerData.getStatusFromMarker(marker);
        if (status != null) {
            mMarkerData.setSelectedTripId(status.getActiveTripId());
            setupInfoWindow();
            marker.showInfoWindow();
            return true;
        }
        return false;
    }

    @Override
    public void removeMarkerClicked(LatLng latLng) {
        if (mMarkerData != null) {
            mMarkerData.clearSelectedTripId();
        }
    }

    /**
     * Data structures to track what vehicles are currently shown on the map
     */
    class MarkerData {

        /**
         * A cached set of vehicles that are currently shown on the map.  Since onMarkerClick()
         * provides a marker, we need a mapping of that marker to a vehicle/trip.
         * Marker that represents a vehicle is the key, and value is the status for the vehicle.
         */
        private HashMap<Marker, ObaTripStatus> mVehicles;

        /**
         * A cached set of vehicle markers currently shown on the map.  This is needed to
         * add/remove markers from the map.  activeTripId is the key - we can't use vehicleId
         * because we want to show an interpolated position (based on schedule data) for trips
         * without real-time data, and those statuses do not have vehicleIds associated with them,
         * but do have activeTripIds.
         */
        private HashMap<String, Marker> mVehicleMarkers;

        /** The activeTripId of the currently-selected (info-window-open) vehicle, or null. */
        private String mSelectedTripId;

        /** Marker showing the most recent AVL data-received position for the selected vehicle. */
        private Marker mDataReceivedMarker;
        /** Cached label text to skip icon rebuild when unchanged. */
        private String mLastDataReceivedLabel;

        /** Markers showing the 10th and 90th percentile predicted positions. */
        private Marker mQuantile10Marker;
        private Marker mQuantile90Marker;
        private BitmapDescriptor mQuantileSlowIcon;
        private BitmapDescriptor mQuantileFastIcon;
        private float mQuantileAnchorX;
        /** Cached quantile speeds in m/s — only recomputed when gamma params change. */
        private GammaSpeedModel.GammaParams mCachedQuantileParams;
        private double mCachedSpeed10Mps;
        private double mCachedSpeed90Mps;

        private static final int QUANTILE_DOT_RADIUS_DP = 6;
        private static final int QUANTILE_DOT_ALPHA = 0xBB;
        private static final float QUANTILE_MARKER_Z_INDEX = VEHICLE_MARKER_Z_INDEX - 0.5f;
        private static final int QUANTILE_LABEL_SP = 10;
        private static final int QUANTILE_LABEL_GAP_DP = 3;
        private static final String QUANTILE_SLOW_LABEL = "slow est.";
        private static final String QUANTILE_FAST_LABEL = "fast est.";

        /** Reusable Location for quantile interpolation to avoid per-frame allocation. */
        private final Location mQuantileReusableLoc = new Location("quantile");

        // --- Data-received marker label constants ---
        private static final int LABEL_SIZE_SP = 10;
        private static final int LABEL_GAP_DP = 2;
        private static final String DATA_RECEIVED_TITLE = "Most recent data";

        // --- Label rendering state, initialized lazily to avoid per-call allocation ---
        private Paint mLabelTitlePaint;
        private Paint mLabelTimePaint;
        private Paint mLabelBgPaint;
        private Paint.FontMetrics mTitleFontMetrics;
        private Paint.FontMetrics mTimeFontMetrics;
        private float mLabelDensity;

        private static final int INITIAL_HASHMAP_SIZE = 5;

        MarkerData() {
            mVehicles = new HashMap<>(INITIAL_HASHMAP_SIZE);
            mVehicleMarkers = new HashMap<>(INITIAL_HASHMAP_SIZE);
        }

        /**
         * Updates markers for the provided routeIds from the status info from the given
         * ObaTripsForRouteResponse
         *
         * @param routeIds markers representing real-time positions for the provided routeIds will
         *                 be
         *                 added to the map.  The response may contain status info for other routes
         *                 as well - we'll only show markers for the routeIds in this HashSet.
         * @param response response that contains the real-time status info
         */
        synchronized void populate(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
            int added = 0;
            int updated = 0;
            ObaTripDetails[] trips = response.getTrips();

            // Keep track of the activeTripIds that should be shown on the map, so we don't need
            // to iterate again later for this same info
            HashSet<String> activeTripIds = new HashSet<>();

            // Add or move markers for vehicles included in response
            for (ObaTripDetails trip : trips) {
                ObaTripStatus status = trip.getStatus();
                if (status != null) {
                    // Check if this vehicle is running a route we're interested in and isn't CANCELED
                    String activeRoute = response.getTrip(status.getActiveTripId()).getRouteId();
                    if (routeIds.contains(activeRoute) && !Status.CANCELED.equals(status.getStatus())) {
                        Location l = status.getLastKnownLocation();
                        boolean isRealtime = true;

                        if (l == null) {
                            // If a potentially extrapolated location isn't available, use last position
                            l = status.getPosition();
                            isRealtime = false;
                        }
                        if (!status.isPredicted()) {
                            isRealtime = false;
                        }

                        Marker m = mVehicleMarkers.get(status.getActiveTripId());

                        if (m == null) {
                            // New activeTripId
                            addMarkerToMap(l, isRealtime, status, response);
                            added++;
                        } else {
                            updateMarker(m, l, isRealtime, status, response);
                            updated++;
                        }
                        activeTripIds.add(status.getActiveTripId());

                        VehicleState vehicleState = VehicleState.fromTripStatus(status);
                        VehicleTrajectoryTracker trajectoryTracker = VehicleTrajectoryTracker.getInstance();
                        String blockId = null;
                        ObaTrip activeTripObj = response.getTrip(status.getActiveTripId());
                        if (activeTripObj != null) {
                            blockId = activeTripObj.getBlockId();
                        }
                        trajectoryTracker.recordState(status.getActiveTripId(), vehicleState, blockId);

                        String tripId = status.getActiveTripId();
                        String shapeId = activeTripObj != null ? activeTripObj.getShapeId() : null;
                        boolean needSchedule = tripId != null
                                && !trajectoryTracker.isSchedulePendingOrCached(tripId);
                        boolean needShape = tripId != null && shapeId != null
                                && trajectoryTracker.getShape(tripId) == null;
                        if (needSchedule || needShape) {
                            if (needSchedule) {
                                trajectoryTracker.markSchedulePending(tripId);
                            }
                            final Context ctx = Application.get().getApplicationContext();
                            final boolean fetchSchedule = needSchedule;
                            final boolean fetchShape = needShape;
                            new Thread(() -> {
                                try {
                                    if (fetchSchedule) {
                                        ObaTripDetailsResponse detailsResponse =
                                                new ObaTripDetailsRequest.Builder(ctx, tripId)
                                                        .setIncludeSchedule(true)
                                                        .setIncludeStatus(false)
                                                        .setIncludeTrip(false)
                                                        .build()
                                                        .call();
                                        if (detailsResponse != null) {
                                            ObaTripSchedule schedule = detailsResponse.getSchedule();
                                            if (schedule != null) {
                                                trajectoryTracker.putSchedule(tripId, schedule);
                                            }
                                        }
                                    }
                                    if (fetchShape) {
                                        ObaShapeResponse shapeResponse =
                                                ObaShapeRequest.newRequest(ctx, shapeId).call();
                                        if (shapeResponse != null) {
                                            List<Location> points = shapeResponse.getPoints();
                                            if (points != null && !points.isEmpty()) {
                                                trajectoryTracker.putShape(tripId, points);
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "Failed to fetch schedule/shape for " + tripId, e);
                                    if (fetchSchedule) {
                                        trajectoryTracker.clearPending(tripId);
                                    }
                                }
                            }).start();
                        }
                    }
                }
            }
            // Remove markers for any previously added tripIds that aren't in the current response
            int removed = removeInactiveMarkers(activeTripIds);

            // Update the data-received marker to reflect the latest AVL position
            showOrUpdateDataReceivedMarker(mSelectedTripId);

            Log.d(TAG,
                    "Added " + added + ", updated " + updated + ", removed " + removed
                            + ", total vehicle markers = "
                            + mVehicleMarkers.size());
            Log.d(TAG, "Vehicle LRU cache size=" + mVehicleColoredIconCache.size() + ", hits="
                    + mVehicleColoredIconCache.hitCount() + ", misses=" + mVehicleColoredIconCache
                    .missCount());

            Log.d(TAG, String.format("Raw uncolored vehicle LRU cache size=%d, hits=%d, misses=%d",
                    mVehicleUncoloredIcons.size(),
                    mVehicleUncoloredIcons.hitCount(),
                    mVehicleUncoloredIcons.missCount()));
        }

        /**
         * Places a marker on the map for this vehicle, and adds it to our marker HashMap
         *
         * @param l          Location to add the marker at
         * @param isRealtime true if the marker shown indicate real-time info, false if it should indicate schedule
         * @param status     the vehicles status to add to the map
         * @param response   the response which contained the provided status
         */
        private void addMarkerToMap(Location l, boolean isRealtime, ObaTripStatus status,
                                    ObaTripsForRouteResponse response) {

            Marker m = mMap.addMarker(new MarkerOptions()
                    .position(MapHelpV2.makeLatLng(l))
                    .title(status.getVehicleId())
                    .icon(getVehicleIcon(isRealtime, status, response))
            );
            ProprietaryMapHelpV2.setZIndex(m, VEHICLE_MARKER_Z_INDEX);
            mVehicleMarkers.put(status.getActiveTripId(), m);
            mVehicles.put(m, status);
        }

        /**
         * Update an existing marker on the map with the current vehicle status
         *
         * @param m          Marker to update
         * @param l          Location to add the marker at
         * @param isRealtime true if the marker shown indicate real-time info, false if it should
         *                   indicate schedule
         * @param status     real-time status of the vehicle
         * @param response   response containing the provided status
         */
        private void updateMarker(Marker m, Location l, boolean isRealtime, ObaTripStatus status,
                                  ObaTripsForRouteResponse response) {
            boolean showInfo = m.isInfoWindowShown();
            m.setIcon(getVehicleIcon(isRealtime, status, response));
            // Update Hashmap with newest status - needed to show info when tapping on marker
            mVehicles.put(m, status);
            // Only update position from server if extrapolation isn't active —
            // the frame callback handles smooth movement along the polyline
            VehicleTrajectoryTracker tracker = VehicleTrajectoryTracker.getInstance();
            String tripId = status.getActiveTripId();
            if (tripId == null || tracker.getShape(tripId) == null
                    || tracker.getEstimatedSpeed(tripId) == null) {
                Location markerLoc = MapHelpV2.makeLocation(m.getPosition());
                if (l.distanceTo(markerLoc) < MAX_VEHICLE_ANIMATION_DISTANCE) {
                    AnimationUtil.animateMarkerTo(m, MapHelpV2.makeLatLng(l));
                } else {
                    m.setPosition(MapHelpV2.makeLatLng(l));
                }
            }
            // If the info window was shown, make sure its open (changing the icon could have closed it)
            if (showInfo) {
                m.showInfoWindow();
            }
        }

        /**
         * Removes any markers that don't currently represent active vehicles running a route
         *
         * @param activeTripIds a set of active tripIds that are currently running the routes.  Any
         *                      markers for tripIds that aren't in this set will be removed
         *                      from the map.
         * @return the number of removed markers
         */
        private int removeInactiveMarkers(HashSet<String> activeTripIds) {
            int removed = 0;
            // Loop using an Iterator, since per Oracle Iterator.remove() is the only safe way
            // to remove an item from a Collection during iteration:
            // http://docs.oracle.com/javase/tutorial/collections/interfaces/collection.html
            try {
                Iterator<Map.Entry<String, Marker>> iterator = mVehicleMarkers.entrySet()
                        .iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Marker> entry = iterator.next();
                    String tripId = entry.getKey();
                    Marker m = entry.getValue();
                    if (!activeTripIds.contains(tripId)) {
                        cancelAnimation(m);
                        entry.getValue().remove();
                        mVehicles.remove(m);
                        iterator.remove();
                        removed++;
                    }
                }
            } catch (UnsupportedOperationException e) {
                Log.w(TAG, "Problem removing vehicle from HashMap using iterator: " + e);
                //The platform apparently didn't like the "efficient" way to do this, so we'll just
                //loop through a copy and remove what we don't want from the original
                HashMap<String, Marker> copy = new HashMap<>(mVehicleMarkers);
                for (Map.Entry<String, Marker> entry : copy.entrySet()) {
                    String tripId = entry.getKey();
                    Marker m = entry.getValue();
                    if (!activeTripIds.contains(tripId)) {
                        cancelAnimation(m);
                        entry.getValue().remove();
                        mVehicles.remove(m);
                        mVehicleMarkers.remove(tripId);
                        removed++;
                    }
                }
            }
            return removed;
        }

        /**
         * Returns an icon for the vehicle that should be shown on the map
         *
         * @param isRealtime true if the marker shown indicate real-time info, false if it should
         *                   indicate schedule
         * @param status     the vehicles status to add to the map
         * @param response   the response which contained the provided status
         * @return an icon for the vehicle that should be shown on the map
         */
        private BitmapDescriptor getVehicleIcon(boolean isRealtime, ObaTripStatus status,
                                                ObaTripsForRouteResponse response) {
            // If another vehicle is selected, show this one as a small dot
            if (mSelectedTripId != null
                    && !mSelectedTripId.equals(status.getActiveTripId())) {
                return getDotIcon(isRealtime, status);
            }

            String routeId = response.getTrip(status.getActiveTripId()).getRouteId();
            ObaRoute route = response.getRoute(routeId);
            int vehicleType = route.getType();
            int colorResource = getDeviationColorResource(isRealtime, status);
            double direction = MathUtils.toDirection(status.getOrientation());
            int halfWind = MathUtils.getHalfWindIndex((float) direction, NUM_DIRECTIONS - 1);

            Bitmap b = getBitmap(vehicleType, colorResource, halfWind);
            return BitmapDescriptorFactory.fromBitmap(b);
        }

        private static final int DOT_RADIUS_DP = 5;
        private LruCache<Integer, BitmapDescriptor> mDotIconCache;

        private BitmapDescriptor getDotIcon(boolean isRealtime, ObaTripStatus status) {
            int colorResource = getDeviationColorResource(isRealtime, status);
            if (mDotIconCache == null) {
                mDotIconCache = new LruCache<>(8);
            }
            BitmapDescriptor cached = mDotIconCache.get(colorResource);
            if (cached != null) return cached;

            float density = mActivity.getResources().getDisplayMetrics().density;
            int radiusPx = (int) (DOT_RADIUS_DP * density);
            int strokePx = (int) (1.5f * density);
            int size = (radiusPx + strokePx) * 2;
            float cx = size / 2f;
            float cy = size / 2f;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, radiusPx + strokePx, paint);
            paint.setColor(ContextCompat.getColor(mActivity, colorResource));
            c.drawCircle(cx, cy, radiusPx, paint);
            BitmapDescriptor descriptor = BitmapDescriptorFactory.fromBitmap(bmp);
            mDotIconCache.put(colorResource, descriptor);
            return descriptor;
        }

        private static final int ICON_TRANSITION_MS = 250;
        private final HashMap<Marker, ValueAnimator> mRunningAnimators = new HashMap<>();

        /**
         * Sets the selected trip and collapses all other vehicle markers to small dots.
         */
        synchronized void setSelectedTripId(String tripId) {
            if (tripId != null && tripId.equals(mSelectedTripId)) return;
            String previousTripId = mSelectedTripId;
            mSelectedTripId = tripId;
            // Immediately restore the newly selected marker to its full icon
            if (tripId != null) {
                restoreMarkerIcon(mVehicleMarkers.get(tripId));
            }
            removeDataReceivedMarker();
            removeQuantileMarkers();
            showOrUpdateDataReceivedMarker(tripId);
            createQuantileMarkers(tripId);
            animateChangedIcons(previousTripId);
        }

        /**
         * Clears the selection, restoring all markers to full vehicle icons.
         */
        synchronized void clearSelectedTripId() {
            if (mSelectedTripId == null) return;
            String previousTripId = mSelectedTripId;
            mSelectedTripId = null;
            removeDataReceivedMarker();
            removeQuantileMarkers();
            animateChangedIcons(previousTripId);
        }

        private void restoreMarkerIcon(Marker marker) {
            if (marker == null) return;
            cancelAnimation(marker);
            ObaTripStatus status = mVehicles.get(marker);
            if (status == null) return;
            marker.setAlpha(1f);
            marker.setIcon(getVehicleIcon(
                    isLocationRealtime(status), status, mLastResponse));
        }

        // --- Data-received marker lifecycle ---

        private void showOrUpdateDataReceivedMarker(String tripId) {
            if (tripId == null || mLastResponse == null) return;

            VehicleTrajectoryTracker tracker = VehicleTrajectoryTracker.getInstance();
            List<VehicleHistoryEntry> history = tracker.getHistoryReadOnly(tripId);
            if (history == null || history.isEmpty()) return;

            VehicleHistoryEntry latest = history.get(history.size() - 1);
            Location pos = latest.getPosition();
            if (pos == null) return;

            Marker vehicleMarker = mVehicleMarkers.get(tripId);
            if (vehicleMarker == null) return;
            ObaTripStatus status = mVehicles.get(vehicleMarker);
            if (status == null) return;

            LatLng latLng = MapHelpV2.makeLatLng(pos);
            String label = formatElapsedTime(latest.getLastLocationUpdateTime());

            if (mDataReceivedMarker != null) {
                mDataReceivedMarker.setPosition(latLng);
                // Skip icon rebuild if label text hasn't changed
                if (!label.equals(mLastDataReceivedLabel)) {
                    mLastDataReceivedLabel = label;
                    mDataReceivedMarker.setIcon(createLabeledVehicleIcon(status, label));
                }
            } else {
                mLastDataReceivedLabel = label;
                mDataReceivedMarker = mMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .icon(createLabeledVehicleIcon(status, label))
                        .anchor(0.5f, 1.0f)
                        .zIndex(VEHICLE_MARKER_Z_INDEX + 1)
                );
            }
        }

        private void removeDataReceivedMarker() {
            if (mDataReceivedMarker != null) {
                mDataReceivedMarker.remove();
                mDataReceivedMarker = null;
                mLastDataReceivedLabel = null;
            }
        }

        // --- Quantile marker lifecycle ---

        /**
         * Creates a quantile marker icon: a dot on the left with a text bubble to its right.
         * Returns the anchor X fraction (dot center / total width) via the first element of
         * outAnchorX if non-null, so callers can avoid recomputing layout metrics.
         *
         */
        private BitmapDescriptor createQuantileLabelIcon(ObaTripStatus status, String label,
                                                          float[] outAnchorX) {
            int colorResource = getDeviationColorResource(isLocationRealtime(status), status);
            int baseColor = ContextCompat.getColor(mActivity, colorResource);
            int dotColor = (baseColor & 0x00FFFFFF) | (QUANTILE_DOT_ALPHA << 24);

            float d = mActivity.getResources().getDisplayMetrics().density;
            int dotRadius = (int) (QUANTILE_DOT_RADIUS_DP * d);
            int dotStroke = (int) (1.5f * d);
            int dotSize = (dotRadius + dotStroke) * 2;
            int gap = (int) (QUANTILE_LABEL_GAP_DP * d);

            // Text measurement
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(QUANTILE_LABEL_SP * d);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setColor(0xFF616161);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textWidth = textPaint.measureText(label);
            int textHeight = (int) Math.ceil(fm.descent - fm.ascent);

            float padLeft = 4 * d;
            float padRight = 6 * d;
            float padY = 2 * d;
            float pointerWidthDp = 10 * d;
            int bubbleWidth = (int) (pointerWidthDp + padLeft + textWidth + padRight);
            int bubbleHeight = (int) (textHeight + padY * 2);
            float cornerRadius = 3 * d;

            int totalWidth = dotSize + gap + bubbleWidth;
            int totalHeight = Math.max(dotSize, bubbleHeight);

            Bitmap bmp = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);

            float dotCx = dotSize / 2f;
            float bubbleLeft = dotSize + gap;

            // Draw dot
            float dotCy = totalHeight / 2f;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0x99FFFFFF);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(dotCx, dotCy, dotRadius + dotStroke, paint);
            paint.setColor(dotColor);
            c.drawCircle(dotCx, dotCy, dotRadius, paint);

            float bubbleTop = (totalHeight - bubbleHeight) / 2f;
            float bodyLeft = drawPointerBubble(c, bubbleLeft, bubbleTop,
                    bubbleWidth, bubbleHeight, cornerRadius, d);

            float textX = bodyLeft + padLeft;
            float textY = bubbleTop + padY - fm.ascent;
            c.drawText(label, textX, textY, textPaint);

            if (outAnchorX != null && outAnchorX.length > 0) {
                outAnchorX[0] = dotCx / totalWidth;
            }
            return BitmapDescriptorFactory.fromBitmap(bmp);
        }

        /**
         * Draws a 5-sided pointer bubble: a rectangle with the left side replaced
         * by two edges meeting at a pointed tip.
         *
         * @return the X coordinate of the body's left edge (after the pointer),
         *         for positioning text content
         */
        private float drawPointerBubble(Canvas c, float left, float top,
                                         float width, float height,
                                         float cornerRadius, float density) {
            float pointerWidth = 10 * density;
            float bodyLeft = left + pointerWidth;
            float right = left + width;
            float bottom = top + height;
            float midY = top + height / 2f;
            float r = cornerRadius;

            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(left, midY);                   // pointer tip
            path.lineTo(bodyLeft, top);                 // up to top-left
            path.lineTo(right - r, top);                // across top
            path.quadTo(right, top, right, top + r);    // top-right corner
            path.lineTo(right, bottom - r);             // down right side
            path.quadTo(right, bottom, right - r, bottom); // bottom-right corner
            path.lineTo(bodyLeft, bottom);              // across bottom
            path.close();                               // back to pointer tip

            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(0xDDFFFFFF);
            bgPaint.setStyle(Paint.Style.FILL);
            c.drawPath(path, bgPaint);

            return bodyLeft;
        }

        private static final double LABEL_DEADZONE_DEG = 20.0;

        /** Normalizes an angle to [0, 360). Java's % preserves sign, so this avoids negative results. */
        private double normalizeDeg(double angle) {
            return ((angle % 360) + 360) % 360;
        }

        /**
         * Converts a vehicle heading to the compass direction the label visually
         * extends toward on the map (the "label azimuth").
         * Picks heading + 90 or heading − 90, whichever places the label in the
         * [0°, 180°] sector (the right/eastern half), so text is always upright.
         */
        private double headingToLabelAzimuth(double heading) {
            double az = normalizeDeg(heading - 90);
            if (az > 180) {
                az = normalizeDeg(heading + 90);
            }
            return az;
        }

        /**
         * Computes the label azimuth for the given heading, clamped away from
         * 0° (north/up) and 180° (south/down) deadzones.
         * The result is always in [DEADZONE, 180 − DEADZONE], so the label
         * is always on the right side and text is always upright.
         */
        private double clampedLabelAzimuth(double heading) {
            double labelAz = headingToLabelAzimuth(heading);
            labelAz = clampAzimuthAwayFrom(labelAz, 0);
            labelAz = clampAzimuthAwayFrom(labelAz, 180);
            return labelAz;
        }

        /**
         * Clamps a degree value [0, 360) away from center by ±LABEL_DEADZONE_DEG,
         * with circular wraparound.
         */
        private double clampAzimuthAwayFrom(double value, double center) {
            double delta = value - center;
            if (delta > 180) delta -= 360;
            if (delta < -180) delta += 360;
            if (delta >= -LABEL_DEADZONE_DEG && delta < 0) {
                return normalizeDeg(center - LABEL_DEADZONE_DEG);
            }
            if (delta >= 0 && delta < LABEL_DEADZONE_DEG) {
                return normalizeDeg(center + LABEL_DEADZONE_DEG);
            }
            return value;
        }

        private void createQuantileMarkers(String tripId) {
            if (tripId == null || mLastResponse == null) return;
            Marker vehicleMarker = mVehicleMarkers.get(tripId);
            if (vehicleMarker == null) return;
            ObaTripStatus status = mVehicles.get(vehicleMarker);
            if (status == null) return;

            float[] anchorOut = new float[1];
            mQuantileSlowIcon = createQuantileLabelIcon(status, QUANTILE_SLOW_LABEL, anchorOut);
            mQuantileAnchorX = anchorOut[0];
            mQuantileFastIcon = createQuantileLabelIcon(status, QUANTILE_FAST_LABEL, null);
            mCachedQuantileParams = null;

            LatLng pos = vehicleMarker.getPosition();
            mQuantile10Marker = addFlatQuantileMarker(pos, mQuantileSlowIcon);
            mQuantile90Marker = addFlatQuantileMarker(pos, mQuantileFastIcon);
        }

        private void removeQuantileMarkers() {
            if (mQuantile10Marker != null) {
                mQuantile10Marker.remove();
                mQuantile10Marker = null;
            }
            if (mQuantile90Marker != null) {
                mQuantile90Marker.remove();
                mQuantile90Marker = null;
            }
            mQuantileSlowIcon = null;
            mQuantileFastIcon = null;
            mCachedQuantileParams = null;
        }

        private Marker addFlatQuantileMarker(LatLng pos, BitmapDescriptor icon) {
            return mMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .icon(icon)
                    .anchor(mQuantileAnchorX, 0.5f)
                    .flat(true)
                    .zIndex(QUANTILE_MARKER_Z_INDEX)
                    .visible(false)
            );
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

        private void ensureLabelPaintsInitialized() {
            if (mLabelTitlePaint != null) return;
            mLabelDensity = mActivity.getResources().getDisplayMetrics().density;

            mLabelTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mLabelTitlePaint.setTextSize(LABEL_SIZE_SP * mLabelDensity);
            mLabelTitlePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            mLabelTitlePaint.setColor(0xFF616161);
            mTitleFontMetrics = mLabelTitlePaint.getFontMetrics();

            mLabelTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mLabelTimePaint.setTextSize(LABEL_SIZE_SP * mLabelDensity);
            mLabelTimePaint.setTypeface(android.graphics.Typeface.DEFAULT);
            mLabelTimePaint.setColor(0xFF757575);
            mTimeFontMetrics = mLabelTimePaint.getFontMetrics();

            mLabelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mLabelBgPaint.setColor(0xDDFFFFFF);
            mLabelBgPaint.setStyle(Paint.Style.FILL);
        }

        private BitmapDescriptor createLabeledVehicleIcon(ObaTripStatus status,
                                                           String timeLine) {
            String routeId = mLastResponse.getTrip(status.getActiveTripId()).getRouteId();
            ObaRoute route = mLastResponse.getRoute(routeId);
            Bitmap vehicleBmp = getBitmap(route.getType(),
                    R.color.stop_info_scheduled_time, NO_DIRECTION);

            ensureLabelPaintsInitialized();
            float d = mLabelDensity;
            float padX = 3 * d;
            float padY = 1.5f * d;
            float lineGap = d;
            int gapPx = (int) (LABEL_GAP_DP * d);

            // Measure text widths (heights come from cached font metrics)
            float titleWidth = mLabelTitlePaint.measureText(DATA_RECEIVED_TITLE);
            int titleHeight = (int) Math.ceil(mTitleFontMetrics.descent - mTitleFontMetrics.ascent);

            float timeWidth = mLabelTimePaint.measureText(timeLine);
            int timeHeight = (int) Math.ceil(mTimeFontMetrics.descent - mTimeFontMetrics.ascent);

            // Layout
            float maxTextWidth = Math.max(titleWidth, timeWidth);
            int labelBlockHeight = (int) (padY + titleHeight + lineGap + timeHeight + padY);
            int totalWidth = Math.max(vehicleBmp.getWidth(), (int) (maxTextWidth + padX * 2));
            int totalHeight = labelBlockHeight + gapPx + vehicleBmp.getHeight();

            // Draw
            Bitmap bmp = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);

            float labelLeft = (totalWidth - maxTextWidth) / 2f - padX;
            float labelRight = (totalWidth + maxTextWidth) / 2f + padX;
            c.drawRoundRect(labelLeft, 0, labelRight, labelBlockHeight,
                    3 * d, 3 * d, mLabelBgPaint);

            float titleX = (totalWidth - titleWidth) / 2f;
            c.drawText(DATA_RECEIVED_TITLE, titleX,
                    padY - mTitleFontMetrics.ascent, mLabelTitlePaint);

            float timeX = (totalWidth - timeWidth) / 2f;
            c.drawText(timeLine, timeX,
                    padY + titleHeight + lineGap - mTimeFontMetrics.ascent, mLabelTimePaint);

            float iconLeft = (totalWidth - vehicleBmp.getWidth()) / 2f;
            c.drawBitmap(vehicleBmp, iconLeft, labelBlockHeight + gapPx, null);

            return BitmapDescriptorFactory.fromBitmap(bmp);
        }

        /**
         * Animates only the markers whose icon state actually changed.
         * @param previousTripId the previously selected trip, or null if none
         */
        private void animateChangedIcons(String previousTripId) {
            if (mLastResponse == null) return;
            for (Map.Entry<String, Marker> entry : mVehicleMarkers.entrySet()) {
                String tripId = entry.getKey();
                // Skip the currently selected marker — already handled
                if (mSelectedTripId != null && mSelectedTripId.equals(tripId)) continue;

                boolean wasDot = previousTripId != null && !previousTripId.equals(tripId);
                boolean shouldBeDot = mSelectedTripId != null && !mSelectedTripId.equals(tripId);
                if (wasDot == shouldBeDot) continue;

                Marker m = entry.getValue();
                ObaTripStatus status = mVehicles.get(m);
                if (status == null) continue;
                boolean isRealtime = isLocationRealtime(status);
                BitmapDescriptor newIcon = getVehicleIcon(isRealtime, status, mLastResponse);
                animateIconTransition(m, newIcon);
            }
        }

        private void animateIconTransition(Marker marker, BitmapDescriptor newIcon) {
            cancelAnimation(marker);
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(ICON_TRANSITION_MS);
            anim.setInterpolator(new AccelerateDecelerateInterpolator());
            final boolean[] swapped = {false};
            anim.addUpdateListener(animation -> {
                float f = (float) animation.getAnimatedValue();
                if (f < 0.5f) {
                    marker.setAlpha(1f - f * 2f);
                } else {
                    if (!swapped[0]) {
                        marker.setIcon(newIcon);
                        swapped[0] = true;
                    }
                    marker.setAlpha((f - 0.5f) * 2f);
                }
            });
            mRunningAnimators.put(marker, anim);
            anim.start();
        }

        private void cancelAnimation(Marker marker) {
            ValueAnimator existing = mRunningAnimators.remove(marker);
            if (existing != null) {
                existing.cancel();
                marker.setAlpha(1f);
            }
        }

        synchronized ObaTripStatus getStatusFromMarker(Marker marker) {
            return mVehicles.get(marker);
        }

        private void removeMarkersFromMap() {
            for (Map.Entry<String, Marker> entry : mVehicleMarkers.entrySet()) {
                entry.getValue().remove();
            }
        }

        /** Reusable Location to avoid per-frame allocation in extrapolation. */
        private final Location mReusableLocation = new Location("extrapolated");

        /**
         * Extrapolates vehicle positions using trajectory data and moves markers
         * along their route polylines. Called every frame via Choreographer.
         */
        void extrapolatePositions() {
            if (mVehicleMarkers == null || mVehicleMarkers.isEmpty()) return;

            VehicleTrajectoryTracker tracker = VehicleTrajectoryTracker.getInstance();
            long now = System.currentTimeMillis();

            // Capture selected-trip data for quantile markers
            GammaSpeedModel.GammaParams selectedParams = null;
            List<Location> selectedShape = null;
            double[] selectedCumDist = null;
            List<VehicleHistoryEntry> selectedHistory = null;

            for (Map.Entry<String, Marker> entry : mVehicleMarkers.entrySet()) {
                String tripId = entry.getKey();
                Marker marker = entry.getValue();

                List<Location> shape = tracker.getShape(tripId);
                double[] cumDist = tracker.getShapeCumulativeDistances(tripId);
                if (shape == null || shape.isEmpty() || cumDist == null) continue;

                List<VehicleHistoryEntry> history = tracker.getHistoryReadOnly(tripId);
                Double speed = tracker.getEstimatedSpeed(tripId);

                // Capture data for quantile markers regardless of speed availability
                if (tripId.equals(mSelectedTripId)) {
                    selectedParams = tracker.getLastGammaParams();
                    selectedShape = shape;
                    selectedCumDist = cumDist;
                    selectedHistory = history;
                }

                if (history == null || history.isEmpty() || speed == null) continue;

                Double extrapolatedDist = DistanceExtrapolator.extrapolateDistance(
                        history, speed, now);
                if (extrapolatedDist == null) continue;

                if (!DistanceExtrapolator.interpolateAlongPolyline(
                        shape, cumDist, extrapolatedDist, mReusableLocation)) {
                    continue;
                }

                marker.setPosition(new LatLng(
                        mReusableLocation.getLatitude(), mReusableLocation.getLongitude()));
            }

            // Update quantile markers for the selected vehicle
            updateQuantileMarkers(selectedParams, selectedShape, selectedCumDist,
                    selectedHistory, now);
        }

        private void hideQuantileMarkers() {
            if (mQuantile10Marker != null) mQuantile10Marker.setVisible(false);
            if (mQuantile90Marker != null) mQuantile90Marker.setVisible(false);
        }

        private void updateQuantileMarkers(GammaSpeedModel.GammaParams params,
                                            List<Location> shape, double[] cumDist,
                                            List<VehicleHistoryEntry> history, long now) {
            if (mQuantile10Marker == null || mQuantile90Marker == null) return;

            if (params == null || shape == null || cumDist == null
                    || history == null || history.isEmpty()) {
                hideQuantileMarkers();
                return;
            }

            VehicleHistoryEntry newest = DistanceExtrapolator.findNewestValidEntry(history);
            if (newest == null) {
                hideQuantileMarkers();
                return;
            }

            Double lastDist = newest.getBestDistanceAlongTrip();
            long lastTime = newest.getLastLocationUpdateTime();
            if (lastDist == null || lastTime <= 0) {
                hideQuantileMarkers();
                return;
            }

            double dtSec = (now - lastTime) / 1000.0;
            if (dtSec < 0.5) {
                hideQuantileMarkers();
                return;
            }

            // Cache quantile speeds — only recompute when gamma params change
            if (params != mCachedQuantileParams) {
                mCachedQuantileParams = params;
                mCachedSpeed10Mps = GammaSpeedModel.quantile(0.10, params)
                        / GammaSpeedModel.MPS_TO_MPH;
                mCachedSpeed90Mps = GammaSpeedModel.quantile(0.90, params)
                        / GammaSpeedModel.MPS_TO_MPH;
            }

            double dist10 = lastDist + mCachedSpeed10Mps * dtSec;
            double dist90 = lastDist + mCachedSpeed90Mps * dtSec;

            updateSingleQuantileMarker(mQuantile10Marker, dist10, shape, cumDist);
            updateSingleQuantileMarker(mQuantile90Marker, dist90, shape, cumDist);
        }

        /**
         * Updates a single quantile marker's position and rotation.
         * Label azimuth is always in [0°, 180°] (right side), so text is
         * always upright — no icon flipping needed.
         */
        private void updateSingleQuantileMarker(Marker marker, double distance,
                                                 List<Location> shape, double[] cumDist) {
            if (!DistanceExtrapolator.interpolateAlongPolyline(
                    shape, cumDist, distance, mQuantileReusableLoc)) {
                marker.setVisible(false);
                return;
            }
            marker.setPosition(new LatLng(
                    mQuantileReusableLoc.getLatitude(),
                    mQuantileReusableLoc.getLongitude()));
            double heading = DistanceExtrapolator.headingAlongPolyline(
                    shape, cumDist, distance);
            if (!Double.isNaN(heading)) {
                double labelAz = clampedLabelAzimuth(heading);
                marker.setRotation((float) (labelAz - 90.0));
            }
            marker.setVisible(true);
        }

        /**
         * Clears any stop markers from the map
         */
        synchronized void clear() {
            removeDataReceivedMarker();
            removeQuantileMarkers();
            if (mVehicleMarkers != null) {
                // Clear all markers from the map
                removeMarkersFromMap();

                // Clear the data structures
                mVehicleMarkers.clear();
                mVehicleMarkers = null;
            }
            if (mVehicles != null) {
                mVehicles.clear();
                mVehicles = null;
            }
        }

        synchronized int size() {
            return mVehicleMarkers.size();
        }
    }

    /**
     * Returns true if there is real-time location information for the given status, false if there
     * is not
     *
     * @param status The trip status information that includes location information
     * @return true if there is real-time location information for the given status, false if there
     * is not
     */
    protected static boolean isLocationRealtime(ObaTripStatus status) {
        return status.getLastKnownLocation() != null && status.isPredicted();
    }

    /**
     * Returns the color resource for a vehicle's schedule deviation status.
     */
    static int getDeviationColorResource(boolean isRealtime, ObaTripStatus status) {
        if (isRealtime) {
            long deviationMin = TimeUnit.SECONDS.toMinutes(status.getScheduleDeviation());
            return ArrivalInfoUtils.computeColorFromDeviation(deviationMin);
        }
        return R.color.stop_info_scheduled_time;
    }

    /**
     * Adapter to show custom info windows when tapping on vehicle markers
     */
    class CustomInfoWindowAdapter implements GoogleMap.InfoWindowAdapter {

        private LayoutInflater mInflater;

        private Context mContext;

        private Marker mCurrentFocusVehicleMarker;

        public CustomInfoWindowAdapter(Context context) {
            this.mInflater = LayoutInflater.from(context);
            this.mContext = context;
        }

        @Override
        public View getInfoWindow(Marker marker) {
            return null;
        }

        @Override
        public View getInfoContents(Marker marker) {
            if (mMarkerData == null) {
                // Markers haven't been initialized yet - use default rendering
                return null;
            }
            ObaTripStatus status = mMarkerData.getStatusFromMarker(marker);
            if (status == null) {
                // Marker that the user tapped on wasn't a vehicle - use default rendering
                mCurrentFocusVehicleMarker = null;
                return null;
            }
            mCurrentFocusVehicleMarker = marker;
            View view = mInflater.inflate(R.layout.vehicle_info_window, null);
            Resources r = mContext.getResources();
            TextView routeView = (TextView) view.findViewById(R.id.route_and_destination);
            TextView statusView = (TextView) view.findViewById(R.id.status);
            ImageView moreView = (ImageView) view.findViewById(R.id.trip_more_info);
            moreView.setColorFilter(r.getColor(R.color.switch_thumb_normal_material_dark));
            ViewGroup occupancyView = view.findViewById(R.id.occupancy);

            // Get route/trip details
            ObaTrip trip = mLastResponse.getTrip(status.getActiveTripId());
            ObaRoute route = mLastResponse.getRoute(trip.getRouteId());

            routeView.setText(UIUtils.getRouteDisplayName(route) + " " +
                    mContext.getString(R.string.trip_info_separator) + " " + UIUtils
                    .formatDisplayText(trip.getHeadsign()));

            boolean isRealtime = isLocationRealtime(status);

            statusView.setBackgroundResource(R.drawable.round_corners_style_b_status);
            GradientDrawable d = (GradientDrawable) statusView.getBackground();

            // Set padding on status view
            int pSides = UIUtils.dpToPixels(mContext, 5);
            int pTopBottom = UIUtils.dpToPixels(mContext, 2);

            int statusColor = getDeviationColorResource(isRealtime, status);

            if (isRealtime) {
                long deviationMin = TimeUnit.SECONDS.toMinutes(status.getScheduleDeviation());
                String statusString = ArrivalInfoUtils.computeArrivalLabelFromDelay(r, deviationMin);
                statusView.setText(statusString);
                d.setColor(r.getColor(statusColor));
                statusView.setPadding(pSides, pTopBottom, pSides, pTopBottom);
            } else {
                // Scheduled info
                statusView.setText(r.getString(R.string.stop_info_scheduled));
                d.setColor(r.getColor(statusColor));
                statusView.setPadding(pSides, pTopBottom, pSides, pTopBottom);

                // Hide occupancy by setting null value
                UIUtils.setOccupancyVisibilityAndColor(occupancyView, null, OccupancyState.HISTORICAL);
                UIUtils.setOccupancyContentDescription(occupancyView, null, OccupancyState.HISTORICAL);

                return view;
            }

            if (status.getOccupancyStatus() != null) {
                // Real-time occupancy data
                UIUtils.setOccupancyVisibilityAndColor(occupancyView, status.getOccupancyStatus(), OccupancyState.REALTIME);
                UIUtils.setOccupancyContentDescription(occupancyView, status.getOccupancyStatus(), OccupancyState.REALTIME);
            } else {
                // Hide occupancy by setting null value
                UIUtils.setOccupancyVisibilityAndColor(occupancyView, null, OccupancyState.REALTIME);
                UIUtils.setOccupancyContentDescription(occupancyView, null, OccupancyState.REALTIME);
            }

            return view;
        }


    }
}