/*
 * Copyright (C) 2026 Open Transit Software Foundation
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
package org.onebusaway.android.map;

import android.app.Activity;
import android.location.Location;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;

import org.onebusaway.android.BuildConfig;
import org.onebusaway.android.map.compose.ObaMapCallbacks;
import org.onebusaway.android.map.compose.ObaMapReadyListener;
import org.onebusaway.android.region.ObaRegionsTask;
import org.onebusaway.android.ui.weather.RegionCallback;

/**
 * Provider-agnostic, non-fragment host for the native map. This is the {@link ObaMapFragment}
 * map-operation surface (minus {@code asFragment()}) plus an explicit lifecycle the owner forwards,
 * so a host activity can mount {@link #getView()} directly instead of via a {@code FragmentManager}.
 *
 * The flavor-specific implementations ({@code GoogleMapHost} / {@code MapLibreMapHost}) own the map
 * view and the {@link MapModeController} set (they implement {@link MapModeController.Callback},
 * returning the held {@code Activity}/view); {@link ObaMapFragment} now delegates to a host so the
 * other map screens keep working unchanged.
 */
public interface ObaMapHost extends MapModeController.ObaMapView, ObaMapReadyListener,
        LayerActivationListener {

    /** Default camera zoom for the seed camera (matches the flavor hosts' own default). */
    float CAMERA_DEFAULT_ZOOM = 16.0f;

    // ========================================================================
    // View + map operations (mirror ObaMapFragment minus asFragment())
    // ========================================================================

    /**
     * The host's map view, or {@code null} in controller mode (see {@link #newController}) where the
     * owner composes the map itself via {@code ObaMap()} and the host only drives it via the handle
     * delivered to {@link #onMapReady}.
     */
    View getView();

    MapModeController.ObaMapView getMapView();

    void setMapMode(String mode, Bundle args);

    boolean setMyLocation(boolean useDefaultZoom, boolean animateToLocation);

    void zoomIn();

    void zoomOut();

    // ========================================================================
    // Listeners (shared map-callback interfaces — same types the owner implements)
    // ========================================================================

    void setOnFocusChangeListener(OnFocusChangedListener listener);

    void setOnProgressBarChangedListener(OnProgressBarChangedListener listener);

    void setOnLocationPermissionResultListener(OnLocationPermissionResultListener listener);

    void setRegionCallback(RegionCallback callback);

    /**
     * The flavor-specific tap callbacks to hand the {@code ObaMap()} composable when the owner
     * composes the map itself (controller mode): the Google host returns {@code this} (it dispatches
     * declarative marker/map taps); the maplibre host returns {@code null} (it wires listeners on the
     * raw map). Returns {@code null} in view-owning mode (the host already passed these to its own view).
     */
    @Nullable
    ObaMapCallbacks getMapCallbacks();

    // ========================================================================
    // Explicit lifecycle the owner forwards (replacing the Fragment's)
    // ========================================================================

    void onStart();

    void onResume();

    void onPause();

    void onStop();

    void onLowMemory();

    void onSaveInstanceState(Bundle outState);

    void onRestoreInstanceState(Bundle savedInstanceState);

    void onDestroy();

    /** Forwarded show/hide for tab-style hosts (the old {@code Fragment.onHiddenChanged}). */
    void onHidden(boolean hidden);

    /**
     * Delivers the result of a location-permission request the host asked for via
     * {@link MapHostDeps#requestLocationPermission()}. {@code grantResult} is one of
     * {@code PackageManager.PERMISSION_GRANTED}/{@code PERMISSION_DENIED}.
     */
    void onLocationPermissionResult(int grantResult);

    // ========================================================================
    // Factory
    // ========================================================================

    /**
     * Creates the flavor-specific host in view-owning mode (it builds its own map view, returned by
     * {@link #getView()}). Used by the fragment screens. The concrete class name is provided by
     * {@link BuildConfig#MAP_HOST_CLASS}; it must declare an {@code (Activity, MapHostDeps, Bundle)}
     * constructor.
     */
    static ObaMapHost newInstance(Activity activity, MapHostDeps deps, Bundle args) {
        try {
            return (ObaMapHost) Class.forName(BuildConfig.MAP_HOST_CLASS)
                    .getDeclaredConstructor(Activity.class, MapHostDeps.class, Bundle.class)
                    .newInstance(activity, deps, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Map host implementation not found: "
                    + BuildConfig.MAP_HOST_CLASS, e);
        }
    }

    /**
     * Creates the flavor-specific host in controller mode: it does NOT build a view ({@link #getView()}
     * returns {@code null}). The owner composes the map itself via {@code ObaMap()} and hands the host
     * the ready map through {@link #onMapReady}. Used by HomeActivity. Requires the host to declare an
     * {@code (Activity, MapHostDeps, Bundle, boolean ownView)} constructor.
     */
    static ObaMapHost newController(Activity activity, MapHostDeps deps, Bundle args) {
        try {
            return (ObaMapHost) Class.forName(BuildConfig.MAP_HOST_CLASS)
                    .getDeclaredConstructor(Activity.class, MapHostDeps.class, Bundle.class, boolean.class)
                    .newInstance(activity, deps, args, false);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Map host implementation not found: "
                    + BuildConfig.MAP_HOST_CLASS, e);
        }
    }

    /**
     * Resolves the initial map camera (lat, lon, zoom) from saved state, then intent extras — the seed
     * the owner passes to {@code ObaMap()} to avoid an initial flash before the map centers itself.
     */
    static double[] resolveInitialCamera(Activity activity, Bundle savedInstanceState) {
        Bundle src = savedInstanceState;
        if (src == null && activity != null) {
            src = activity.getIntent().getExtras();
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

    // ========================================================================
    // Declarative map commands (carried out via the existing ObaMapView/controller methods)
    // ========================================================================

    /**
     * Carries out a {@link MapCommand} the view models dispatched via {@link MapViewModel#mapCommands}.
     * This is the host-side home of the imperative map operations HomeActivity used to perform in its
     * event relay (recenter, focus, route-mode switch, region re-zoom) — now centralized here so the
     * activity only wires the flow to this method. A {@code default} method since both flavor hosts
     * implement the same {@code ObaMapView}/{@code ObaRegionsTask.Callback} surface it calls.
     */
    default void executeMapCommand(MapCommand command) {
        if (command instanceof MapCommand.Recenter) {
            MapCommand.Recenter c = (MapCommand.Recenter) command;
            Location loc = new Location("");
            loc.setLatitude(c.getLat());
            loc.setLongitude(c.getLon());
            setMapCenter(loc, true, true);
        } else if (command instanceof MapCommand.FocusStop) {
            MapCommand.FocusStop c = (MapCommand.FocusStop) command;
            setMapCenter(c.getStop().getLocation(), false, c.getOverlayExpanded());
            setFocusStop(c.getStop(), c.getRoutes());
        } else if (command instanceof MapCommand.ClearFocus) {
            setFocusStop(null, null);
        } else if (command instanceof MapCommand.ShowRoute) {
            Bundle args = new Bundle();
            args.putBoolean(MapParams.ZOOM_TO_ROUTE, false);
            args.putBoolean(MapParams.ZOOM_INCLUDE_CLOSEST_VEHICLE, true);
            args.putString(MapParams.ROUTE_ID, ((MapCommand.ShowRoute) command).getRouteId());
            setMapMode(MapParams.MODE_ROUTE, args);
        } else if (command instanceof MapCommand.ExitRouteMode) {
            // Return to stop mode, preserving the current zoom + center (the route header's cancel).
            Bundle args = new Bundle();
            args.putBoolean(MapParams.DO_N0T_CENTER_ON_LOCATION, true);
            args.putFloat(MapParams.ZOOM, getZoomLevelAsFloat());
            Location center = getMapCenterAsLocation();
            if (center != null) {
                args.putDouble(MapParams.CENTER_LAT, center.getLatitude());
                args.putDouble(MapParams.CENTER_LON, center.getLongitude());
            }
            setMapMode(MapParams.MODE_STOP, args);
        } else if (command instanceof MapCommand.RegionChanged) {
            // The map re-zoom hook (was a registered ObaRegionsTask callback); both hosts implement it.
            if (this instanceof ObaRegionsTask.Callback) {
                ((ObaRegionsTask.Callback) this)
                        .onRegionTaskFinished(((MapCommand.RegionChanged) command).getChanged());
            }
        }
    }
}
