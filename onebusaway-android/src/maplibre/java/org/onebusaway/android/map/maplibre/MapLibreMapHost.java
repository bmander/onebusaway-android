/*
 * Copyright (C) 2011-2024 Paul Watts (paulcwatts@gmail.com),
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
package org.onebusaway.android.map.maplibre;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.OnMapReadyCallback;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.UiSettings;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.ObaAnalytics;
import org.onebusaway.android.io.PlausibleAnalytics;
import org.onebusaway.android.io.elements.ObaReferences;
import org.onebusaway.android.io.elements.ObaRegion;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaShape;
import org.onebusaway.android.io.elements.ObaStop;
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
import org.onebusaway.android.map.MapNavigation;
import org.onebusaway.android.map.MapViewModel;
import org.onebusaway.android.map.render.BikeMarker;
import org.onebusaway.android.map.render.GeoPoint;
import org.onebusaway.android.map.render.MapRenderState;
import org.onebusaway.android.map.render.RoutePolyline;
import org.onebusaway.android.map.render.StopMarker;
import org.onebusaway.android.map.render.VehicleMarker;
import org.onebusaway.android.region.ObaRegionsTask;
import org.onebusaway.android.ui.weather.RegionCallback;
import org.onebusaway.android.util.LayerUtils;
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
import android.os.Looper;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.drawable.DrawableCompat;

import static org.onebusaway.android.util.PermissionUtils.LOCATION_PERMISSIONS;
import static org.onebusaway.android.util.UIUtils.canManageDialog;

/**
 * The non-fragment MapLibre host: the extracted body of {@link MapLibreMapFragment}. It owns a raw
 * {@link MapView} (instead of inheriting from the SDK's {@code SupportMapFragment}), drives the
 * {@link MapModeController} set + overlays imperatively, and forwards the MapView's explicit lifecycle
 * (the owner hands it {@code onStart/onResume/...}). The hosting Activity mounts {@link #getView()};
 * location-permission requests go through {@link MapHostDeps} (the owner's {@code ActivityResultLauncher}).
 * {@link MapLibreMapFragment} is now a thin wrapper around this class for the other (still-fragment)
 * map screens.
 */
