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

import android.content.Context;
import android.location.Location;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.onebusaway.android.R;
import org.onebusaway.android.extrapolation.ExtrapolationResult;
import org.onebusaway.android.extrapolation.Extrapolator;
import org.onebusaway.android.extrapolation.ExtrapolatorKt;
import org.onebusaway.android.extrapolation.data.TripDataManager;
import org.onebusaway.android.io.elements.ObaTrip;
import org.onebusaway.android.io.elements.ObaTripDetails;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.elements.ObaElementExtensionsKt;
import org.onebusaway.android.io.elements.Status;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.util.LocationUtils;
import org.onebusaway.android.util.UIUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

/**
 * Manages all vehicle markers on the Google Map: creation, position updates
 * (including extrapolation), selection, data-received markers, and cleanup.
 * Reads/writes {@link VehicleMarkerState} as pure data; all map API calls live here.
 */
class VehicleMapController {

    private static final double MAX_VEHICLE_ANIMATION_DISTANCE = 400;
    private static final float VEHICLE_MARKER_Z_INDEX = 1;
    private static final float DATA_RECEIVED_MARKER_Z_INDEX = 3.1f;

    private final GoogleMap mMap;
    private final Context mContext;
    private final VehicleIconFactory mIconFactory;
    private final TripDataManager mDataManager;
    private final int mAnimateDurationMs;

    private HashMap<String, VehicleMarkerState> mStates = new HashMap<>();

    /** Reusable Location to avoid per-frame allocation in extrapolation. */
    private final Location mReusableLocation = new Location("extrapolated");

    private BitmapDescriptor mDataReceivedIcon;

    VehicleMapController(GoogleMap map, Context context, VehicleIconFactory iconFactory,
                         int animateDurationMs) {
        mMap = map;
        mContext = context.getApplicationContext();
        mIconFactory = iconFactory;
        mDataManager = TripDataManager.getInstance();
        mAnimateDurationMs = animateDurationMs;
    }

    // --- Populate from API response ---

    synchronized void populate(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
        HashSet<String> activeTripIds = new HashSet<>();
        HashMap<String, String> vehicleToTrip = new HashMap<>();
        long now = System.currentTimeMillis();

        for (ObaTripDetails trip : response.getTrips()) {
            ObaTripStatus status = trip.getStatus();
            if (status == null) continue;

            ObaTrip activeTrip = response.getTrip(status.getActiveTripId());
            if (activeTrip == null) continue;
            String activeRoute = activeTrip.getRouteId();
            if (!routeIds.contains(activeRoute) || Status.CANCELED.equals(status.getStatus()))
                continue;

            Location l = status.getLastKnownLocation();
            if (l == null) l = status.getPosition();
            if (l == null) continue;

            boolean isRealtime = ObaElementExtensionsKt.isLocationRealtime(status)
                    || ObaElementExtensionsKt.isRealtimeSpeedEstimable(status, now);

            String tripId = status.getActiveTripId();
            String vehicleId = status.getVehicleId();

            // A vehicle that switches trips (e.g. finishing one run and starting
            // the next) keeps the same vehicleId but gets a new tripId. Remove
            // the stale marker for the old trip so it doesn't linger on the map.
            if (vehicleId != null) {
                String prevTrip = vehicleToTrip.put(vehicleId, tripId);
                if (prevTrip != null && !prevTrip.equals(tripId)) {
                    removeState(prevTrip);
                    activeTripIds.remove(prevTrip);
                }
            }

            VehicleMarkerState state = mStates.get(tripId);
            if (state == null) {
                addVehicle(tripId, l, isRealtime, status, response);
            } else {
                updateVehicle(state, isRealtime, status, response);
            }
            activeTripIds.add(tripId);
        }

        removeInactiveMarkers(activeTripIds);
    }

    // --- Vehicle marker lifecycle ---

    private void addVehicle(String tripId, Location l, boolean isRealtime,
                            ObaTripStatus status, ObaTripsForRouteResponse response) {
        Marker m = mMap.addMarker(new MarkerOptions()
                .position(MapHelpV2.makeLatLng(l))
                .title(status.getVehicleId())
                .icon(mIconFactory.getVehicleIcon(isRealtime, status, response))
        );
        ProprietaryMapHelpV2.setZIndex(m, VEHICLE_MARKER_Z_INDEX);
        VehicleMarkerState state = new VehicleMarkerState(tripId, status);
        state.vehicleMarker = m;
        m.setTag(state);
        mStates.put(tripId, state);
    }

