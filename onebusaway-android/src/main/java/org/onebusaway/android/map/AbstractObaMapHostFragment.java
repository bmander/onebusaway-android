/*
 * Copyright (C) 2011-2026 Paul Watts (paulcwatts@gmail.com),
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
package org.onebusaway.android.map;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaShape;
import org.onebusaway.android.io.elements.ObaStop;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.ui.weather.RegionCallback;
import org.onebusaway.android.util.PermissionUtils;

import java.util.HashSet;
import java.util.List;

/**
 * Flavor-agnostic thin {@link Fragment} wrapper around an {@link ObaMapHost}. Home hosts the map
 * directly via {@link ObaMapHost} (no FragmentManager); this wrapper keeps the other map screens
 * ({@code TripResultsFragment}, {@code InfrastructureIssueActivity}, {@code TripPlanLocationPicker})
 * working unchanged — it owns a host (built by the flavor subclass via {@link #createHost}), forwards
 * the Fragment lifecycle to it, drives the location-permission request through an
 * {@link ActivityResultLauncher} (as {@link MapHostDeps}), and delegates the {@link ObaMapFragment}
 * contract to the host.
 *
 * Those screens add the fragment with {@code commit()} and then immediately call setters before the
 * fragment's {@code onCreateView} has created the host. So listeners are stashed and applied when the
 * host is created, and map operations no-op until then — matching the legacy fragment, whose own map
 * ops no-op'd while {@code mMap} was still null.
 *
 * The two concrete flavor subclasses ({@code BaseMapFragment} / {@code MapLibreMapFragment}) only
 * supply {@link #createHost} and the {@code CAMERA_DEFAULT_ZOOM} their {@code StopOverlay} reads.
 */