public class MapLibreMapHost
        implements ObaMapHost, MapModeController.Callback, ObaRegionsTask.Callback,
        MapModeController.ObaMapView,
        LocationHelper.Listener,
        OnMapReadyCallback, LayerActivationListener {

    private static final String TAG = "MapLibreMapHost";

    private static final String STYLE_URL_LIGHT = "https://tiles.openfreemap.org/styles/liberty";
    private static final String STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark";

    public static final float CAMERA_DEFAULT_ZOOM = 16.0f;

    public static final float DEFAULT_MAP_PADDING_DP = 20.0f;

    // The hosting environment, supplied by the owner (Activity or thin fragment wrapper).
    private final Activity mActivity;

    private final MapHostDeps mDeps;

    private final MapView mMapView;

    private boolean mDestroyed = false;

    private int mMapPaddingLeft = 0;
    private int mMapPaddingTop = 0;
    private int mMapPaddingRight = 0;
    private int mMapPaddingBottom = 0;

    private MapLibreMap mMap;

    private String mFocusStopId;

    // The Activity-scoped view model owns the render model + data-shaping logic (shared with the
    // Google host); this host delegates ObaMapView mutations to it and re-renders mRenderState
    // (= the VM's render state) via the maplibre renderer.
    private final MapViewModel mViewModel;

    private final MapRenderState mRenderState;

    private MapLibreRenderer mRenderer;

    private boolean mWarnOutOfRange = true;
    private boolean mRunning = false;

    private List<MapModeController> mControllers;
    private String mMapMode = "";

    private LatLng mCenter;
    private Location mCenterLocation;

    OnFocusChangedListener mOnFocusChangedListener;
    OnProgressBarChangedListener mOnProgressBarChangedListener;
    OnLocationPermissionResultListener mOnLocationPermissionResultListener;

    LocationHelper mLocationHelper;
    Bundle mLastSavedInstanceState;

    private boolean mUserDeniedPermission = false;

    private AlertDialog locationPermissionDialog;

    private RegionCallback regionCallback;

    public MapLibreMapHost(Activity activity, MapHostDeps deps, Bundle args) {
        mActivity = activity;
        mDeps = deps;
        // Activity-scoped view model: owns the render state (so it survives configuration changes)
        // and the data-shaping logic shared with the Google host.
        mViewModel = new ViewModelProvider((ViewModelStoreOwner) activity).get(MapViewModel.class);
        mRenderState = mViewModel.getRenderState();

        // Initialize MapLibre before any map usage
        MapLibre.getInstance(activity);

        mUserDeniedPermission = PreferenceUtils.userDeniedLocationPermission();
        mLocationHelper = new LocationHelper(activity);

        mLastSavedInstanceState = args;

        // Build the raw MapView (replacing SupportMapFragment) and drive its lifecycle explicitly.
        mMapView = new MapView(activity);
        mMapView.onCreate(args);
        mMapView.getMapAsync(this);

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
        return mMapView;
    }

    // ============================================================================================
    // LayerActivationListener
    // ============================================================================================

    @Override
    public void onActivateLayer(LayerInfo layer) {
        switch (layer.getLayerlabel()) {
            case "Bikeshare": {
                for (MapModeController controller : mControllers) {
                    if (controller instanceof BikeshareMapController) {
                        ((BikeshareMapController) controller).showBikes(true);
                        ObaAnalytics.reportUiEvent(null,
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
                        ObaAnalytics.reportUiEvent(null,
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

    // ============================================================================================
    // Map setup
    // ============================================================================================

    private boolean inDarkMode() {
        return AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES || (
                AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_NO &&
                        (mActivity.getResources().getConfiguration().uiMode
                                & Configuration.UI_MODE_NIGHT_MASK)
                                == Configuration.UI_MODE_NIGHT_YES
        );
    }

    @Override
    public void onMapReady(@NonNull MapLibreMap map) {
        mMap = map;

        String styleUrl = inDarkMode() ? STYLE_URL_DARK : STYLE_URL_LIGHT;
        mMap.setStyle(new Style.Builder().fromUri(styleUrl), style -> {
            initMap(mLastSavedInstanceState);

            // Setup location component after style is loaded
            if (!mUserDeniedPermission) {
                setupLocationComponent(style);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void setupLocationComponent(Style style) {
        if (mMap == null) {
            return;
        }
        if (PermissionUtils.hasGrantedAtLeastOnePermission(mActivity, LOCATION_PERMISSIONS)) {
            LocationComponent locationComponent = mMap.getLocationComponent();
            locationComponent.activateLocationComponent(
                    LocationComponentActivationOptions.builder(mActivity, style).build());
            locationComponent.setLocationComponentEnabled(true);
            locationComponent.setCameraMode(CameraMode.NONE);
            locationComponent.setRenderMode(RenderMode.COMPASS);
            mLocationHelper.registerListener(this);
        } else {
            showLocationPermissionDialog();
        }
    }

    private void initMap(Bundle savedInstanceState) {
        UiSettings uiSettings = mMap.getUiSettings();
        mUserDeniedPermission = PreferenceUtils.userDeniedLocationPermission();

        // Listen for camera changes
        mMap.addOnCameraMoveListener(() -> {
            Log.d(TAG, "onCameraMove");
            if (mControllers != null) {
                for (MapModeController controller : mControllers) {
                    controller.notifyMapChanged();
                }
            }
        });

        // The renderer draws the shared MapRenderState onto the map via classic annotations.
        mRenderer = new MapLibreRenderer(mMap, mActivity, mRenderState);

        // Map / marker clicks. A stop tap focuses + recenters; a tap on empty map clears focus;
        // vehicle/bike taps fall through (return false) so the classic title/snippet info window
        // shows (the rich Google Compose info windows are a Google-flavor enhancement). Tapping that
        // info window deep links via the shared MapNavigation (same policy as the Google host).
        mMap.addOnMapClickListener(point -> {
            onMapTapped(point);
            return false;
        });

        mMap.setOnMarkerClickListener(marker -> {
            StopMarker stop = mRenderer.stopForMarker(marker);
            if (stop != null) {
                onStopTapped(stop.getStop());
                return true;
            }
            return false;
        });

        mMap.setOnInfoWindowClickListener(marker -> {
            VehicleMarker vehicle = mRenderer.vehicleForMarker(marker);
            if (vehicle != null) {
                MapNavigation.openVehicleTripDetails(mActivity, vehicle.getStatus(), mFocusStopId);
                return true;
            }
            BikeMarker bike = mRenderer.bikeForMarker(marker);
            if (bike != null) {
                MapNavigation.openBikeDeepLink(mActivity, bike.getStation());
                return true;
            }
            return false;
        });

        // Hide the default compass (we rely on the app's own controls)
        uiSettings.setCompassEnabled(false);

        if (savedInstanceState != null) {
            initMapState(savedInstanceState);
        } else {
            Bundle args = mActivity.getIntent().getExtras();
            if (args == null) {
                args = new Bundle();
            }
            double lat = args.getDouble(MapParams.CENTER_LAT, 0.0d);
            double lon = args.getDouble(MapParams.CENTER_LON, 0.0d);
            if (lat == 0.0d && lon == 0.0d) {
                PreferenceUtils.maybeRestoreMapViewToBundle(args);
            }
            initMapState(args);
        }
    }

    private void initMapState(Bundle args) {
        mFocusStopId = args.getString(MapParams.STOP_ID);

        mMapPaddingLeft = args.getInt(MapParams.MAP_PADDING_LEFT, MapParams.DEFAULT_MAP_PADDING);
        mMapPaddingTop = args.getInt(MapParams.MAP_PADDING_TOP, MapParams.DEFAULT_MAP_PADDING);
        mMapPaddingRight = args.getInt(MapParams.MAP_PADDING_RIGHT, MapParams.DEFAULT_MAP_PADDING);
        mMapPaddingBottom = args.getInt(MapParams.MAP_PADDING_BOTTOM, MapParams.DEFAULT_MAP_PADDING);
        setPadding(mMapPaddingLeft, mMapPaddingTop, mMapPaddingRight, mMapPaddingBottom);

        String mode = args.getString(MapParams.MODE);
        if (mode == null) {
            mode = MapParams.MODE_STOP;
        }
        setMapMode(mode, args);
    }

    // ============================================================================================
    // Lifecycle (forwarded by the owner; the raw MapView's lifecycle is driven here)
    // ============================================================================================

    @Override
    public void onStart() {
        mMapView.onStart();
    }

    @Override
    public void onResume() {
        mUserDeniedPermission = PreferenceUtils.userDeniedLocationPermission();
        if (mLocationHelper != null) {
            mLocationHelper.onResume();
        }
        mRunning = true;
        mMapView.onResume();

        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onResume();
                controller.notifyMapChanged();
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
        mMapView.onPause();
    }

    @Override
    public void onStop() {
        mMapView.onStop();
    }

    @Override
    public void onLowMemory() {
        mMapView.onLowMemory();
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
        if (mMap != null && center != null) {
            outState.putDouble(MapParams.CENTER_LAT, center.getLatitude());
            outState.putDouble(MapParams.CENTER_LON, center.getLongitude());
            outState.putFloat(MapParams.ZOOM, getZoomLevelAsFloat());
        }
        outState.putInt(MapParams.MAP_PADDING_LEFT, mMapPaddingLeft);
        outState.putInt(MapParams.MAP_PADDING_TOP, mMapPaddingTop);
        outState.putInt(MapParams.MAP_PADDING_RIGHT, mMapPaddingRight);
        outState.putInt(MapParams.MAP_PADDING_BOTTOM, mMapPaddingBottom);

        mMapView.onSaveInstanceState(outState);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onViewStateRestored(savedInstanceState);
            }
        }
    }

    @Override
    public void onHidden(boolean hidden) {
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onHidden(hidden);
            }
        }
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        if (mLocationHelper != null) {
            mLocationHelper.unregisterListener(this);
        }
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.destroy();
            }
        }
        mMapView.onDestroy();
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onLocationPermissionResult(int grantResult) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            mUserDeniedPermission = false;
            // Enable location display
            if (mMap != null) {
                Style style = mMap.getStyle();
                if (style != null) {
                    setupLocationComponent(style);
                }
            }
        } else {
            mUserDeniedPermission = true;
        }

        if (mOnLocationPermissionResultListener != null) {
            mOnLocationPermissionResultListener.onLocationPermissionResult(grantResult);
        }
    }

    // ============================================================================================
    // ObaMapFragment interface
    // ============================================================================================

    @Override
    public void zoomIn() {
        if (mMap != null) {
            mMap.animateCamera(CameraUpdateFactory.zoomIn());
        }
    }

    @Override
    public void zoomOut() {
        if (mMap != null) {
            mMap.animateCamera(CameraUpdateFactory.zoomOut());
        }
    }

    @Override
    public void setOnFocusChangeListener(OnFocusChangedListener listener) {
        mOnFocusChangedListener = listener;
    }

    @Override
    public void setOnProgressBarChangedListener(OnProgressBarChangedListener listener) {
        mOnProgressBarChangedListener = listener;
    }

    @Override
    public void setOnLocationPermissionResultListener(OnLocationPermissionResultListener listener) {
        mOnLocationPermissionResultListener = listener;
    }

    @Override
    public void setRegionCallback(RegionCallback callback) {
        this.regionCallback = callback;
    }

    @Override
    public MapModeController.ObaMapView getMapView() {
        return this;
    }

    // ============================================================================================
    // MapModeController.Callback
    // ============================================================================================

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
        // Switching modes clears accumulated stops but keeps the focused one (old clear(false)).
        mViewModel.clearStops(false);
        rerender();
        BikeshareMapController bikeshareMapController = new BikeshareMapController(this);
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

    public boolean isRouteDisplayed() {
        return MapParams.MODE_ROUTE.equals(mMapMode);
    }

    @Override
    public void showProgress(boolean show) {
        if (mOnProgressBarChangedListener != null) {
            mOnProgressBarChangedListener.onProgressBarChanged(show);
        }
    }

    @Override
    public void showStops(List<ObaStop> stops, ObaReferences refs) {
        if (stops == null) {
            return;
        }
        mViewModel.showStops(stops, refs);
        rerender();
        checkRegionWeather(false);
    }

    @Override
    public void showBikeStations(List<BikeRentalStation> bikeStations) {
        boolean bikeshareVisible = MapParams.MODE_DIRECTIONS.equals(mMapMode)
                || LayerUtils.isBikeshareLayerVisible();
        mViewModel.showBikeStations(bikeStations, bikeshareVisible);
        rerender();
    }

    @Override
    public void clearBikeStations() {
        mViewModel.clearBikeStations();
        rerender();
    }

    @Override
    public void notifyOutOfRange() {
        String serverName = Application.get().getCustomApiUrl();
        if (mWarnOutOfRange && (Application.get().getCurrentRegion() != null
                || !TextUtils.isEmpty(serverName))) {
            if (mRunning && canManageDialog(mActivity)) {
                showOutOfRangeDialog();
            }
        }
        checkRegionWeather(true);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean setMyLocation(boolean useDefaultZoom, boolean animateToLocation) {
        if (!LocationUtils.isLocationEnabled(mActivity) && mRunning
                && UIUtils.canManageDialog(mActivity)) {
            SharedPreferences prefs = Application.getPrefs();
            if (!prefs.getBoolean(mActivity.getString(
                    R.string.preference_key_never_show_location_dialog), false)) {
                showNoLocationDialog();
            }
            return false;
        }

        Location lastLocation = Application.getLastKnownLocation(mActivity);
        if (lastLocation == null) {
            if (!PermissionUtils.hasGrantedAtLeastOnePermission(Application.get(), LOCATION_PERMISSIONS)) {
                if (!PreferenceUtils.userDeniedLocationPermission()) {
                    if (!mUserDeniedPermission) {
                        showLocationPermissionDialog();
                    }
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
            CameraPosition.Builder cameraPosition = new CameraPosition.Builder()
                    .target(MapHelpMapLibre.makeLatLng(l));

            if (useDefaultZoom) {
                cameraPosition.zoom(CAMERA_DEFAULT_ZOOM);
            } else {
                cameraPosition.zoom(mMap.getCameraPosition().zoom);
            }

            if (animateToLocation) {
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition.build()));
            } else {
                mMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition.build()));
            }
        }

        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onLocation();
            }
        }
    }

    @Override
    public void zoomToRegion() {
        ObaRegion region = Application.get().getCurrentRegion();

        if (region != null && mMap != null) {
            LatLngBounds b = MapHelpMapLibre.getRegionBounds(region);
            int padding = 0;
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b, padding));
        }
    }

    @Override
    public Location getSouthWest() {
        if (mMap != null) {
            LatLngBounds bounds = mMap.getProjection().getVisibleRegion().latLngBounds;
            Location southWest = new Location("");
            southWest.setLatitude(bounds.getLatSouth());
            southWest.setLongitude(bounds.getLonWest());
            return southWest;
        }
        return null;
    }

    @Override
    public Location getNorthEast() {
        if (mMap != null) {
            LatLngBounds bounds = mMap.getProjection().getVisibleRegion().latLngBounds;
            Location northEast = new Location("");
            northEast.setLatitude(bounds.getLatNorth());
            northEast.setLongitude(bounds.getLonEast());
            return northEast;
        }
        return null;
    }

    // ============================================================================================
    // ObaRegionsTask.Callback
    // ============================================================================================

    @Override
    public void onRegionTaskFinished(boolean currentRegionChanged) {
        if (mDestroyed) {
            return;
        }

        Location l = Application.getLastKnownLocation(mActivity);
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

    // ============================================================================================
    // ObaMapView implementation
    // ============================================================================================

    @Override
    public void setZoom(float zoomLevel) {
        if (mMap != null) {
            mMap.moveCamera(CameraUpdateFactory.zoomTo(zoomLevel));
        }
    }

    @Override
    public Location getMapCenterAsLocation() {
        if (mMap != null) {
            LatLng center = mMap.getCameraPosition().target;
            if (mCenter == null || !mCenter.equals(center)) {
                mCenter = center;
                mCenterLocation = MapHelpMapLibre.makeLocation(mCenter);
            }
        }
        return mCenterLocation;
    }

    @Override
    public void setMapCenter(Location location, boolean animateToLocation,
                             boolean overlayExpanded) {
        if (mMap != null) {
            CameraPosition cp = mMap.getCameraPosition();

            LatLng target = MapHelpMapLibre.makeLatLng(location);

            if (isRouteDisplayed() && overlayExpanded) {
                double percentageOffset = 0.2;
                double bias = (getLongitudeSpanInDecDegrees() * percentageOffset) / 2;
                target = new LatLng(target.getLatitude() - bias, target.getLongitude());
            }

            CameraPosition newPos = new CameraPosition.Builder()
                    .target(target)
                    .zoom(cp.zoom)
                    .bearing(cp.bearing)
                    .tilt(cp.tilt)
                    .build();

            if (animateToLocation) {
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(newPos));
            } else {
                mMap.moveCamera(CameraUpdateFactory.newCameraPosition(newPos));
            }
        }
    }

    @Override
    public double getLatitudeSpanInDecDegrees() {
        if (mMap != null) {
            LatLngBounds bounds = mMap.getProjection().getVisibleRegion().latLngBounds;
            return Math.abs(bounds.getLatNorth() - bounds.getLatSouth());
        }
        return 0;
    }

    @Override
    public double getLongitudeSpanInDecDegrees() {
        if (mMap != null) {
            LatLngBounds bounds = mMap.getProjection().getVisibleRegion().latLngBounds;
            return Math.abs(bounds.getLonEast() - bounds.getLonWest());
        }
        return 0;
    }

    @Override
    public float getZoomLevelAsFloat() {
        if (mMap != null) {
            return (float) mMap.getCameraPosition().zoom;
        }
        return 0;
    }

    @Override
    public void setRouteOverlay(int lineOverlayColor, ObaShape[] shapes, boolean clear) {
        mViewModel.setRoute(lineOverlayColor, shapes, clear);
        rerender();
    }

    @Override
    public void setRouteOverlay(int lineOverlayColor, ObaShape[] shapes) {
        setRouteOverlay(lineOverlayColor, shapes, true);
    }

    @Override
    public void updateVehicles(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
        mViewModel.updateVehicles(routeIds, response);
        rerender();
    }

    @Override
    public void removeVehicleOverlay() {
        mViewModel.clearVehicles();
        rerender();
    }

    @Override
    public void zoomToRoute() {
        LatLngBounds bounds = routePolylineBounds();
        if (mMap != null && bounds != null) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0));
        }
    }

    @Override
    public void zoomToItinerary() {
        zoomToRoute();
    }

    @Override
    public void zoomIncludeClosestVehicle(HashSet<String> routeIds,
                                          ObaTripsForRouteResponse response) {
        // Best-effort on maplibre: frame the route shape (closest-vehicle inclusion is a refinement).
        zoomToRoute();
    }

    @Override
    public void removeRouteOverlay() {
        mViewModel.clearRoute();
        rerender();
    }

    @Override
    public void removeStopOverlay(boolean clearFocusedStop) {
        mViewModel.clearStops(clearFocusedStop);
        if (clearFocusedStop) {
            mFocusStopId = null;
        }
        rerender();
    }

    @Override
    public boolean canWatchMapChanges() {
        return true;
    }

    @Override
    public void setFocusStop(ObaStop stop, List<ObaRoute> routes) {
        mViewModel.setFocusStop(stop, routes);
        mFocusStopId = (stop != null) ? stop.getId() : null;
        rerender();
    }

    @Override
    public int addMarker(Location location, Float hue) {
        int id = mViewModel.addMarker(location.getLatitude(), location.getLongitude(), hue);
        rerender();
        return id;
    }

    @Override
    public void removeMarker(int markerId) {
        mViewModel.removeMarker(markerId);
        rerender();
    }

    @Override
    public void setPadding(Integer left, Integer top, Integer right, Integer bottom) {
        if (left != null) mMapPaddingLeft = left;
        if (top != null) mMapPaddingTop = top;
        if (right != null) mMapPaddingRight = right;
        if (bottom != null) mMapPaddingBottom = bottom;

        // MapLibre doesn't have a direct setPadding() on the map object.
        // Padding is applied through camera updates and content insets.
    }

    @Override
    public void postInvalidate() {
        // No-op for MapLibre
    }

    // ============================================================================================
    // Render-state helpers + marker taps
    // ============================================================================================

    private void rerender() {
        if (mRenderer != null) {
            mRenderer.render();
        }
    }

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

    /** A stop tap: focus + notify listeners, then animate the camera onto it. */
    private void onStopTapped(ObaStop stop) {
        onFocusChanged(stop, mViewModel.cachedRoutes(), stop.getLocation());
        if (mMap != null) {
            LatLng pos = MapHelpMapLibre.makeLatLng(stop.getLocation());
            double currentZoom = mMap.getCameraPosition().zoom;
            if (currentZoom < CAMERA_DEFAULT_ZOOM) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, CAMERA_DEFAULT_ZOOM));
            } else {
                mMap.animateCamera(CameraUpdateFactory.newLatLng(pos));
            }
        }
    }

    /** A tap on empty map clears the focused stop. */
    private void onMapTapped(LatLng point) {
        Location location = point != null ? MapHelpMapLibre.makeLocation(point) : null;
        onFocusChanged(null, null, location);
    }

    // ============================================================================================
    // Stop focus notification (was the StopOverlay.OnFocusChangedListener callback)
    // ============================================================================================

    final Handler mStopChangedHandler = new Handler(Looper.getMainLooper());

    public void onFocusChanged(final ObaStop stop, final HashMap<String, ObaRoute> routes,
                               final Location location) {
        mStopChangedHandler.post(() -> {
            mFocusStopId = (stop != null) ? stop.getId() : null;
            mRenderState.setFocusedStopId(mFocusStopId);
            rerender();
            if (mOnFocusChangedListener != null) {
                mOnFocusChangedListener.onFocusChanged(stop, routes, location);
            }
        });
    }

    // ============================================================================================
    // LocationHelper.Listener
    // ============================================================================================

    @Override
    public void onLocationChanged(Location l) {
        if (mControllers != null) {
            for (MapModeController controller : mControllers) {
                controller.onLocation();
            }
        }
    }

    // ============================================================================================
    // Weather / region helpers
    // ============================================================================================

    public void checkRegionWeather(boolean isOutOfRange) {
        ObaRegion region = Application.get().getCurrentRegion();
        boolean isValid = (region != null && mMap != null && !isOutOfRange);
        if (regionCallback != null) {
            regionCallback.onValidRegion(isValid);
        }
    }

    // ============================================================================================
    // Dialogs
    // ============================================================================================

    @SuppressWarnings("deprecation")
    private void showOutOfRangeDialog() {
        Drawable icon = mActivity.getResources().getDrawable(android.R.drawable.ic_dialog_map);
        DrawableCompat.setTint(icon, mActivity.getResources().getColor(R.color.theme_primary));

        new AlertDialog.Builder(mActivity)
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

        TextView noLocationText = view.findViewById(R.id.no_location_text);
        noLocationText.setText(mActivity.getString(R.string.main_nolocation,
                mActivity.getString(R.string.app_name)));

        neverShowDialog.setOnCheckedChangeListener((compoundButton, isChecked) ->
                PreferenceUtils.saveBoolean(mActivity.getString(
                        R.string.preference_key_never_show_location_dialog), isChecked));

        Drawable icon = mActivity.getResources().getDrawable(android.R.drawable.ic_dialog_map);
        DrawableCompat.setTint(icon, mActivity.getResources().getColor(R.color.theme_primary));

        new AlertDialog.Builder(mActivity)
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
                            if (mControllers != null) {
                                for (MapModeController controller : mControllers) {
                                    controller.onLocation();
                                }
                            }
                        }
                )
                .show();
    }

    public void showLocationPermissionDialog() {
        if (!canManageDialog(mActivity)) {
            return;
        }
        if (locationPermissionDialog != null && locationPermissionDialog.isShowing()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity)
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
                                mOnLocationPermissionResultListener.onLocationPermissionResult(
                                        PackageManager.PERMISSION_DENIED);
                            }
                        }
                );
        locationPermissionDialog = builder.create();
        locationPermissionDialog.show();
    }
}
