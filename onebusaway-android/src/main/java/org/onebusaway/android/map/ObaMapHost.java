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
import android.os.Bundle;
import android.view.View;

import org.onebusaway.android.BuildConfig;
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
public interface ObaMapHost extends MapModeController.ObaMapView {

    // ========================================================================
    // View + map operations (mirror ObaMapFragment minus asFragment())
    // ========================================================================

    View getView();

    MapModeController.ObaMapView getMapView();

    void setMapMode(String mode, Bundle args);

    boolean setMyLocation(boolean useDefaultZoom, boolean animateToLocation);

    void zoomIn();

    void zoomOut();

    // ========================================================================
    // Listeners (reuse ObaMapFragment's nested interfaces — same types the owner implements)
    // ========================================================================

    void setOnFocusChangeListener(ObaMapFragment.OnFocusChangedListener listener);

    void setOnProgressBarChangedListener(ObaMapFragment.OnProgressBarChangedListener listener);

    void setOnLocationPermissionResultListener(ObaMapFragment.OnLocationPermissionResultListener listener);

    void setRegionCallback(RegionCallback callback);

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
     * Creates the flavor-specific host. The concrete class name is provided by
     * {@link BuildConfig#MAP_HOST_CLASS}; it must declare an
     * {@code (Activity, MapHostDeps, Bundle)} constructor.
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
}