    private void updateVehicle(VehicleMarkerState state, boolean isRealtime,
                               ObaTripStatus status, ObaTripsForRouteResponse response) {
        Marker m = state.vehicleMarker;
        boolean showInfo = m.isInfoWindowShown();
        m.setIcon(mIconFactory.getVehicleIcon(isRealtime, status, response));
        state.setStatus(status);
        if (showInfo) {
            m.showInfoWindow();
        }
    }

    private void removeInactiveMarkers(HashSet<String> activeTripIds) {
        Iterator<Map.Entry<String, VehicleMarkerState>> iterator =
                mStates.entrySet().iterator();
        while (iterator.hasNext()) {
            VehicleMarkerState state = iterator.next().getValue();
            if (!activeTripIds.contains(state.getTripId())) {
                destroyState(state);
                iterator.remove();
            }
        }
    }

    private void removeState(String tripId) {
        VehicleMarkerState state = mStates.remove(tripId);
        if (state != null) {
            destroyState(state);
        }
    }

    private void destroyState(VehicleMarkerState state) {
        state.vehicleMarker.remove();
        removeDataReceivedMarker(state);
    }

    // --- Data-received marker lifecycle ---

    private void showDataReceivedMarker(VehicleMarkerState state) {
        removeDataReceivedMarker(state);
        ObaTripStatus status = state.getStatus();
        Location loc = status.getPosition();
        if (loc == null) return;
        if (!status.isPredicted() || status.getLastLocationUpdateTime() <= 0) return;
        long elapsed = System.currentTimeMillis() - status.getLastLocationUpdateTime();
        Marker m = mMap.addMarker(new MarkerOptions()
                .position(MapHelpV2.makeLatLng(loc))
                .icon(getOrCreateDataReceivedIcon())
                .title(mContext.getString(R.string.marker_most_recent_data))
                .snippet(UIUtils.formatElapsedTime(elapsed))
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(DATA_RECEIVED_MARKER_Z_INDEX));
        state.dataReceivedMarker = m;
        state.dataReceivedFixTime = status.getLastLocationUpdateTime();
        m.setTag(state);
    }

    private void updateDataReceivedMarker(VehicleMarkerState state, ObaTripStatus newestValid,
                                           long now) {
        if (state.dataReceivedMarker == null || newestValid == null) return;
        long fixTime = newestValid.getLastLocationUpdateTime();
        if (fixTime != state.dataReceivedFixTime) {
            state.dataReceivedFixTime = fixTime;
            Location loc = newestValid.getPosition();
            if (loc != null) {
                AnimationUtil.animateMarkerTo(state.dataReceivedMarker,
                        MapHelpV2.makeLatLng(loc), mAnimateDurationMs);
            }
        }
    }

    private void removeDataReceivedMarker(VehicleMarkerState state) {
        if (state.dataReceivedMarker != null) {
            state.dataReceivedMarker.remove();
            state.dataReceivedMarker = null;
        }
        state.dataReceivedFixTime = 0;
    }

    private BitmapDescriptor getOrCreateDataReceivedIcon() {
        if (mDataReceivedIcon == null) {
            mDataReceivedIcon = MapIconUtils.createCircleIcon(
                    mContext, R.drawable.ic_signal_indicator, 0xFF616161);
        }
        return mDataReceivedIcon;
    }

    private static VehicleMarkerState stateOf(Marker marker) {
        Object tag = marker.getTag();
        return tag instanceof VehicleMarkerState ? (VehicleMarkerState) tag : null;
    }

    // --- Selection ---

    synchronized boolean handleMarkerClick(Marker marker) {
        VehicleMarkerState state = stateOf(marker);
        if (state == null) return false;
        if (marker.equals(state.dataReceivedMarker)) {
            marker.showInfoWindow();
        } else {
            selectState(state);
        }
        return true;
    }

    synchronized void selectVehicle(String tripId) {
        VehicleMarkerState state = mStates.get(tripId);
        if (state != null) selectState(state);
    }

    private void selectState(VehicleMarkerState state) {
        deselectAll();
        state.selected = true;
        state.vehicleMarker.showInfoWindow();
        if (state.isExtrapolating()) {
            showDataReceivedMarker(state);
        }
    }

    synchronized void deselectAll() {
        for (VehicleMarkerState state : mStates.values()) {
            state.selected = false;
            removeDataReceivedMarker(state);
        }
    }