public abstract class AbstractObaMapHostFragment extends Fragment
        implements ObaMapFragment, MapHostDeps {

    private ObaMapHost mHost;

    private ActivityResultLauncher<String[]> mPermissionLauncher;

    // Listeners set before onCreateView are stashed and applied once the host exists.
    private OnFocusChangedListener mPendingFocusListener;

    private OnProgressBarChangedListener mPendingProgressListener;

    private OnLocationPermissionResultListener mPendingPermissionListener;

    private RegionCallback mPendingRegionCallback;

    /** Flavor hook: build the concrete (Google / MapLibre) map host for this wrapper. */
    protected abstract ObaMapHost createHost(Activity activity, MapHostDeps deps, Bundle args);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean granted =
                            Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                                    || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                    if (mHost != null) {
                        mHost.onLocationPermissionResult(granted
                                ? PackageManager.PERMISSION_GRANTED
                                : PackageManager.PERMISSION_DENIED);
                    }
                });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Bundle args = getArguments() != null ? getArguments() : savedInstanceState;
        mHost = createHost(requireActivity(), this, args);
        // Apply any listeners that were set before the host existed.
        if (mPendingFocusListener != null) mHost.setOnFocusChangeListener(mPendingFocusListener);
        if (mPendingProgressListener != null) {
            mHost.setOnProgressBarChangedListener(mPendingProgressListener);
        }
        if (mPendingPermissionListener != null) {
            mHost.setOnLocationPermissionResultListener(mPendingPermissionListener);
        }
        if (mPendingRegionCallback != null) mHost.setRegionCallback(mPendingRegionCallback);
        return mHost.getView();
    }

    @Override
    public void requestLocationPermission() {
        mPermissionLauncher.launch(PermissionUtils.LOCATION_PERMISSIONS);
    }

    // ---- Fragment lifecycle → host ----

    @Override
    public void onStart() {
        super.onStart();
        if (mHost != null) mHost.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mHost != null) mHost.onResume();
    }

    @Override
    public void onPause() {
        if (mHost != null) mHost.onPause();
        super.onPause();
    }

    @Override
    public void onStop() {
        if (mHost != null) mHost.onStop();
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mHost != null) mHost.onLowMemory();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mHost != null) mHost.onSaveInstanceState(outState);
    }

    @Override
    public void onViewStateRestored(@NonNull Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        if (mHost != null) mHost.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (mHost != null) mHost.onHidden(hidden);
    }

    @Override
    public void onDestroyView() {
        if (mHost != null) {
            mHost.onDestroy();
            mHost = null;
        }
        super.onDestroyView();
    }

    // ---- Listeners (stashed until the host exists) ----

    @Override
    public void setOnFocusChangeListener(OnFocusChangedListener listener) {
        mPendingFocusListener = listener;
        if (mHost != null) mHost.setOnFocusChangeListener(listener);
    }

    @Override
    public void setOnProgressBarChangedListener(OnProgressBarChangedListener listener) {
        mPendingProgressListener = listener;
        if (mHost != null) mHost.setOnProgressBarChangedListener(listener);
    }

    @Override
    public void setOnLocationPermissionResultListener(OnLocationPermissionResultListener listener) {
        mPendingPermissionListener = listener;
        if (mHost != null) mHost.setOnLocationPermissionResultListener(listener);
    }

    @Override
    public void setRegionCallback(RegionCallback callback) {
        mPendingRegionCallback = callback;
        if (mHost != null) mHost.setRegionCallback(callback);
    }

    // ---- ObaMapFragment delegation (no-op / default until the host exists) ----

    @Override
    public MapModeController.ObaMapView getMapView() {
        return mHost != null ? mHost.getMapView() : null;
    }

    @Override
    public void setMapMode(String mode, Bundle args) {
        if (mHost != null) mHost.setMapMode(mode, args);
    }

    @Override
    public boolean setMyLocation(boolean useDefaultZoom, boolean animateToLocation) {
        return mHost != null && mHost.setMyLocation(useDefaultZoom, animateToLocation);
    }

    @Override
    public void zoomIn() {
        if (mHost != null) mHost.zoomIn();
    }

    @Override
    public void zoomOut() {
        if (mHost != null) mHost.zoomOut();
    }

    // ---- ObaMapView delegation ----

    @Override
    public void setZoom(float zoomLevel) {
        if (mHost != null) mHost.setZoom(zoomLevel);
    }

    @Override
    public Location getMapCenterAsLocation() {
        return mHost != null ? mHost.getMapCenterAsLocation() : null;
    }

    @Override
    public void setMapCenter(Location location, boolean animateToLocation, boolean overlayExpanded) {
        if (mHost != null) mHost.setMapCenter(location, animateToLocation, overlayExpanded);
    }

    @Override
    public double getLatitudeSpanInDecDegrees() {
        return mHost != null ? mHost.getLatitudeSpanInDecDegrees() : 0;
    }

    @Override
    public double getLongitudeSpanInDecDegrees() {
        return mHost != null ? mHost.getLongitudeSpanInDecDegrees() : 0;
    }

    @Override
    public float getZoomLevelAsFloat() {
        return mHost != null ? mHost.getZoomLevelAsFloat() : 0;
    }

    @Override
    public void setRouteOverlay(int lineOverlayColor, ObaShape[] shapes) {
        if (mHost != null) mHost.setRouteOverlay(lineOverlayColor, shapes);
    }

    @Override
    public void setRouteOverlay(int lineOverlayColor, ObaShape[] shapes, boolean clear) {
        if (mHost != null) mHost.setRouteOverlay(lineOverlayColor, shapes, clear);
    }

    @Override
    public void updateVehicles(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
        if (mHost != null) mHost.updateVehicles(routeIds, response);
    }

    @Override
    public void removeVehicleOverlay() {
        if (mHost != null) mHost.removeVehicleOverlay();
    }

    @Override
    public void zoomToRoute() {
        if (mHost != null) mHost.zoomToRoute();
    }

    @Override
    public void zoomToItinerary() {
        if (mHost != null) mHost.zoomToItinerary();
    }

    @Override
    public void zoomIncludeClosestVehicle(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
        if (mHost != null) mHost.zoomIncludeClosestVehicle(routeIds, response);
    }

    @Override
    public void postInvalidate() {
        if (mHost != null) mHost.postInvalidate();
    }

    @Override
    public void removeRouteOverlay() {
        if (mHost != null) mHost.removeRouteOverlay();
    }

    @Override
    public void removeStopOverlay(boolean clearFocusedStop) {
        if (mHost != null) mHost.removeStopOverlay(clearFocusedStop);
    }

    @Override
    public boolean canWatchMapChanges() {
        return mHost != null && mHost.canWatchMapChanges();
    }

    @Override
    public void setFocusStop(ObaStop stop, List<ObaRoute> routes) {
        if (mHost != null) mHost.setFocusStop(stop, routes);
    }

    @Override
    public int addMarker(Location location, Float hue) {
        return mHost != null ? mHost.addMarker(location, hue) : -1;
    }

    @Override
    public void removeMarker(int markerId) {
        if (mHost != null) mHost.removeMarker(markerId);
    }

    @Override
    public void setPadding(Integer left, Integer top, Integer right, Integer bottom) {
        if (mHost != null) mHost.setPadding(left, top, right, bottom);
    }
}
