/*
 * Copyright (C) 2014-2026 University of South Florida, Open Transit Software Foundation
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
import android.view.Choreographer;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;

import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.ui.TripDetailsActivity;
import org.onebusaway.android.ui.TripDetailsListFragment;

import java.util.HashSet;

/**
 * Bridge between map listener interfaces and {@link VehicleMapController}.
 * Owns the choreographer frame loop and delegates all marker management
 * to the controller.
 */
public class VehicleOverlay implements GoogleMap.OnInfoWindowClickListener, MarkerListeners {

    interface Controller {
        String getFocusedStopId();
    }

    private static final int ANIMATE_DURATION_MS = 600;
    private static final long FRAME_INTERVAL_MS = 50; // 20fps

    private final Activity mActivity;
    private final GoogleMap mMap;
    private VehicleMapController mController;
    private ObaTripsForRouteResponse mLastResponse;
    private Controller mAppController;

    private boolean mExtrapolationTicking;
    private long mLastFrameTimeMs;
    private final Choreographer.FrameCallback mFrameCallback = this::onExtrapolationFrame;

    public VehicleOverlay(Activity activity, GoogleMap map) {
        mActivity = activity;
        mMap = map;
        VehicleIconFactory iconFactory = new VehicleIconFactory(activity);
        VehicleInfoWindowAdapter adapter = new VehicleInfoWindowAdapter(activity,
                new VehicleInfoWindowAdapter.InfoSource() {
                    @Override
                    public ObaTripStatus getStatusFromMarker(Marker marker) {
                        return mController != null ? mController.getStatusFromMarker(marker) : null;
                    }

                    @Override
                    public boolean isDataReceivedMarker(Marker marker) {
                        return mController != null && mController.isDataReceivedMarker(marker);
                    }

                    @Override
                    public boolean isExtrapolating(Marker marker) {
                        return mController != null && mController.isExtrapolating(marker);
                    }

                    @Override
                    public ObaTripsForRouteResponse getLastResponse() {
                        return mLastResponse;
                    }
                });
        mMap.setInfoWindowAdapter(adapter);
        mMap.setOnInfoWindowClickListener(this);
        mController = new VehicleMapController(map, activity, iconFactory, ANIMATE_DURATION_MS);
    }

    public void setController(Controller controller) {
        mAppController = controller;
    }

    public void updateVehicles(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
        mLastResponse = response;
        mController.populate(routeIds, response);
        startExtrapolationTicking();
    }

    public synchronized int size() {
        return mController != null ? mController.size() : 0;
    }

    public synchronized void clear() {
        stopExtrapolationTicking();
        if (mController != null) {
            mController.clear();
        }
    }

    // --- Info window click ---

    @Override
    public void onInfoWindowClick(Marker marker) {
        if (mController == null) return;
        String tripId = mController.getTripIdForDataReceivedMarker(marker);
        if (tripId != null) {
            navigateToTripDetails(tripId);
            return;
        }
        ObaTripStatus status = mController.getStatusFromMarker(marker);
        if (status != null) {
            navigateToTripDetails(status.getActiveTripId());
        }
    }

    private void navigateToTripDetails(String tripId) {
        TripDetailsActivity.Builder builder = new TripDetailsActivity.Builder(mActivity, tripId);
        if (mAppController != null && mAppController.getFocusedStopId() != null) {
            builder.setStopId(mAppController.getFocusedStopId());
        }
        builder.setScrollMode(TripDetailsListFragment.SCROLL_MODE_VEHICLE)
                .setUpMode("back")
                .start();
    }

    // --- Marker click delegation ---

    public void selectTrip(String tripId) {
        if (mController != null && tripId != null) {
            mController.selectVehicle(tripId);
        }
    }

    @Override
    public boolean markerClicked(Marker marker) {
        if (mController == null) return false;
        return mController.handleMarkerClick(marker);
    }

    @Override
    public void removeMarkerClicked(LatLng latLng) {
        if (mController != null) mController.deselectAll();
    }

    // --- Choreographer frame loop ---

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
        if (!mExtrapolationTicking || mController == null || mActivity.isDestroyed()) {
            mExtrapolationTicking = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - mLastFrameTimeMs >= FRAME_INTERVAL_MS) {
            mLastFrameTimeMs = now;
            mController.updatePositions(now);
        }
        Choreographer.getInstance().postFrameCallback(mFrameCallback);
    }
}