    // --- Queries ---

    synchronized ObaTripStatus getStatusFromMarker(Marker marker) {
        VehicleMarkerState state = stateOf(marker);
        return state != null ? state.getStatus() : null;
    }

    synchronized boolean isExtrapolating(Marker marker) {
        VehicleMarkerState state = stateOf(marker);
        return state != null && state.isExtrapolating();
    }

    synchronized boolean isDataReceivedMarker(Marker marker) {
        VehicleMarkerState state = stateOf(marker);
        return state != null && marker.equals(state.dataReceivedMarker);
    }

    synchronized String getTripIdForDataReceivedMarker(Marker marker) {
        VehicleMarkerState state = stateOf(marker);
        if (state != null && marker.equals(state.dataReceivedMarker)) return state.getTripId();
        return null;
    }

    // --- Per-frame position updates ---

    synchronized void updatePositions(long now) {
        if (mStates.isEmpty()) return;

        TripDataManager dm = mDataManager;

        for (VehicleMarkerState state : mStates.values()) {
            ExtrapolationResult result = getOrCreateExtrapolator(state).extrapolate(now);
            state.setExtrapolating(result instanceof ExtrapolationResult.Success);

            LatLng target = (result instanceof ExtrapolationResult.Success)
                    ? mapToPolyline(state, (ExtrapolationResult.Success) result) : null;

            ObaTripStatus newestValid = dm.getNewestValidEntry(state.getTripId());

            if (target != null) {
                boolean freshData = detectFreshAvlData(state, newestValid);
                if (freshData) {
                    startTransitionAnimation(state, target);
                } else {
                    setPositionIfNotAnimating(state, target);
                }
            } else {
                ObaTripStatus status = state.getStatus();
                Location loc = status.getLastKnownLocation();
                if (loc == null) loc = status.getPosition();
                if (loc != null) {
                    state.vehicleMarker.setPosition(MapHelpV2.makeLatLng(loc));
                }
            }
            if (state.selected) {
                if (target != null) {
                    updateDataReceivedMarker(state, newestValid, now);
                } else {
                    removeDataReceivedMarker(state);
                }
            }
        }
    }

    // --- Extrapolation helpers ---

    private Extrapolator getOrCreateExtrapolator(VehicleMarkerState state) {
        Extrapolator ext = state.getExtrapolator();
        if (ext != null) return ext;
        ext = ExtrapolatorKt.createExtrapolator(state.getTripId(),
                mDataManager);
        state.setExtrapolator(ext);
        return ext;
    }

    private LatLng mapToPolyline(VehicleMarkerState state,
                                  ExtrapolationResult.Success result) {
        TripDataManager.ShapeData sd = mDataManager
                .getShapeWithDistances(state.getTripId());
        if (sd == null || sd.points.isEmpty()) return null;
        if (!LocationUtils.interpolateAlongPolyline(
                sd.points, sd.cumulativeDistances,
                result.getDistribution().median(), mReusableLocation))
            return null;
        return new LatLng(mReusableLocation.getLatitude(), mReusableLocation.getLongitude());
    }

    private boolean detectFreshAvlData(VehicleMarkerState state, ObaTripStatus newest) {
        long fixTime = newest != null ? newest.getLastLocationUpdateTime() : 0;
        long prev = state.getLastFixTimeMs();
        state.setLastFixTimeMs(fixTime);
        return prev != 0 && fixTime != prev;
    }

    private void startTransitionAnimation(VehicleMarkerState state, LatLng target) {
        Marker marker = state.vehicleMarker;
        if (marker.getPosition() != null) {
            state.animating = true;
            AnimationUtil.animateMarkerTo(marker, target, mAnimateDurationMs,
                    () -> state.animating = false);
        } else {
            marker.setPosition(target);
        }
    }

    private void setPositionIfNotAnimating(VehicleMarkerState state, LatLng target) {
        if (!state.animating) {
            LatLng current = state.vehicleMarker.getPosition();
            if (current == null || current.latitude != target.latitude
                    || current.longitude != target.longitude) {
                state.vehicleMarker.setPosition(target);
            }
        }
    }

    // --- Lifecycle ---

    synchronized void clear() {
        for (VehicleMarkerState state : mStates.values()) {
            destroyState(state);
        }
        mStates.clear();
        mDataReceivedIcon = null;
    }

    synchronized int size() {
        return mStates.size();
    }
}
