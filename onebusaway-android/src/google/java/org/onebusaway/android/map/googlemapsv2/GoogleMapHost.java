/*
 * Copyright (C) 2011-2014 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com), and individual contributors.
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

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.LocationSource;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.UiSettings;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.VisibleRegion;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.analytics.FirebaseAnalytics;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.ObaAnalytics;
import org.onebusaway.android.io.PlausibleAnalytics;
import org.onebusaway.android.io.elements.ObaReferences;
import org.onebusaway.android.io.elements.ObaRegion;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaShape;
import org.onebusaway.android.io.elements.ObaStop;
import org.onebusaway.android.io.elements.ObaTripDetails;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.elements.Status;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.map.DirectionsMapController;
import org.onebusaway.android.map.LayerActivationListener;
import org.onebusaway.android.map.LayerInfo;
import org.onebusaway.android.map.MapHostDeps;
import org.onebusaway.android.map.MapModeController;
import org.onebusaway.android.map.MapParams;
import org.onebusaway.android.map.ObaMapFragment.OnFocusChangedListener;
import org.onebusaway.android.map.ObaMapFragment.OnLocationPermissionResultListener;
import org.onebusaway.android.map.ObaMapFragment.OnProgressBarChangedListener;
import org.onebusaway.android.map.ObaMapHost;
import org.onebusaway.android.map.RouteMapController;
import org.onebusaway.android.map.StopMapController;
import org.onebusaway.android.map.bike.BikeshareMapController;
import org.onebusaway.android.map.googlemapsv2.bike.BikeStationOverlay;
import org.onebusaway.android.map.googlemapsv2.compose.ComposeMapHostKt;
import org.onebusaway.android.map.render.GeoPoint;
import org.onebusaway.android.map.render.MapRenderState;
import org.onebusaway.android.map.render.RoutePolyline;
import org.onebusaway.android.map.render.VehicleMarker;
import org.onebusaway.android.region.ObaRegionsTask;
import org.onebusaway.android.ui.weather.RegionCallback;
import org.onebusaway.android.util.LocationHelper;
import org.onebusaway.android.util.LocationUtils;
import org.onebusaway.android.util.PermissionUtils;
import org.onebusaway.android.util.PreferenceUtils;
import org.onebusaway.android.util.UIUtils;
import org.opentripplanner.routing.bike_rental.BikeRentalStation;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.drawable.DrawableCompat;

import static org.onebusaway.android.util.PermissionUtils.LOCATION_PERMISSIONS;
import static org.onebusaway.android.util.UIUtils.canManageDialog;

/**
 * The non-fragment Google Maps host: the extracted body of the legacy {@code BaseMapFragment}. It
 * owns the raw {@link GoogleMap}, the {@link MapModeController} set, and the overlays, and drives the
 * map imperatively (stop/route/directions modes) exactly as before. The hosting Activity mounts
 * {@link #getView()} and forwards lifecycle via {@link ObaMapHost}; location-permission requests go
 * through {@link MapHostDeps} (the owner's {@code ActivityResultLauncher}). {@code BaseMapFragment}
 * is now a thin wrapper around this class for the other (still-fragment) map screens.
 *
 * The MapFragment was historically split into stop mode and route mode; this class handles the common
 * functionality (zoom, save/restore, bookkeeping) and hands the rest to a {@link MapModeController}.
 */
