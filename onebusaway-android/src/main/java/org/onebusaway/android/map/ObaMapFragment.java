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
package org.onebusaway.android.map;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

import org.onebusaway.android.BuildConfig;
import org.onebusaway.android.ui.weather.RegionCallback;

/**
 * Provider-agnostic interface for the map fragment. All main source code should reference
 * this interface instead of the concrete Google Maps implementation.
 *
 * Implementations of this interface must also be {@link Fragment} subclasses.
 * Use {@link #asFragment()} instead of raw casts for Fragment operations.
 */
public interface ObaMapFragment extends MapModeController.ObaMapView {

    String TAG = "MapFragment";

    // ========================================================================
    // Listener setters
    // ========================================================================

    void setOnFocusChangeListener(OnFocusChangedListener listener);

    void setOnProgressBarChangedListener(OnProgressBarChangedListener listener);

    void setOnLocationPermissionResultListener(OnLocationPermissionResultListener listener);

    void setRegionCallback(RegionCallback callback);

    // ========================================================================
    // Map-specific operations (beyond ObaMapView)
    // ========================================================================

    MapModeController.ObaMapView getMapView();

    void setMapMode(String mode, Bundle args);

    boolean setMyLocation(boolean useDefaultZoom, boolean animateToLocation);

    void zoomIn();

    void zoomOut();

    // ========================================================================
    // Fragment bridge
    // ========================================================================

    /**
     * Returns this map fragment as a {@link Fragment}. All implementations must be Fragment
     * subclasses, so this is always safe.
     */
    default Fragment asFragment() {
        return (Fragment) this;
    }

    // ========================================================================
    // Factory
    // ========================================================================

    /**
     * Creates a new instance of the flavor-specific map fragment implementation.
     * The concrete class name is provided by {@link BuildConfig#MAP_FRAGMENT_CLASS}.
     */
    static ObaMapFragment newInstance() {
        try {
            return (ObaMapFragment) Class.forName(BuildConfig.MAP_FRAGMENT_CLASS)
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Map fragment implementation not found: "
                    + BuildConfig.MAP_FRAGMENT_CLASS, e);
        }
    }
}
