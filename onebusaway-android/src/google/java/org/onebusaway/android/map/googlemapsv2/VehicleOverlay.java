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
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;

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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A map overlay that shows vehicle positions on the map
 */
public class VehicleOverlay implements MarkerListeners  {

    interface Controller {
        String getFocusedStopId();
        void onVehicleSelected(String tripId);
        void onVehicleDeselected();
    }

    private static final String TAG = "VehicleOverlay";

    private GoogleMap mMap;

    private MarkerData mMarkerData;

    private final Activity mActivity;

    private ObaTripsForRouteResponse mLastResponse;

    private View mVehicleInfoCard;
    private TextView mCardRouteView;
    private TextView mCardStatusView;
    private ViewGroup mCardOccupancyView;
    private String mCardTripId;

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

    /**
     * Directional icon resource IDs indexed by [vehicleType][halfWind].
     * Last entry in each row is the "no direction" fallback.
     */
    private static final int[][] VEHICLE_ICON_RES = {
            // TYPE_TRAM (0)
            {
                    R.drawable.ic_marker_with_tram_smaller_north_inside,
                    R.drawable.ic_marker_with_tram_smaller_north_east_inside,
                    R.drawable.ic_marker_with_tram_smaller_east_inside,
                    R.drawable.ic_marker_with_tram_smaller_south_east_inside,
                    R.drawable.ic_marker_with_tram_smaller_south_inside,
                    R.drawable.ic_marker_with_tram_smaller_south_west_inside,
                    R.drawable.ic_marker_with_tram_smaller_west_inside,
                    R.drawable.ic_marker_with_tram_smaller_north_west_inside,
                    R.drawable.ic_marker_with_tram_smaller_none_inside,
            },
            // TYPE_SUBWAY (1)
            {
                    R.drawable.ic_marker_with_subway_smaller_north_inside,
                    R.drawable.ic_marker_with_subway_smaller_north_east_inside,
                    R.drawable.ic_marker_with_subway_smaller_east_inside,
                    R.drawable.ic_marker_with_subway_smaller_south_east_inside,
                    R.drawable.ic_marker_with_subway_smaller_south_inside,
                    R.drawable.ic_marker_with_subway_smaller_south_west_inside,
                    R.drawable.ic_marker_with_subway_smaller_west_inside,
                    R.drawable.ic_marker_with_subway_smaller_north_west_inside,
                    R.drawable.ic_marker_with_subway_smaller_none_inside,
            },
            // TYPE_RAIL (2)
            {
                    R.drawable.ic_marker_with_train_smaller_north_inside,
                    R.drawable.ic_marker_with_train_smaller_north_east_inside,
                    R.drawable.ic_marker_with_train_smaller_east_inside,
                    R.drawable.ic_marker_with_train_smaller_south_east_inside,
                    R.drawable.ic_marker_with_train_smaller_south_inside,
                    R.drawable.ic_marker_with_train_smaller_south_west_inside,
                    R.drawable.ic_marker_with_train_smaller_west_inside,
                    R.drawable.ic_marker_with_train_smaller_north_west_inside,
                    R.drawable.ic_marker_with_train_smaller_none_inside,
            },
            // TYPE_BUS (3)
            {
                    R.drawable.ic_marker_with_bus_smaller_north_inside,
                    R.drawable.ic_marker_with_bus_smaller_north_east_inside,
                    R.drawable.ic_marker_with_bus_smaller_east_inside,
                    R.drawable.ic_marker_with_bus_smaller_south_east_inside,
                    R.drawable.ic_marker_with_bus_smaller_south_inside,
                    R.drawable.ic_marker_with_bus_smaller_south_west_inside,
                    R.drawable.ic_marker_with_bus_smaller_west_inside,
                    R.drawable.ic_marker_with_bus_smaller_north_west_inside,
                    R.drawable.ic_marker_with_bus_smaller_none_inside,
            },
            // TYPE_FERRY (4)
            {
                    R.drawable.ic_marker_with_boat_smaller_north_inside,
                    R.drawable.ic_marker_with_boat_smaller_north_east_inside,
                    R.drawable.ic_marker_with_boat_smaller_east_inside,
                    R.drawable.ic_marker_with_boat_smaller_south_east_inside,
                    R.drawable.ic_marker_with_boat_smaller_south_inside,
                    R.drawable.ic_marker_with_boat_smaller_south_west_inside,
                    R.drawable.ic_marker_with_boat_smaller_west_inside,
                    R.drawable.ic_marker_with_boat_smaller_north_west_inside,
                    R.drawable.ic_marker_with_boat_smaller_none_inside,
            },
    };

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
        hideVehicleInfoCard();
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
            int[] res = VEHICLE_ICON_RES[vehicleType];
            int idx = (halfWind >= 0 && halfWind < res.length - 1) ? halfWind : res.length - 1;
            b = BitmapFactory.decodeResource(Application.get().getResources(), res[idx]);
        }

        mVehicleUncoloredIcons.put(cacheKey, b);

        return b;
    }

    private static boolean supportedVehicleType(int vehicleType) {
        return vehicleType >= 0 && vehicleType < VEHICLE_ICON_RES.length;
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

    private void initVehicleInfoCard() {
        mVehicleInfoCard = mActivity.findViewById(R.id.vehicleInfoCard);
        if (mVehicleInfoCard == null) return;
        mCardRouteView = mVehicleInfoCard.findViewById(R.id.route_and_destination);
        mCardStatusView = mVehicleInfoCard.findViewById(R.id.status);
        mCardOccupancyView = mVehicleInfoCard.findViewById(R.id.occupancy);
        ImageView moreView = mVehicleInfoCard.findViewById(R.id.trip_more_info);
        moreView.setColorFilter(
                mActivity.getResources().getColor(R.color.switch_thumb_normal_material_dark));
        mVehicleInfoCard.setOnClickListener(v -> onVehicleInfoCardClick());
    }

    private void onVehicleInfoCardClick() {
        if (mCardTripId == null) return;
        TripDetailsActivity.Builder builder =
                new TripDetailsActivity.Builder(mActivity, mCardTripId)
                        .setScrollMode(TripDetailsListFragment.SCROLL_MODE_VEHICLE)
                        .setUpMode("back");
        if (mController != null && mController.getFocusedStopId() != null) {
            builder.setStopId(mController.getFocusedStopId());
        }
        builder.start();
    }

    private void showVehicleInfoCard(ObaTripStatus status) {
        if (mVehicleInfoCard == null) initVehicleInfoCard();
        if (mVehicleInfoCard == null || mLastResponse == null) return;

        mCardTripId = status.getActiveTripId();
        ObaTrip trip = mLastResponse.getTrip(mCardTripId);
        ObaRoute route = mLastResponse.getRoute(trip.getRouteId());

        mCardRouteView.setText(UIUtils.getRouteDisplayName(route) + " "
                + mActivity.getString(R.string.trip_info_separator) + " "
                + UIUtils.formatDisplayText(trip.getHeadsign()));

        boolean isRealtime = isLocationRealtime(status);
        Resources r = mActivity.getResources();
        int statusColor = getDeviationColorResource(isRealtime, status);

        mCardStatusView.setBackgroundResource(R.drawable.round_corners_style_b_status);
        GradientDrawable d = (GradientDrawable) mCardStatusView.getBackground();
        d.setColor(r.getColor(statusColor));
        int pSides = UIUtils.dpToPixels(mActivity, 5);
        int pTopBottom = UIUtils.dpToPixels(mActivity, 2);
        mCardStatusView.setPadding(pSides, pTopBottom, pSides, pTopBottom);

        if (isRealtime) {
            long deviationMin = TimeUnit.SECONDS.toMinutes(status.getScheduleDeviation());
            mCardStatusView.setText(
                    ArrivalInfoUtils.computeArrivalLabelFromDelay(r, deviationMin));
            UIUtils.setOccupancyVisibilityAndColor(mCardOccupancyView,
                    status.getOccupancyStatus(), OccupancyState.REALTIME);
            UIUtils.setOccupancyContentDescription(mCardOccupancyView,
                    status.getOccupancyStatus(), OccupancyState.REALTIME);
        } else {
            mCardStatusView.setText(r.getString(R.string.stop_info_scheduled));
            UIUtils.setOccupancyVisibilityAndColor(mCardOccupancyView,
                    null, OccupancyState.HISTORICAL);
            UIUtils.setOccupancyContentDescription(mCardOccupancyView,
                    null, OccupancyState.HISTORICAL);
        }

        mVehicleInfoCard.setVisibility(View.VISIBLE);
    }

    private void hideVehicleInfoCard() {
        if (mVehicleInfoCard != null) {
            mVehicleInfoCard.setVisibility(View.GONE);
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
        if (mMarkerData.handleInfoLabelClick(marker)) {
            return true;
        }
        ObaTripStatus status = mMarkerData.getStatusFromMarker(marker);
        if (status != null) {
            mMarkerData.setSelectedTripId(status.getActiveTripId());
            showVehicleInfoCard(status);
            return true;
        }
        return false;
    }

    @Override
    public void removeMarkerClicked(LatLng latLng) {
        if (mMarkerData != null) {
            mMarkerData.clearSelectedTripId();
        }
        hideVehicleInfoCard();
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
        private volatile String mSelectedTripId;

        /** Icon marker (unrotated) for the most recent AVL position. */
        private Marker mDataReceivedIconMarker;
        /** Label marker (rotated along polyline) for the most recent AVL position. */
        private Marker mDataReceivedLabelMarker;
        /** Cached label text to skip icon rebuild when unchanged. */
        private String mLastDataReceivedLabel;
        /** Cached anchor X for the label marker, offset to clear the circle icon. */
        private float mDataReceivedLabelAnchorX;

        /** Info label markers showing the slow/fast estimate positions. */
        private Marker mSlowEstimateMarker;
        private Marker mFastEstimateMarker;
        private BitmapDescriptor mSlowEstimateIcon;
        private BitmapDescriptor mFastEstimateIcon;
        /** Cached percentile speeds in m/s — only recomputed when gamma params change. */
        private GammaSpeedModel.GammaParams mCachedGammaParams;
        private double mCachedSpeed10Mps;
        private double mCachedSpeed90Mps;

        private static final float INFO_LABEL_Z_INDEX = VEHICLE_MARKER_Z_INDEX - 0.5f;
        private static final int INFO_LABEL_SP = 10;
        private static final int INFO_LABEL_POINTER_WIDTH_DP = 10;
        private static final String SLOW_ESTIMATE_LABEL = "Slow estimate";
        private static final String FAST_ESTIMATE_LABEL = "Fast estimate";
        private static final String SLOW_ESTIMATE_EXPANDED = "Slow estimate\n10th percentile speed";
        private static final String FAST_ESTIMATE_EXPANDED = "Fast estimate\n90th percentile speed";
        private BitmapDescriptor mSlowEstimateExpandedIcon;
        private BitmapDescriptor mFastEstimateExpandedIcon;
        private boolean mSlowEstimateExpanded;
        private boolean mFastEstimateExpanded;

        /** Polygon showing the gamma PDF curve offset perpendicular to the route. */
        private Polygon mPdfPolygon;
        private static final float HIGHLIGHT_Z_INDEX = -1f;
        private static final int HIGHLIGHT_RGB = 0xAB47BC; // bright purple
        private static final int PDF_FILL_ALPHA = 0x66;
        private static final int PDF_STROKE_RGB = 0xE1BEE7; // light purple
        private static final int PDF_STROKE_ALPHA = 0x88;

        /** Spacing between spine sample points in meters. */
        private static final double SPINE_SPACING_METERS = 20.0;
        /** Maximum perpendicular offset at the PDF peak, in meters. */
        private static final double PDF_MAX_OFFSET_METERS = 150.0;
        /** Upper percentile bound for the rendered distance range. */
        private static final double PDF_UPPER_QUANTILE = 0.99;

        // Precomputed spine for the selected trip's route shape
        private double[] mSpineDistances;
        private double[] mSpineLats;
        private double[] mSpineLngs;
        private LatLng[] mSpineLatLngs;
        // Precomputed per-spine-point values for per-frame offset calculation
        private double[] mSpineDistDeltaMph; // (dist[i] - avlDist) * MPS_TO_MPH
        private double[] mSpinePerpCos;      // cos(toRadians(heading + 90))
        private double[] mSpinePerpSin;      // sin(toRadians(heading + 90))
        private double[] mSpineLatCosInv;    // 1 / cos(toRadians(lat))
        /** Shape reference used to detect when spine needs rebuilding. */
        private List<Location> mSpineShape;
        /** Last AVL distance used to build spine; triggers rebuild when it changes. */
        private double mCachedLastDist = Double.NaN;

        // Cached gamma-derived values (recomputed only when params change)
        private double mCachedSpeed99Mps;
        private double mCachedInvScale;  // 1 / params.scale

        // Normalized PDF lookup table indexed by u = x/scale (unit-scale gamma).
        // Depends only on alpha; rebuilt when alpha changes.
        private static final int PDF_TABLE_SIZE = 256;
        private static final double PDF_TABLE_MAX_U = 50.0;
        private static final double PDF_TABLE_INV_STEP =
                (PDF_TABLE_SIZE - 1) / PDF_TABLE_MAX_U;
        private final double[] mNormalizedPdfTable = new double[PDF_TABLE_SIZE];

        /** Reusable list for polygon vertex construction. */
        private final java.util.ArrayList<LatLng> mPdfPolygonPoints = new java.util.ArrayList<>();

        /** Reusable Location for info label interpolation to avoid per-frame allocation. */
        private final Location mInfoLabelReusableLoc = new Location("infolabel");

        private static final String DATA_RECEIVED_TITLE = "Most recent data";

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

                        recordTrajectoryState(status, response);
                        fetchScheduleAndShapeIfNeeded(status, response);
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
         * Records a vehicle state snapshot and route type into the trajectory tracker.
         */
        private void recordTrajectoryState(ObaTripStatus status,
                                            ObaTripsForRouteResponse response) {
            VehicleTrajectoryTracker tracker = VehicleTrajectoryTracker.getInstance();
            ObaTrip activeTripObj = response.getTrip(status.getActiveTripId());
            String blockId = activeTripObj != null ? activeTripObj.getBlockId() : null;
            tracker.recordState(status.getActiveTripId(),
                    VehicleState.fromTripStatus(status), blockId);
            if (tracker.getRouteType(status.getActiveTripId()) == null) {
                String routeId = activeTripObj != null ? activeTripObj.getRouteId() : null;
                ObaRoute route = routeId != null ? response.getRoute(routeId) : null;
                if (route != null) {
                    tracker.putRouteType(status.getActiveTripId(), route.getType());
                }
            }
        }

        /**
         * Fetches schedule and/or shape data in a background thread if not already cached.
         */
        private void fetchScheduleAndShapeIfNeeded(ObaTripStatus status,
                                                    ObaTripsForRouteResponse response) {
            VehicleTrajectoryTracker tracker = VehicleTrajectoryTracker.getInstance();
            String tripId = status.getActiveTripId();
            ObaTrip activeTripObj = response.getTrip(tripId);
            String shapeId = activeTripObj != null ? activeTripObj.getShapeId() : null;
            boolean needSchedule = tripId != null
                    && !tracker.isSchedulePendingOrCached(tripId);
            boolean needShape = tripId != null && shapeId != null
                    && tracker.getShape(tripId) == null;
            if (!needSchedule && !needShape) return;

            if (needSchedule) {
                tracker.markSchedulePending(tripId);
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
                                tracker.putSchedule(tripId, schedule);
                            }
                        }
                    }
                    if (fetchShape) {
                        ObaShapeResponse shapeResponse =
                                ObaShapeRequest.newRequest(ctx, shapeId).call();
                        if (shapeResponse != null) {
                            List<Location> points = shapeResponse.getPoints();
                            if (points != null && !points.isEmpty()) {
                                tracker.putShape(tripId, points);
                            }
                        }
                    }
                    if (tripId.equals(mSelectedTripId) && mController != null) {
                        mActivity.runOnUiThread(() -> {
                            if (tripId.equals(mSelectedTripId) && mController != null) {
                                mController.onVehicleSelected(tripId);
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to fetch schedule/shape for " + tripId, e);
                    if (fetchSchedule) {
                        tracker.clearPending(tripId);
                    }
                }
            }).start();
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
            removeEstimateMarkers();
            showOrUpdateDataReceivedMarker(tripId);
            createEstimateMarkers(tripId);
            animateChangedIcons(previousTripId);
            if (mController != null && tripId != null) {
                mController.onVehicleSelected(tripId);
            }
        }

        /**
         * Clears the selection, restoring all markers to full vehicle icons.
         */
        synchronized void clearSelectedTripId() {
            if (mSelectedTripId == null) return;
            String previousTripId = mSelectedTripId;
            mSelectedTripId = null;
            removeDataReceivedMarker();
            removeEstimateMarkers();
            animateChangedIcons(previousTripId);
            if (mController != null) {
                mController.onVehicleDeselected();
            }
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

            LatLng latLng = MapHelpV2.makeLatLng(pos);
            String label = formatElapsedTime(latest.getLastLocationUpdateTime());

            // Compute label rotation from polyline heading
            float labelRotation = 0f;
            Double lastDist = latest.getBestDistanceAlongTrip();
            List<Location> shape = tracker.getShape(tripId);
            double[] cumDist = tracker.getShapeCumulativeDistances(tripId);
            if (lastDist != null && shape != null && cumDist != null) {
                double heading = DistanceExtrapolator.headingAlongPolyline(
                        shape, cumDist, lastDist);
                if (!Double.isNaN(heading)) {
                    double labelAz = clampedLabelAzimuth(heading);
                    labelRotation = (float) (labelAz - 90.0);
                }
            }

            // Icon marker: unrotated circle with signal icon
            if (mDataReceivedIconMarker != null) {
                mDataReceivedIconMarker.setPosition(latLng);
            } else {
                mDataReceivedIconMarker = mMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .icon(createDataReceivedCircleIcon())
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

        private void removeDataReceivedMarker() {
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

        // --- Info label lifecycle ---

        /**
         * Creates an info label icon: a pointer bubble with text.
         */
        private BitmapDescriptor createInfoLabelIcon(String label, float[] outWidth) {
            return createInfoLabelIconInternal(new String[]{label}, outWidth);
        }

        private BitmapDescriptor createInfoLabelExpandedIcon(String label) {
            return createInfoLabelIconInternal(label.split("\n"), null);
        }

        private BitmapDescriptor createInfoLabelIconInternal(String[] lines,
                                                                   float[] outWidth) {
            float d = mActivity.getResources().getDisplayMetrics().density;

            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(INFO_LABEL_SP * d);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setColor(0xFF616161);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            int lineHeight = (int) Math.ceil(fm.descent - fm.ascent);
            float lineGap = d;

            // Measure widest line
            float maxLineWidth = 0;
            for (String line : lines) {
                maxLineWidth = Math.max(maxLineWidth, textPaint.measureText(line));
            }

            float padLeft = 4 * d;
            float padRight = 6 * d;
            float padY = 2 * d;
            float pointerWidth = INFO_LABEL_POINTER_WIDTH_DP * d;
            int textBlockHeight = lineHeight * lines.length
                    + (int) (lineGap * (lines.length - 1));
            int bubbleWidth = (int) (pointerWidth + padLeft + maxLineWidth + padRight);
            int bubbleHeight = (int) (textBlockHeight + padY * 2);
            float cornerRadius = 3 * d;

            Bitmap bmp = Bitmap.createBitmap(bubbleWidth, bubbleHeight, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);

            float bodyLeft = drawPointerBubble(c, 0, 0,
                    bubbleWidth, bubbleHeight, cornerRadius, pointerWidth);

            float textX = bodyLeft + padLeft;
            float textY = padY - fm.ascent;
            for (int i = 0; i < lines.length; i++) {
                c.drawText(lines[i], textX, textY + i * (lineHeight + lineGap), textPaint);
            }

            if (outWidth != null && outWidth.length > 0) {
                outWidth[0] = bubbleWidth;
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
                                         float cornerRadius, float pointerWidth) {
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

        private void createEstimateMarkers(String tripId) {
            if (tripId == null || mLastResponse == null) return;
            Integer routeType = VehicleTrajectoryTracker.getInstance().getRouteType(tripId);
            if (routeType != null && ObaRoute.isGradeSeparated(routeType)) return;
            Marker vehicleMarker = mVehicleMarkers.get(tripId);
            if (vehicleMarker == null) return;

            mSlowEstimateIcon = createInfoLabelIcon(SLOW_ESTIMATE_LABEL, null);
            mFastEstimateIcon = createInfoLabelIcon(FAST_ESTIMATE_LABEL, null);
            mSlowEstimateExpandedIcon = createInfoLabelExpandedIcon(SLOW_ESTIMATE_EXPANDED);
            mFastEstimateExpandedIcon = createInfoLabelExpandedIcon(FAST_ESTIMATE_EXPANDED);
            mCachedGammaParams = null;
            mSlowEstimateExpanded = false;
            mFastEstimateExpanded = false;

            LatLng pos = vehicleMarker.getPosition();
            mSlowEstimateMarker = addFlatInfoLabelMarker(pos, mSlowEstimateIcon);
            mFastEstimateMarker = addFlatInfoLabelMarker(pos, mFastEstimateIcon);

            // Create the PDF polygon behind the route polyline
            mPdfPolygon = addPdfPolygon();
        }

        private void removeEstimateMarkers() {
            if (mSlowEstimateMarker != null) {
                mSlowEstimateMarker.remove();
                mSlowEstimateMarker = null;
            }
            if (mFastEstimateMarker != null) {
                mFastEstimateMarker.remove();
                mFastEstimateMarker = null;
            }
            if (mPdfPolygon != null) {
                mPdfPolygon.remove();
                mPdfPolygon = null;
            }
            mSpineShape = null;
            mSpineDistances = null;
            mCachedLastDist = Double.NaN;
            mSlowEstimateIcon = null;
            mFastEstimateIcon = null;
            mSlowEstimateExpandedIcon = null;
            mFastEstimateExpandedIcon = null;
            mCachedGammaParams = null;
        }

        /**
         * Handles a click on an info label marker by toggling between collapsed
         * and expanded label. Returns true if the marker was an info label.
         */
        boolean handleInfoLabelClick(Marker marker) {
            if (marker.equals(mSlowEstimateMarker)) {
                mSlowEstimateExpanded = !mSlowEstimateExpanded;
                mSlowEstimateMarker.setIcon(mSlowEstimateExpanded
                        ? mSlowEstimateExpandedIcon : mSlowEstimateIcon);
                return true;
            }
            if (marker.equals(mFastEstimateMarker)) {
                mFastEstimateExpanded = !mFastEstimateExpanded;
                mFastEstimateMarker.setIcon(mFastEstimateExpanded
                        ? mFastEstimateExpandedIcon : mFastEstimateIcon);
                return true;
            }
            return false;
        }

        private Marker addFlatInfoLabelMarker(LatLng pos, BitmapDescriptor icon) {
            return mMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .icon(icon)
                    .anchor(0f, 0.5f)
                    .flat(true)
                    .zIndex(INFO_LABEL_Z_INDEX)
                    .visible(false)
            );
        }

        private Polygon addPdfPolygon() {
            int fillColor = (PDF_FILL_ALPHA << 24) | HIGHLIGHT_RGB;
            int strokeColor = (PDF_STROKE_ALPHA << 24) | PDF_STROKE_RGB;
            return mMap.addPolygon(new PolygonOptions()
                    .add(new LatLng(0, 0))  // dummy point; replaced on first update
                    .fillColor(fillColor)
                    .strokeColor(strokeColor)
                    .strokeWidth(4f)
                    .zIndex(HIGHLIGHT_Z_INDEX)
                    .visible(false));
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

        private static final int DATA_ICON_RADIUS_DP = 13;
        private static final int DATA_ICON_INNER_DP = 20;
        private static final int DATA_ICON_GAP_DP = 3;

        /** Creates the circle + signal icon bitmap (unrotated). */
        private BitmapDescriptor createDataReceivedCircleIcon() {
            float d = mActivity.getResources().getDisplayMetrics().density;
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
                    mActivity, R.drawable.ic_signal_indicator);
            if (icon != null) {
                int iconLeft = (size - iconSize) / 2;
                int iconTop = (size - iconSize) / 2;
                icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
                icon.draw(c);
            }

            return BitmapDescriptorFactory.fromBitmap(bmp);
        }

        /** Creates the pointer-bubble label bitmap (rotated by the marker).
         *  Also computes mDataReceivedLabelAnchorX to offset the label past the circle icon. */
        private BitmapDescriptor createDataReceivedLabelIcon(String timeLine) {
            String[] lines = {DATA_RECEIVED_TITLE, timeLine};
            float[] widthOut = new float[1];
            BitmapDescriptor icon = createInfoLabelIconInternal(lines, widthOut);
            float d = mActivity.getResources().getDisplayMetrics().density;
            float offsetPx = (DATA_ICON_RADIUS_DP + DATA_ICON_GAP_DP) * d;
            mDataReceivedLabelAnchorX = -offsetPx / widthOut[0];
            return icon;
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

            // Capture selected-trip data for estimate markers
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

                // Capture data for estimate markers regardless of speed availability
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

            // Update estimate markers for the selected vehicle
            updateEstimateMarkers(selectedParams, selectedShape, selectedCumDist,
                    selectedHistory, now);
        }

        private void hideEstimateMarkers() {
            if (mSlowEstimateMarker != null) mSlowEstimateMarker.setVisible(false);
            if (mFastEstimateMarker != null) mFastEstimateMarker.setVisible(false);
            if (mPdfPolygon != null) mPdfPolygon.setVisible(false);
        }

        private void updateEstimateMarkers(GammaSpeedModel.GammaParams params,
                                            List<Location> shape, double[] cumDist,
                                            List<VehicleHistoryEntry> history, long now) {
            if (mSlowEstimateMarker == null || mFastEstimateMarker == null) return;

            if (params == null || shape == null || cumDist == null
                    || history == null || history.isEmpty()) {
                hideEstimateMarkers();
                return;
            }

            VehicleHistoryEntry newest = DistanceExtrapolator.findNewestValidEntry(history);
            if (newest == null) {
                hideEstimateMarkers();
                return;
            }

            Double lastDist = newest.getBestDistanceAlongTrip();
            long lastTime = newest.getLastLocationUpdateTime();
            if (lastDist == null || lastTime <= 0) {
                hideEstimateMarkers();
                return;
            }

            double dtSec = (now - lastTime) / 1000.0;
            if (dtSec < 0.5) {
                hideEstimateMarkers();
                return;
            }

            // Cache percentile speeds, inverse scale, and PDF table
            if (params != mCachedGammaParams) {
                mCachedGammaParams = params;
                mCachedSpeed10Mps = GammaSpeedModel.quantile(0.10, params)
                        / GammaSpeedModel.MPS_TO_MPH;
                mCachedSpeed90Mps = GammaSpeedModel.quantile(0.90, params)
                        / GammaSpeedModel.MPS_TO_MPH;
                mCachedSpeed99Mps = GammaSpeedModel.quantile(PDF_UPPER_QUANTILE, params)
                        / GammaSpeedModel.MPS_TO_MPH;
                mCachedInvScale = 1.0 / params.scale;
                buildNormalizedPdfTable(params.alpha);
            }

            double dist10 = lastDist + mCachedSpeed10Mps * dtSec;
            double dist90 = lastDist + mCachedSpeed90Mps * dtSec;
            double dist99 = lastDist + mCachedSpeed99Mps * dtSec;

            updateInfoLabelPosition(mSlowEstimateMarker, dist10, shape, cumDist);
            updateInfoLabelPosition(mFastEstimateMarker, dist90, shape, cumDist);
            updatePdfPolygon(lastDist, dtSec, dist99, shape, cumDist);
        }

        /**
         * Precomputes spine points along the route shape by merging:
         * 1. Regular interval points (every SPINE_SPACING_METERS)
         * 2. Every vertex on the original polyline
         * 3. The current AVL data point (lastDist)
         * Called when the shape is first needed, changes, or the AVL position changes.
         */
        private void buildSpine(List<Location> shape, double[] cumDist, double avlDist) {
            if (shape == null || shape.isEmpty() || cumDist == null) return;
            mSpineShape = shape;
            mCachedLastDist = avlDist;
            double totalDist = cumDist[cumDist.length - 1];

            // Collect all candidate distances into a sorted set (auto-deduplicates)
            java.util.TreeSet<Double> distSet = new java.util.TreeSet<>();

            // Regular interval points
            for (double d = 0; d <= totalDist; d += SPINE_SPACING_METERS) {
                distSet.add(d);
            }
            distSet.add(totalDist);

            // Every polyline vertex
            for (double d : cumDist) {
                distSet.add(d);
            }

            // AVL data point
            if (!Double.isNaN(avlDist) && avlDist >= 0 && avlDist <= totalDist) {
                distSet.add(avlDist);
            }

            int count = distSet.size();
            mSpineDistances = new double[count];
            mSpineLats = new double[count];
            mSpineLngs = new double[count];
            mSpineLatLngs = new LatLng[count];
            mSpineDistDeltaMph = new double[count];
            mSpinePerpCos = new double[count];
            mSpinePerpSin = new double[count];
            mSpineLatCosInv = new double[count];

            Location reusable = new Location("spine");
            int i = 0;
            for (Double d : distSet) {
                mSpineDistances[i] = d;
                DistanceExtrapolator.interpolateAlongPolyline(shape, cumDist, d, reusable);
                double lat = reusable.getLatitude();
                double lng = reusable.getLongitude();
                mSpineLats[i] = lat;
                mSpineLngs[i] = lng;
                mSpineLatLngs[i] = new LatLng(lat, lng);

                // Precompute distance delta from AVL point (in mph-seconds)
                mSpineDistDeltaMph[i] = (d - avlDist) * GammaSpeedModel.MPS_TO_MPH;

                // Precompute perpendicular direction vector components.
                // Includes radians-to-degrees conversion so per-frame code
                // can add offsets directly to lat/lng in degrees.
                double heading = DistanceExtrapolator.headingAlongPolyline(
                        shape, cumDist, d);
                double perpBearing = Math.toRadians(heading + 90.0);
                double degreesPerMeter = Math.toDegrees(1.0)
                        / DistanceExtrapolator.EARTH_RADIUS_METERS;
                mSpinePerpCos[i] = Math.cos(perpBearing) * degreesPerMeter;
                mSpinePerpSin[i] = Math.sin(perpBearing) * degreesPerMeter;
                mSpineLatCosInv[i] = 1.0 / Math.cos(Math.toRadians(lat));
                i++;
            }
            mPdfPolygonPoints.ensureCapacity(count * 2);
        }

        /**
         * Builds a lookup table of the normalized gamma PDF indexed by u = x/scale.
         * Uses a unit-scale gamma Gamma(alpha, 1), so the table depends only on
         * alpha and is valid for any scale or dtSec. Per-frame code converts
         * spine distances to u-values and interpolates into this table.
         */
        private void buildNormalizedPdfTable(double alpha) {
            GammaSpeedModel.GammaParams unitParams =
                    new GammaSpeedModel.GammaParams(alpha, 1.0);
            double modeU = (alpha >= 1) ? alpha - 1 : 0.01;
            double peakPdf = GammaSpeedModel.pdf(modeU, unitParams);
            if (peakPdf <= 0) peakPdf = 1.0;
            double step = PDF_TABLE_MAX_U / (PDF_TABLE_SIZE - 1);
            for (int k = 0; k < PDF_TABLE_SIZE; k++) {
                double u = k * step;
                mNormalizedPdfTable[k] = (u > 0)
                        ? GammaSpeedModel.pdf(u, unitParams) / peakPdf : 0;
            }
        }

        /**
         * Per-frame: looks up cached spine range, builds polygon using
         * precomputed spine geometry and PDF lookup table.
         */
        private void updatePdfPolygon(double lastDist, double dtSec,
                                       double distEnd,
                                       List<Location> shape, double[] cumDist) {
            if (mPdfPolygon == null) return;

            // Lazily build spine on first use, shape change, or AVL position change
            if (mSpineDistances == null || shape != mSpineShape
                    || Double.doubleToLongBits(lastDist)
                       != Double.doubleToLongBits(mCachedLastDist)) {
                buildSpine(shape, cumDist, lastDist);
            }
            if (mSpineDistances == null) {
                mPdfPolygon.setVisible(false);
                return;
            }

            // Binary search for the range of spine points within [lastDist, distEnd]
            // lastDist (AVL point) is guaranteed to be in the spine, so the PDF
            // evaluation starts exactly at the vehicle's last known position.
            int startIdx = Arrays.binarySearch(mSpineDistances, lastDist);
            if (startIdx < 0) startIdx = -startIdx - 1;
            int endIdx = Arrays.binarySearch(mSpineDistances, distEnd);
            if (endIdx < 0) endIdx = -endIdx - 1;
            else endIdx++;  // include the exact match

            if (startIdx >= endIdx || startIdx >= mSpineDistances.length) {
                mPdfPolygon.setVisible(false);
                return;
            }
            endIdx = Math.min(endIdx, mSpineDistances.length);

            mPdfPolygonPoints.clear();

            // Per-frame: convert each spine point's distance-from-AVL to the
            // normalized gamma variable u = distDelta / (scale * dtSec), then
            // look up the precomputed normalized PDF table.
            // mSpineDistDeltaMph[i] = distDelta * MPS_TO_MPH, so:
            //   u = mSpineDistDeltaMph[i] * invScaleDt
            // where invScaleDt = 1 / (scale * dtSec) = mCachedInvScale / dtSec
            double invScaleDt = mCachedInvScale / dtSec;

            for (int i = startIdx; i < endIdx; i++) {
                double u = mSpineDistDeltaMph[i] * invScaleDt;

                // Lookup normalized PDF via linear interpolation into table
                double normalizedPdf;
                if (u <= 0 || u >= PDF_TABLE_MAX_U) {
                    normalizedPdf = 0;
                } else {
                    double idx = u * PDF_TABLE_INV_STEP;
                    int lo = (int) idx;
                    double frac = idx - lo;
                    normalizedPdf = mNormalizedPdfTable[lo]
                            + frac * (mNormalizedPdfTable[lo + 1]
                                      - mNormalizedPdfTable[lo]);
                }
                double offset = normalizedPdf * PDF_MAX_OFFSET_METERS;

                // Perpendicular offset using precomputed direction vectors
                double dLat = offset * mSpinePerpCos[i];
                double dLng = offset * mSpinePerpSin[i] * mSpineLatCosInv[i];

                mPdfPolygonPoints.add(new LatLng(
                        mSpineLats[i] + dLat, mSpineLngs[i] + dLng));
            }

            // Backward pass: spine side (pre-built LatLngs, no computation)
            for (int i = endIdx - 1; i >= startIdx; i--) {
                mPdfPolygonPoints.add(mSpineLatLngs[i]);
            }

            if (mPdfPolygonPoints.size() >= 3) {
                mPdfPolygon.setPoints(mPdfPolygonPoints);
                mPdfPolygon.setVisible(true);
            } else {
                mPdfPolygon.setVisible(false);
            }
        }

        /**
         * Updates an info label marker's position and rotation along the polyline.
         * Label azimuth is always in [0°, 180°] (right side), so text is
         * always upright — no icon flipping needed.
         */
        private void updateInfoLabelPosition(Marker marker, double distance,
                                                 List<Location> shape, double[] cumDist) {
            if (!DistanceExtrapolator.interpolateAlongPolyline(
                    shape, cumDist, distance, mInfoLabelReusableLoc)) {
                marker.setVisible(false);
                return;
            }
            marker.setPosition(new LatLng(
                    mInfoLabelReusableLoc.getLatitude(),
                    mInfoLabelReusableLoc.getLongitude()));
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
            removeEstimateMarkers();
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

}