public class GoogleMapHost
        implements ObaMapHost, MapModeController.Callback, ObaRegionsTask.Callback,
        MapModeController.ObaMapView,
        LocationSource, LocationHelper.Listener,
        GoogleMap.OnCameraChangeListener,
        StopOverlay.OnFocusChangedListener, OnMapReadyCallback,
        LayerActivationListener {

    private static final String TAG = "MapFragment";

    public static final float CAMERA_DEFAULT_ZOOM = 16.0f;

    public static final float DEFAULT_MAP_PADDING_DP = 20.0f;

    // The hosting environment, supplied by the owner (Activity or thin fragment wrapper).
    private final Activity mActivity;

    private final MapHostDeps mDeps;

    private final View mView;

    private boolean mDestroyed = false;

    // Keep track of current map padding
    private int mMapPaddingLeft = 0;

    private int mMapPaddingTop = 0;

    private int mMapPaddingRight = 0;

    private int mMapPaddingBottom = 0;

    // Use fully-qualified class name to avoid import statement, because it interferes with scripted
    // copying of Maps API v2 classes between Google/Amazon build flavors (see #254)
    private com.google.android.gms.maps.GoogleMap mMap;

    private String mFocusStopId;

    // The host controls the stop overlay, since that is used by both modes.
    private StopOverlay mStopOverlay;

    private BikeStationOverlay mBikeStationOverlay;

    // We only display the out of range dialog once
    private boolean mWarnOutOfRange = true;

    private boolean mRunning = false;

    private List<MapModeController> mControllers;

    private String mMapMode = "";

    // Declarative overlay content (route polylines, generic markers) rendered by ObaMapContent
    // inside the GoogleMap {} composable. The ObaMapView methods mutate this instead of the map SDK.
    private final MapRenderState mRenderState = new MapRenderState();

    // We have to convert from LatLng to Location, so hold references to both
    private LatLng mCenter;

    private Location mCenterLocation;

    private OnLocationChangedListener mListener;

    // Listen to map tap events
    OnFocusChangedListener mOnFocusChangedListener;

    // Listen to map loading/progress bar events
    OnProgressBarChangedListener mOnProgressBarChangedListener;

    // Listen to location permission request results
    OnLocationPermissionResultListener mOnLocationPermissionResultListener;

    LocationHelper mLocationHelper;

    Bundle mLastSavedInstanceState;

    private boolean mUserDeniedPermission = false;

    private FirebaseAnalytics mFirebaseAnalytics;

    private AlertDialog locationPermissionDialog;

    public GoogleMapHost(Activity activity, MapHostDeps deps, Bundle args) {
        mActivity = activity;
        mDeps = deps;
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(activity);

        mUserDeniedPermission = PreferenceUtils.userDeniedLocationPermission();

        mLocationHelper = new LocationHelper(activity);

        if (!MapHelpV2.isMapsInstalled(activity)) {
            MapHelpV2.promptUserInstallMaps(activity);
            mView = new View(activity);
            return;
        }

        // Save the args/saved bundle; onMapReady() (invoked via the Compose MapEffect bridge)
        // consumes it just like the old getMapAsync() callback did.
        mLastSavedInstanceState = args;

        // Host the map in a ComposeView via android-maps-compose. The host keeps driving the raw
        // GoogleMap imperatively in onMapReady() + the overlays; the seed camera only avoids an
        // initial flash before initMap() centers the map.
        double[] seed = resolveInitialCamera(args);
        mView = ComposeMapHostKt.createComposeMapView(
                activity, mRenderState, this, seed[0], seed[1], (float) seed[2]);

        // If we have a recent location, show this while we're waiting on the LocationHelper
        Location l = Application.getLastKnownLocation(activity);
        if (l != null) {
            final long TIME_THRESHOLD = TimeUnit.MINUTES.toMillis(5);
            if (System.currentTimeMillis() - l.getTime() < TIME_THRESHOLD) {
                onLocationChanged(l);
            }
        }
    }

    @Override
    public Activity getActivity() {
        return mActivity;
    }

    @Override
    public View getView() {
        return mView;
    }

    @Override
    public void onActivateLayer(LayerInfo layer) {
        switch (layer.getLayerlabel()) {
            case "Bikeshare": {
                for (MapModeController controller : mControllers) {
                    if (controller instanceof BikeshareMapController) {
                        ((BikeshareMapController) controller).showBikes(true);
                        ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                                Application.get().getPlausibleInstance(),
                                PlausibleAnalytics.REPORT_MAP_EVENT_URL,
                                mActivity.getString(R.string.analytics_layer_bikeshare),
                                mActivity.getString(R.string.analytics_label_bikeshare_activated));
                    }
                }
                break;
            }
        }
    }

    @Override
    public void onDeactivateLayer(LayerInfo layer) {
        switch (layer.getLayerlabel()) {
            case "Bikeshare": {
                for (MapModeController controller : mControllers) {
                    if (controller instanceof BikeshareMapController) {
                        ((BikeshareMapController) controller).showBikes(false);
                        ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                                Application.get().getPlausibleInstance(),
                                PlausibleAnalytics.REPORT_MAP_EVENT_URL,
                                mActivity.getString(R.string.analytics_layer_bikeshare),
                                mActivity.getString(R.string.analytics_label_bikeshare_deactivated));
                    }
                }
                break;
            }
        }
    }

    /** Resolves the initial map camera (lat, lon, zoom) from saved state, then intent extras. */
    private double[] resolveInitialCamera(Bundle savedInstanceState) {
        Bundle src = savedInstanceState;
        if (src == null && mActivity != null) {
            src = mActivity.getIntent().getExtras();
        }
        double lat = 0.0;
        double lon = 0.0;
        float zoom = CAMERA_DEFAULT_ZOOM;
        if (src != null) {
            lat = src.getDouble(MapParams.CENTER_LAT, 0.0);
            lon = src.getDouble(MapParams.CENTER_LON, 0.0);
            zoom = src.getFloat(MapParams.ZOOM, CAMERA_DEFAULT_ZOOM);
        }
        return new double[]{lat, lon, zoom};
    }

    public void zoomIn() {
        mMap.animateCamera(CameraUpdateFactory.zoomIn());
    }

    public void zoomOut() {
        mMap.animateCamera(CameraUpdateFactory.zoomOut());
    }

    @Override
    public void onMapReady(com.google.android.gms.maps.GoogleMap map) {
        mMap = map;

        MapClickListeners mapClickListeners = new MapClickListeners();

        mMap.setOnMarkerClickListener(mapClickListeners);
        mMap.setOnMapClickListener(mapClickListeners);

        if (inDarkMode()) {
            mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(mActivity, R.raw.dark_map));
        } else {
            // When in light mode, just remove POIs.
            String removePOIStyle = "[{\"featureType\":\"poi\",\"elementType\":\"all\",\"stylers\":[{\"visibility\":\"off\"}]}]";
            mMap.setMapStyle(new MapStyleOptions(removePOIStyle));
        }

        initMap(mLastSavedInstanceState);
    }

    private boolean inDarkMode() {
        return AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES || (
                AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_NO &&
                        (mActivity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        );
    }

    private void initMap(Bundle savedInstanceState) {
        UiSettings uiSettings = mMap.getUiSettings();
        mUserDeniedPermission = PreferenceUtils.userDeniedLocationPermission();

        if (!mUserDeniedPermission) {
            requestPermissionAndInit(mActivity);
        }

        // Set location source
        mMap.setLocationSource(this);
        // Listener for camera changes
        mMap.setOnCameraChangeListener(this);
        // Hide MyLocation button on map, since we have our own button
        uiSettings.setMyLocationButtonEnabled(false);
        // Hide the built-in zoom controls; the app shows its own (the preference-gated Compose zoom
        // buttons), and the SDK ones otherwise sit bottom-end under our my-location FAB.
        uiSettings.setZoomControlsEnabled(false);
        // Hide Toolbar
        uiSettings.setMapToolbarEnabled(false);
        // Check for map mode settings
        updateMapModeSettings();

        if (savedInstanceState != null) {
            initMapState(savedInstanceState);
        } else {
            Bundle args = mActivity.getIntent().getExtras();
            // The rest of this code assumes a bundle exists, even if it's empty
            if (args == null) {
                args = new Bundle();
            }
            double lat = args.getDouble(MapParams.CENTER_LAT, 0.0d);
            double lon = args.getDouble(MapParams.CENTER_LON, 0.0d);
            if (lat == 0.0d && lon == 0.0d) {
                // Try to restore the latest map view location
                PreferenceUtils.maybeRestoreMapViewToBundle(args);
            }
            initMapState(args);
        }
    }

    private void initMapState(Bundle args) {
        mFocusStopId = args.getString(MapParams.STOP_ID);
        mRenderState.setFocusedStopId(mFocusStopId);

        mMapPaddingLeft = args.getInt(MapParams.MAP_PADDING_LEFT, MapParams.DEFAULT_MAP_PADDING);
        mMapPaddingTop = args.getInt(MapParams.MAP_PADDING_TOP, MapParams.DEFAULT_MAP_PADDING);
        mMapPaddingRight = args.getInt(MapParams.MAP_PADDING_RIGHT, MapParams.DEFAULT_MAP_PADDING);

        mMapPaddingBottom = args
                .getInt(MapParams.MAP_PADDING_BOTTOM, MapParams.DEFAULT_MAP_PADDING);
        setPadding(mMapPaddingLeft, mMapPaddingTop, mMapPaddingRight, mMapPaddingBottom);

        String mode = args.getString(MapParams.MODE);
        if (mode == null) {
            mode = MapParams.MODE_STOP;
        }
        setMapMode(mode, args);
    }

    @SuppressLint("MissingPermission")
    private void requestPermissionAndInit(final Activity activity) {
        if (PermissionUtils.hasGrantedAtLeastOnePermission(activity, LOCATION_PERMISSIONS)) {
            // Show the location on the map
            mMap.setMyLocationEnabled(true);
            // Make sure location helper is registered
            mLocationHelper.registerListener(this);
        } else {
            // Explain permission to user
            showLocationPermissionDialog();
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onLocationPermissionResult(int grantResult) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            mUserDeniedPermission = false;
            // Show the location on the map
            if (mMap != null) {
                mMap.setMyLocationEnabled(true);
            }
            // Make sure location helper is registered
            mLocationHelper.registerListener(this);
        } else {
            mUserDeniedPermission = true;
        }
        if (mOnLocationPermissionResultListener != null) {
            mOnLocationPermissionResultListener.onLocationPermissionResult(grantResult);
        }
    }

    @Override
    public void onStart() {
        // The Compose-hosted GoogleMap manages its own view-tree lifecycle.
    }

    @Override
    public void onStop() {
        // The Compose-hosted GoogleMap manages its own view-tree lifecycle.
    }

    @Override
    public void onLowMemory() {
        // The Compose-hosted GoogleMap manages its own memory.
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        mLocationHelper.unregisterListener(this);
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.destroy();
            }
        }
    }

    @Override
    public void onPause() {
        if (mLocationHelper != null) {
            mLocationHelper.onPause();
        }
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onPause();
            }
        }

        Location center = getMapCenterAsLocation();
        if (center != null) {
            PreferenceUtils.saveMapViewToPreferences(center.getLatitude(), center.getLongitude(),
                    getZoomLevelAsFloat());
        }

        mRunning = false;
    }

    /**
     * The host's owner forwards show/hide here (the old {@code Fragment.onHiddenChanged}).
     *
     * @param hidden True if the host is now hidden, false if it is not visible.
     */
    @Override
    public void onHidden(boolean hidden) {
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onHidden(hidden);
            }
        }
    }

    @Override
    public void onResume() {
        mUserDeniedPermission = PreferenceUtils.userDeniedLocationPermission();
        if (mLocationHelper != null) {
            mLocationHelper.onResume();
        }
        mRunning = true;

        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onResume();
                controller.notifyMapChanged();
            }
        }
        updateMapModeSettings();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onSaveInstanceState(outState);
            }
        }
        outState.putString(MapParams.MODE, getMapMode());
        outState.putString(MapParams.STOP_ID, mFocusStopId);
        Location center = getMapCenterAsLocation();
        if (mMap != null) {
            outState.putDouble(MapParams.CENTER_LAT, center.getLatitude());
            outState.putDouble(MapParams.CENTER_LON, center.getLongitude());
            outState.putFloat(MapParams.ZOOM, getZoomLevelAsFloat());
        }
        outState.putInt(MapParams.MAP_PADDING_LEFT, mMapPaddingLeft);
        outState.putInt(MapParams.MAP_PADDING_TOP, mMapPaddingTop);
        outState.putInt(MapParams.MAP_PADDING_RIGHT, mMapPaddingRight);
        outState.putInt(MapParams.MAP_PADDING_BOTTOM, mMapPaddingBottom);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onViewStateRestored(savedInstanceState);
            }
        }
    }

    public boolean isRouteDisplayed() {
        return MapParams.MODE_ROUTE.equals(mMapMode);
    }

    /**
     * Initialize the Stop Overlay
     *
     * @return true if the overlay was successfully initialized, false if it was not
     */
    public boolean setupStopOverlay() {
        if (mStopOverlay != null) {
            // Overlay was previously initialized and can be used
            return true;
        }
        if (mMap == null) {
            // We need a map reference to initialize the overlay
            return false;
        }
        mStopOverlay = new StopOverlay(mActivity, mMap);
        mStopOverlay.setOnFocusChangeListener(this);
        return true;
    }

    public void setupBikeStationOverlay(boolean isInDirectionsMode) {
        if (mBikeStationOverlay == null && mActivity != null) {
            mBikeStationOverlay = new BikeStationOverlay(mActivity, mMap, isInDirectionsMode);
            mBikeStationOverlay.setOnFocusChangeListener(mOnFocusChangedListener);
        }
    }

    //
    // Fragment Controller
    //
    @Override
    public String getMapMode() {
        if (!"".equals(mMapMode)) {
            return mMapMode;
        }
        return null;
    }

    @Override
    public void setMapMode(String mode, Bundle args) {
        String oldMode = getMapMode();
        if (oldMode != null && oldMode.equals(mode)) {
            for (MapModeController controller : mControllers) {
                controller.setState(args);
            }
            return;
        }
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.destroy();
            }
            mControllers.clear();
        } else {
            mControllers = new ArrayList<>();
        }
        if (mStopOverlay != null) {
            mStopOverlay.clear(false);
        }
        BikeshareMapController bikeshareMapController = new BikeshareMapController(this);
        setupBikeStationOverlay(MapParams.MODE_DIRECTIONS.equals(mode));
        if (MapParams.MODE_ROUTE.equals(mode)) {
            RouteMapController controller = new RouteMapController(this);
            mControllers.add(controller);
            bikeshareMapController.setMode(controller.getMode());
        } else if (MapParams.MODE_STOP.equals(mode)) {
            StopMapController controller = new StopMapController(this);
            mControllers.add(controller);
            bikeshareMapController.setMode(controller.getMode());
        } else if (MapParams.MODE_DIRECTIONS.equals(mode)) {
            DirectionsMapController controller = new DirectionsMapController(this);
            mControllers.add(controller);
            bikeshareMapController.setMode(controller.getMode());
        }
        mControllers.add(bikeshareMapController);
        for (MapModeController controller : mControllers) {
            controller.setState(args);
            controller.onResume();
        }
        mMapMode = mode;
    }

    @Override
    public MapModeController.ObaMapView getMapView() {
        // We implement the ObaMapView interface too.
        return this;
    }

    /**
     * Adds a generic marker to the map and returns the ID associated with that marker, which can
     * be used to remove the marker via removeMarker(). The marker is pushed into the render state
     * and drawn by ObaMapContent, so (unlike the old overlay) it survives until the map is ready.
     */
    @Override
    public int addMarker(Location location, Float hue) {
        return mRenderState.addMarker(
                new GeoPoint(location.getLatitude(), location.getLongitude()), hue);
    }

    /**
     * Removes the marker from the map that has the given ID, which was previously generated by
     * addMarker() in this class. No-op if no such marker exists.
     */
    @Override
    public void removeMarker(int markerId) {
        mRenderState.removeMarker(markerId);
    }

    @Override
    public void setPadding(Integer left, Integer top, Integer right, Integer bottom) {

        if (left != null) {
            mMapPaddingLeft = left;
        }
        if (top != null) {
            mMapPaddingTop = top;
        }
        if (right != null) {
            mMapPaddingRight = right;
        }
        if (bottom != null) {
            mMapPaddingBottom = bottom;
        }

        if (mMap != null) {
            mMap.setPadding(mMapPaddingLeft, mMapPaddingTop, mMapPaddingRight, mMapPaddingBottom);
        }
    }

    @Override
    public void showProgress(boolean show) {
        if (mOnProgressBarChangedListener != null) {
            mOnProgressBarChangedListener.onProgressBarChanged(show);
        }
    }

    @Override
    public void showStops(List<ObaStop> stops, ObaReferences refs) {
        // Make sure that the stop overlay has been successfully initialized
        if (setupStopOverlay() && stops != null) {
            mStopOverlay.populateStops(stops, refs);
            // When we have stops that means we have a valid region to get the weather
            checkRegionWeather(false);
        }
    }

    @Override
    public void showBikeStations(List<BikeRentalStation> bikeStations) {
        setupBikeStationOverlay(MapParams.MODE_DIRECTIONS.equals(mMapMode));
        mBikeStationOverlay.addBikeStations(bikeStations);
    }

    @Override
    public void clearBikeStations() {
        if (mBikeStationOverlay != null) {
            mBikeStationOverlay.clearBikeStations();
        }
    }

    @Override
    public void notifyOutOfRange() {
        //Before we trigger the out of range warning, make sure we have region info
        //or have a API URL that was custom set by the user in via Preferences
        String serverName = Application.get().getCustomApiUrl();
        if (mWarnOutOfRange && (Application.get().getCurrentRegion() != null || !TextUtils.isEmpty(serverName))) {
            if (mRunning && canManageDialog(mActivity)) {
                showOutOfRangeDialog();
            }
        }
        // Notify weather view that we are out of range
        checkRegionWeather(true);
    }

    //
    // Region Task Callback
    //
    @Override
    public void onRegionTaskFinished(boolean currentRegionChanged) {
        if (mDestroyed) {
            // Too early or late in the host lifecycle to take any action
            return;
        }

        Location l = Application.getLastKnownLocation(mActivity);
        // If the region changed, and we don't have a location or the map center is still (0,0),
        // then zoom to the region (or location if we have it)
        Location mapCenter = getMapCenterAsLocation();
        if (currentRegionChanged &&
                (l == null ||
                        (mapCenter != null && mapCenter.getLatitude() == 0.0 &&
                                mapCenter.getLongitude() == 0.0))) {
            if (l != null) {
                setMyLocation(true, false);
            } else {
                zoomToRegion();
                checkRegionWeather(false);
            }
        }
    }

    public void setOnFocusChangeListener(OnFocusChangedListener onFocusChangedListener) {
        mOnFocusChangedListener = onFocusChangedListener;
    }

    public void setOnProgressBarChangedListener(
            OnProgressBarChangedListener onProgressBarChangedListener) {
        mOnProgressBarChangedListener = onProgressBarChangedListener;
    }

    public void setOnLocationPermissionResultListener(OnLocationPermissionResultListener onLocationPermissionResultListener) {
        mOnLocationPermissionResultListener = onLocationPermissionResultListener;
    }

    //
    // Stop changed handler
    //
    final Handler mStopChangedHandler = new Handler();

    public void onFocusChanged(final ObaStop stop, final HashMap<String, ObaRoute> routes,
                               final Location location) {
        // Run in a separate thread, to avoid blocking UI for long running events
        mStopChangedHandler.post(new Runnable() {
            public void run() {
                if (stop != null) {
                    mFocusStopId = stop.getId();
                } else {
                    mFocusStopId = null;
                }
                // Courier the focused stop to the render state so a vehicle info-window tap can deep
                // link into TripDetails scoped to it (the old VehicleOverlay.Controller hook).
                mRenderState.setFocusedStopId(mFocusStopId);

                // Pass overlay focus event up to listeners
                if (mOnFocusChangedListener != null) {
                    mOnFocusChangedListener.onFocusChanged(stop, routes, location);
                }
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean setMyLocation(boolean useDefaultZoom, boolean animateToLocation) {
        if (!LocationUtils.isLocationEnabled(mActivity) && mRunning && UIUtils.canManageDialog(
                mActivity)) {
            // If the user hasn't opted out of "Enable location" dialog, show it to them
            SharedPreferences prefs = Application.getPrefs();
            if (!prefs.getBoolean(mActivity.getString(R.string.preference_key_never_show_location_dialog), false)) {
                showNoLocationDialog();
            }
            return false;
        }

        Location lastLocation = Application.getLastKnownLocation(mActivity);
        if (lastLocation == null) {
            if (!PermissionUtils.hasGrantedAtLeastOnePermission(Application.get(), LOCATION_PERMISSIONS)) {
                if (!PreferenceUtils.userDeniedLocationPermission()) {
                    requestPermissionAndInit(mActivity);
                }
            } else {
                Toast.makeText(mActivity,
                        mActivity.getResources().getString(R.string.main_waiting_for_location),
                        Toast.LENGTH_SHORT).show();

            }
            return false;
        }

        setMyLocation(lastLocation, useDefaultZoom, animateToLocation);
        return true;
    }

    private void setMyLocation(Location l, boolean useDefaultZoom, boolean animateToLocation) {
        if (mMap != null) {
            // Move camera to current location
            CameraPosition.Builder cameraPosition = new CameraPosition.Builder()
                    .target(MapHelpV2.makeLatLng(l));

            if (useDefaultZoom) {
                // Use default zoom level
                cameraPosition.zoom(CAMERA_DEFAULT_ZOOM);
            } else {
                // Use current zoom level
                cameraPosition.zoom(mMap.getCameraPosition().zoom);
            }

            if (animateToLocation) {
                // Smooth animation to position
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition.build()));
            } else {
                // Abrupt change to position
                mMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition.build()));
            }
        }

        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onLocation();
            }
        }
    }

    public void zoomToRegion() {
        // If we have a region, then zoom to it.
        ObaRegion region = Application.get().getCurrentRegion();

        if (region != null && mMap != null) {
            LatLngBounds b = MapHelpV2.getRegionBounds(region);

            // Use screen dimensions to avoid IllegalStateException (#581)
            int width = mActivity.getResources().getDisplayMetrics().widthPixels;
            int height = mActivity.getResources().getDisplayMetrics().heightPixels;
            int padding = 0;
            mMap.animateCamera((CameraUpdateFactory.newLatLngBounds(b, width, height, padding)));
        }
    }

    private RegionCallback regionCallback;

    public void setRegionCallback(RegionCallback callback) {
        this.regionCallback = callback;
    }

    public void checkRegionWeather(boolean isOutOfRange) {
        // If we have a valid region, callback to home activity to get the weather.
        ObaRegion region = Application.get().getCurrentRegion();
        boolean isValid = (region != null && mMap != null && !isOutOfRange);

        if (regionCallback != null) {
            regionCallback.onValidRegion(isValid);
        }
    }

    @Override
    public Location getSouthWest() {
        if (mMap != null) {
            Location southWest = new Location("");
            southWest.setLatitude(mMap.getProjection().getVisibleRegion().latLngBounds.southwest.latitude);
            southWest.setLongitude(mMap.getProjection().getVisibleRegion().latLngBounds.southwest.longitude);
            return southWest;
        }
        return null;
    }

    @Override
    public Location getNorthEast() {
        if (mMap != null) {
            Location northEast = new Location("");
            northEast.setLatitude(mMap.getProjection().getVisibleRegion().latLngBounds.northeast.latitude);
            northEast.setLongitude(mMap.getProjection().getVisibleRegion().latLngBounds.northeast.longitude);
            return northEast;
        }
        return null;
    }

    //
    // MapView interactions
    //

    @Override
    public void setZoom(float zoomLevel) {
        if (mMap != null) {
            mMap.moveCamera(CameraUpdateFactory.zoomTo(zoomLevel));
        }
    }

    @Override
    public Location getMapCenterAsLocation() {
        // If the center is the same as the last call to this method, pass back the same Location
        if (mMap != null) {
            LatLng center = mMap.getCameraPosition().target;
            if (mCenter == null || mCenter != center) {
                mCenter = center;
                mCenterLocation = MapHelpV2.makeLocation(mCenter);
            }
        }
        return mCenterLocation;
    }

    @Override
    public void setMapCenter(Location location, boolean animateToLocation,
                             boolean overlayExpanded) {
        if (mMap != null) {
            CameraPosition cp = mMap.getCameraPosition();

            LatLng target = MapHelpV2.makeLatLng(location);
            LatLng offsetTarget;

            if (isRouteDisplayed() && overlayExpanded) {
                // Adjust camera target if the route header is currently displayed - map padding
                // doesn't get this quite right, as the header is slid up some and full padding doesn't apply
                double percentageOffset = 0.2;
                double bias =
                        (getLongitudeSpanInDecDegrees() * percentageOffset) / 2;
                offsetTarget = new LatLng(target.latitude - bias, target.longitude);
                target = offsetTarget;
            }

            if (animateToLocation) {
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        new CameraPosition.Builder().target(target)
                                .zoom(cp.zoom)
                                .bearing(cp.bearing)
                                .tilt(cp.tilt)
                                .build()
                ));
            } else {
                mMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                        new CameraPosition.Builder().target(target)
                                .zoom(cp.zoom)
                                .bearing(cp.bearing)
                                .tilt(cp.tilt)
                                .build()
                ));
            }
        }
    }

    @Override
    public double getLatitudeSpanInDecDegrees() {
        VisibleRegion vr = mMap.getProjection().getVisibleRegion();
        return Math.abs(vr.latLngBounds.northeast.latitude - vr.latLngBounds.southwest.latitude);
    }

    @Override
    public double getLongitudeSpanInDecDegrees() {
        VisibleRegion vr = mMap.getProjection().getVisibleRegion();
        return Math.abs(vr.latLngBounds.northeast.longitude - vr.latLngBounds.southwest.longitude);
    }

    @Override
    public float getZoomLevelAsFloat() {
        return mMap.getCameraPosition().zoom;
    }

    @Override
    public void setRouteOverlay(int lineOverlayColor, ObaShape[] shapes, boolean clear) {
        // Push the shapes into the shared render state; ObaMapContent draws them as Polyline
        // composables (the directional-arrow stamp + color are applied there). [clear] replaces the
        // current lines (route mode); !clear appends (directions mode renders one leg per call).
        List<RoutePolyline> polylines =
                clear ? new ArrayList<>() : new ArrayList<>(mRenderState.getRoutePolylines());

        int totalPoints = 0;
        for (ObaShape s : shapes) {
            List<GeoPoint> points = new ArrayList<>();
            for (Location l : s.getPoints()) {
                points.add(new GeoPoint(l.getLatitude(), l.getLongitude()));
            }
            polylines.add(new RoutePolyline(lineOverlayColor, points));
            totalPoints += points.size();
        }
        mRenderState.setRoutePolylines(polylines);

        Log.d(TAG, "Total points for route polylines = " + totalPoints);
    }

    @Override
    public void setRouteOverlay(int lineOverlayColor, ObaShape[] shapes) {
        setRouteOverlay(lineOverlayColor, shapes, true);
    }

    @Override
    public void updateVehicles(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
        // Build the marker list (the old VehicleOverlay.MarkerData.populate filter) and push it to the
        // render state; ObaMapContent draws + animates the markers and renders their info windows.
        List<VehicleMarker> markers = new ArrayList<>();
        for (ObaTripDetails trip : response.getTrips()) {
            ObaTripStatus status = trip.getStatus();
            if (status == null) {
                continue;
            }
            // Only show vehicles running a route we asked for, and that aren't CANCELED.
            String activeRoute = response.getTrip(status.getActiveTripId()).getRouteId();
            if (!routeIds.contains(activeRoute) || Status.CANCELED.equals(status.getStatus())) {
                continue;
            }
            Location l = status.getLastKnownLocation();
            boolean isRealtime = true;
            if (l == null) {
                // No extrapolated location available - fall back to the last reported position.
                l = status.getPosition();
                isRealtime = false;
            }
            if (!status.isPredicted()) {
                isRealtime = false;
            }
            markers.add(new VehicleMarker(
                    status.getActiveTripId(),
                    new GeoPoint(l.getLatitude(), l.getLongitude()),
                    isRealtime,
                    status));
        }
        mRenderState.setVehicles(markers, response);
    }

    @Override
    public void removeVehicleOverlay() {
        mRenderState.clearVehicles();
    }

    /**
     * Builds the bounds enclosing the current route/itinerary polylines (now sourced from the render
     * state rather than live Polyline objects), or {@code null} if there are no points to enclose.
     */
    private LatLngBounds routePolylineBounds() {
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        boolean any = false;
        for (RoutePolyline p : mRenderState.getRoutePolylines()) {
            for (GeoPoint pt : p.getPoints()) {
                builder.include(new LatLng(pt.getLatitude(), pt.getLongitude()));
                any = true;
            }
        }
        return any ? builder.build() : null;
    }

    @Override
    public void zoomToRoute() {
        if (mMap != null) {
            LatLngBounds bounds = routePolylineBounds();
            if (bounds != null) {
                if (mActivity != null) {
                    int padding = UIUtils.dpToPixels(mActivity, DEFAULT_MAP_PADDING_DP);
                    mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding));
                }
            } else {
                Toast.makeText(mActivity, mActivity.getString(R.string.route_info_no_shape_data),
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void zoomToItinerary() {
        if (mMap != null) {
            LatLngBounds bounds = routePolylineBounds();
            if (bounds != null && mActivity != null) {
                int padding = UIUtils.dpToPixels(mActivity, DEFAULT_MAP_PADDING_DP);
                mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds,
                        mActivity.getResources().getDisplayMetrics().widthPixels,
                        mActivity.getResources().getDisplayMetrics().heightPixels,
                        padding));
            }
        }
    }

    @Override
    public void zoomIncludeClosestVehicle(HashSet<String> routeIds,
                                          ObaTripsForRouteResponse response) {
        if (mMap == null) {
            return;
        }
        LatLng closestVehicleLocation = MapHelpV2
                .getClosestVehicle(response, routeIds, getMapCenterAsLocation());

        LatLngBounds visibleBounds = mMap.getProjection().getVisibleRegion().latLngBounds;

        if (closestVehicleLocation == null || visibleBounds.contains(closestVehicleLocation)) {
            // Closest vehicle is already in view or is null - don't change camera
            return;
        }

        // Zoom to include current map bounds and closest vehicle location
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        builder.include(visibleBounds.northeast);
        builder.include(visibleBounds.southwest);
        builder.include(closestVehicleLocation);

        if (mActivity != null) {
            int padding = UIUtils.dpToPixels(mActivity, DEFAULT_MAP_PADDING_DP);
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), padding));
        }
    }

    @Override
    public void removeRouteOverlay() {
        mRenderState.clearRoutePolylines();
    }

    @Override
    public void removeStopOverlay(boolean clearFocusedStop) {
        if (mStopOverlay != null) {
            mStopOverlay.clear(clearFocusedStop);
        }
    }

    @Override
    public boolean canWatchMapChanges() {
        // Android Map API v2 has an OnCameraChangeListener
        return true;
    }

    @Override
    public void setFocusStop(ObaStop stop, List<ObaRoute> routes) {
        // Make sure that the stop overlay has been successfully initialized before setting focus
        if (setupStopOverlay()) {
            mStopOverlay.setFocus(stop, routes);
        }
    }

    @Override
    public void onCameraChange(CameraPosition cameraPosition) {
        Log.d(TAG, "onCameraChange");
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.notifyMapChanged();
            }
        }
    }

    // Maps V2 Location updates

    @Override
    public void activate(OnLocationChangedListener listener) {
        mListener = listener;
    }

    @Override
    public void deactivate() {
        mListener = null;
    }

    public void onLocationChanged(Location l) {
        if (mListener != null) {
            // Show real-time location on map
            mListener.onLocationChanged(l);
        }
    }

    @Override
    public void postInvalidate() {
        // Do nothing - calling `this.postInvalidate()` causes a StackOverflowError
    }

    //
    // Dialogs
    //

    private void showOutOfRangeDialog() {
        Drawable icon = mActivity.getResources().getDrawable(android.R.drawable.ic_dialog_map);
        DrawableCompat.setTint(icon, mActivity.getResources().getColor(R.color.theme_primary));

        new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.main_outofrange_title)
                .setIcon(icon)
                .setCancelable(false)
                .setMessage(mActivity.getString(R.string.main_outofrange,
                        Application.get().getCurrentRegion() != null ?
                                Application.get().getCurrentRegion().getName() : ""
                ))
                .setPositiveButton(R.string.main_outofrange_yes,
                        (dialog, which) -> {
                            if (!mDestroyed) {
                                zoomToRegion();
                                checkRegionWeather(false);
                            }
                        }
                )
                .setNegativeButton(R.string.main_outofrange_no,
                        (dialog, which) -> {
                            if (!mDestroyed) {
                                mWarnOutOfRange = false;
                            }
                        }
                )
                .show();
    }

    @SuppressWarnings("deprecation")
    private void showNoLocationDialog() {
        View view = mActivity.getLayoutInflater().inflate(R.layout.no_location_dialog, null);
        CheckBox neverShowDialog = view.findViewById(R.id.location_never_ask_again);

        // Set the dialog text programmatically to support app_name placeholder
        TextView noLocationText = view.findViewById(R.id.no_location_text);
        noLocationText.setText(mActivity.getString(R.string.main_nolocation, mActivity.getString(R.string.app_name)));

        neverShowDialog.setOnCheckedChangeListener((compoundButton, isChecked) ->
                // Save the preference
                PreferenceUtils.saveBoolean(mActivity.getString(R.string.preference_key_never_show_location_dialog), isChecked));

        Drawable icon = mActivity.getResources().getDrawable(android.R.drawable.ic_dialog_map);
        DrawableCompat.setTint(icon, mActivity.getResources().getColor(R.color.theme_primary));

        new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.main_nolocation_title)
                .setIcon(icon)
                .setCancelable(false)
                .setView(view)
                .setPositiveButton(R.string.rt_yes,
                        (dialog, which) -> mActivity.startActivity(
                                new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                )
                .setNegativeButton(R.string.rt_no,
                        (dialog, which) -> {
                            // Ok, I suppose we can just try looking from where we are.
                            if (mControllers != null) {
                                for (MapModeController controller : mControllers) {
                                    controller.onLocation();
                                }
                            }
                        }
                )
                .show();
    }

    /**
     * Class responsible for listening to the clicks on the map or markers and propagating these
     * clicks to the overlays visible on the map.
     */
    private class MapClickListeners implements GoogleMap.OnMarkerClickListener, GoogleMap.OnMapClickListener {

        @Override
        public void onMapClick(LatLng latLng) {
            if (mStopOverlay != null) {
                mStopOverlay.removeMarkerClicked(latLng);
            }

            if (mBikeStationOverlay != null) {
                mBikeStationOverlay.removeMarkerClicked(latLng);
            }
        }

        @Override
        public boolean onMarkerClick(Marker marker) {
            if (mStopOverlay != null) {
                if (mStopOverlay.markerClicked(marker)) {
                    return true;
                }
            }
            if (mBikeStationOverlay != null) {
                if (mBikeStationOverlay.markerClicked(marker)) {
                    return true;
                }
            }
            // Vehicles are maps-compose markers: returning false lets the SDK show their info window
            // via maps-compose's adapter (see ObaMapContent).
            return false;
        }
    }

    /**
     * Shows the dialog to explain why location permissions are needed.  If this provided activity
     * can't manage dialogs then this method is a no-op.
     */
    public void showLocationPermissionDialog() {
        if (!canManageDialog(mActivity)) {
            return;
        }
        if (locationPermissionDialog != null && locationPermissionDialog.isShowing()) {
            return;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.location_permissions_title)
                .setMessage(R.string.location_permissions_message)
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        (dialog, which) -> {
                            PreferenceUtils.setUserDeniedLocationPermissions(false);
                            // Request permissions from the user (via the owner's launcher)
                            mDeps.requestLocationPermission();
                        }
                )
                .setNegativeButton(R.string.no_thanks,
                        (dialog, which) -> {
                            if (mOnLocationPermissionResultListener != null) {
                                mUserDeniedPermission = true;
                                PreferenceUtils.setUserDeniedLocationPermissions(true);
                                mOnLocationPermissionResultListener.onLocationPermissionResult(PackageManager.PERMISSION_DENIED);
                            }
                        }
                );
        locationPermissionDialog = builder.create();
        locationPermissionDialog.show();
    }

    /**
     * Updates the map settings based on the current state of map mode preference.
     */
    private void updateMapModeSettings() {
        if (mMap == null) return;

        String normal2D = mActivity.getString(R.string.preferences_preferred_map_option_normal2d);
        String normal3D = mActivity.getString(R.string.preferences_preferred_map_option_normal3d);
        String satellite = mActivity.getString(R.string.preferences_preferred_map_option_satellite);

        String mapType = Application.getPrefs().getString(mActivity.getString(R.string.preference_key_map_mode), normal2D);

        if (mapType.equals(normal2D)) {
            setMapType(GoogleMap.MAP_TYPE_NORMAL, false, false);
        } else if (mapType.equals(normal3D)) {
            if (mMap.getMapType() == GoogleMap.MAP_TYPE_HYBRID) {
                mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
            }
            setMapType(GoogleMap.MAP_TYPE_NORMAL, true, true);
        } else if (mapType.equals(satellite)) {
            setMapType(GoogleMap.MAP_TYPE_HYBRID, false, false);
        } else {
            return; // Should never happen
        }

        resetCameraTilt();
    }

    /**
     * Sets the map type, tilt gestures, and 3D buildings visibility for the Google Map.
     */
    private void setMapType(int type, boolean tiltEnabled, boolean buildingsEnabled) {
        mMap.setMapType(type);
        mMap.getUiSettings().setTiltGesturesEnabled(tiltEnabled);
        mMap.setBuildingsEnabled(buildingsEnabled);
    }

    /**
     * Resets camera tilt to defaults (0 degrees)
     */
    private void resetCameraTilt() {
        mMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition.Builder()
                        .target(mMap.getCameraPosition().target)
                        .zoom(mMap.getCameraPosition().zoom)
                        .tilt(0)
                        .build()
        ));
    }
}
