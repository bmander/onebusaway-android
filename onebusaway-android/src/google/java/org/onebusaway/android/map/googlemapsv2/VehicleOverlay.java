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
import android.location.Location;
import android.util.Log;
import android.view.Choreographer;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.onebusaway.android.R;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaTrip;
import org.onebusaway.android.io.elements.ObaTripDetails;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.elements.ObaElementExtensionsKt;
import org.onebusaway.android.io.elements.Status;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.extrapolation.ExtrapolationResult;
import org.onebusaway.android.extrapolation.Extrapolator;
import org.onebusaway.android.extrapolation.ExtrapolatorKt;
import org.onebusaway.android.extrapolation.data.TripDataManager;
import org.onebusaway.android.util.LocationUtils;
import org.onebusaway.android.ui.TripDetailsActivity;
import org.onebusaway.android.ui.TripDetailsListFragment;
import org.onebusaway.android.util.UIUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

/**
 * A map overlay that shows vehicle positions on the map.
 * Delegates icon creation to {@link VehicleIconFactory} and info window
 * rendering to {@link VehicleInfoWindowAdapter}.
 */
public class VehicleOverlay implements GoogleMap.OnInfoWindowClickListener, MarkerListeners {

    interface Controller {
        String getFocusedStopId();
    }

    private static final String TAG = "VehicleOverlay";

    private GoogleMap mMap;
    private MarkerData mMarkerData;
    private final Activity mActivity;
    private ObaTripsForRouteResponse mLastResponse;
    private Controller mController;
    private final VehicleIconFactory mIconFactory;

    private static final int ANIMATE_DURATION_MS = 600;

    /** Data-received marker shown when a vehicle is selected. */
    private Marker mDataReceivedMarker;
    private String mDataReceivedTripId;
    private long mDataReceivedFixTime;
    private BitmapDescriptor mDataReceivedIcon;

    private boolean mExtrapolationTicking;
    private long mLastFrameTimeMs;
    private static final long FRAME_INTERVAL_MS = 50; // 20fps
    private final Choreographer.FrameCallback mFrameCallback = this::onExtrapolationFrame;

    /**
     * If a vehicle moves less than this distance (in meters), it will be animated
     */
    private static final double MAX_VEHICLE_ANIMATION_DISTANCE = 400;

    private static final float VEHICLE_MARKER_Z_INDEX = 1;

    public VehicleOverlay(Activity activity, GoogleMap map) {
        mActivity = activity;
        mMap = map;
        mIconFactory = new VehicleIconFactory(activity);
        VehicleInfoWindowAdapter adapter = new VehicleInfoWindowAdapter(activity,
                new VehicleInfoWindowAdapter.InfoSource() {
                    @Override
                    public ObaTripStatus getStatusFromMarker(Marker marker) {
                        return mMarkerData != null ? mMarkerData.getStatusFromMarker(marker) : null;
                    }

                    @Override
                    public boolean isDataReceivedMarker(Marker marker) {
                        return marker.equals(mDataReceivedMarker);
                    }

                    @Override
                    public boolean isExtrapolating(Marker marker) {
                        return mMarkerData != null && mMarkerData.isExtrapolating(marker);
                    }

                    @Override
                    public ObaTripsForRouteResponse getLastResponse() {
                        return mLastResponse;
                    }
                });
        mMap.setInfoWindowAdapter(adapter);
        mMap.setOnInfoWindowClickListener(this);
    }

    public void setController(Controller controller) {
        mController = controller;
    }

    /**
     * Updates vehicles for the provided routeIds from the status info from the given response.
     */
    public void updateVehicles(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
        setupMarkerData();
        mLastResponse = response;
        mMarkerData.populate(routeIds, response);
        startExtrapolationTicking();
    }

    public synchronized int size() {
        return mMarkerData != null ? mMarkerData.size() : 0;
    }

    public synchronized void clear() {
        stopExtrapolationTicking();
        removeDataReceivedMarker();
        mDataReceivedIcon = null;
        if (mMarkerData != null) {
            mMarkerData.clear();
            mMarkerData = null;
        }
    }

    @Override
    public void onInfoWindowClick(Marker marker) {
        if (marker.equals(mDataReceivedMarker) && mDataReceivedTripId != null) {
            navigateToTripDetails(mDataReceivedTripId);
            return;
        }
        if (mMarkerData != null) {
            ObaTripStatus status = mMarkerData.getStatusFromMarker(marker);
            if (status != null) {
                navigateToTripDetails(status.getActiveTripId());
            }
        }
    }

    private void navigateToTripDetails(String tripId) {
        TripDetailsActivity.Builder builder = new TripDetailsActivity.Builder(mActivity, tripId);
        if (mController != null && mController.getFocusedStopId() != null) {
            builder.setStopId(mController.getFocusedStopId());
        }
        builder.setScrollMode(TripDetailsListFragment.SCROLL_MODE_VEHICLE)
                .setUpMode("back")
                .start();
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
        if (!mExtrapolationTicking || mMarkerData == null || mActivity.isDestroyed()) {
            mExtrapolationTicking = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - mLastFrameTimeMs >= FRAME_INTERVAL_MS) {
            mLastFrameTimeMs = now;
            mMarkerData.updatePositions(now);
        }
        Choreographer.getInstance().postFrameCallback(mFrameCallback);
    }

    // --- Marker selection ---

    public void selectTrip(String tripId) {
        if (mMarkerData == null || tripId == null) return;
        Marker marker = mMarkerData.getMarkerForTrip(tripId);
        if (marker == null) return;
        ObaTripStatus status = mMarkerData.getStatusFromMarker(marker);
        if (status != null) {
            marker.showInfoWindow();
            showDataReceivedMarker(status);
        }
    }

    @Override
    public boolean markerClicked(Marker marker) {
        if (marker.equals(mDataReceivedMarker)) {
            marker.showInfoWindow();
            return true;
        }
        if (mMarkerData == null) return false;
        ObaTripStatus status = mMarkerData.getStatusFromMarker(marker);
        if (status != null) {
            marker.showInfoWindow();
            showDataReceivedMarker(status);
            return true;
        }
        return false;
    }

    @Override
    public void removeMarkerClicked(LatLng latLng) {
        removeDataReceivedMarker();
    }

    // --- Data-received marker ---

    private void showDataReceivedMarker(ObaTripStatus status) {
        removeDataReceivedMarker();

        if (!status.isPredicted() || status.getLastLocationUpdateTime() <= 0) return;
        Location loc = status.getPosition();
        if (loc == null) return;

        if (mDataReceivedIcon == null) {
            mDataReceivedIcon = MapIconUtils.createCircleIcon(
                    mActivity, R.drawable.ic_signal_indicator, 0xFF616161);
        }

        long elapsed = System.currentTimeMillis() - status.getLastLocationUpdateTime();
        String snippet = UIUtils.formatElapsedTime(elapsed);

        mDataReceivedMarker = mMap.addMarker(new MarkerOptions()
                .position(MapHelpV2.makeLatLng(loc))
                .icon(mDataReceivedIcon)
                .title(mActivity.getString(R.string.marker_most_recent_data))
                .snippet(snippet)
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(VEHICLE_MARKER_Z_INDEX + 0.1f)
        );
        mDataReceivedTripId = status.getActiveTripId();
        mDataReceivedFixTime = status.getLastLocationUpdateTime();
    }

    void updateDataReceivedMarkerIfNeeded(String tripId, ObaTripStatus newestValid, long now) {
        if (newestValid == null || !newestValid.isPredicted()) return;
        if (newestValid.getLastLocationUpdateTime() <= 0) return;

        // Create marker if needed, or switch to a different trip
        if (mDataReceivedMarker == null || !tripId.equals(mDataReceivedTripId)) {
            showDataReceivedMarker(newestValid);
            return;
        }

        // Only update position and snippet when the fix time changes
        long fixTime = newestValid.getLastLocationUpdateTime();
        if (fixTime == mDataReceivedFixTime) return;
        mDataReceivedFixTime = fixTime;

        Location loc = newestValid.getPosition();
        if (loc == null) return;

        AnimationUtil.animateMarkerTo(mDataReceivedMarker,
                MapHelpV2.makeLatLng(loc), ANIMATE_DURATION_MS);
        mDataReceivedMarker.setSnippet(
                UIUtils.formatElapsedTime(now - fixTime));
    }

    private void removeDataReceivedMarker() {
        if (mDataReceivedMarker != null) {
            mDataReceivedMarker.remove();
            mDataReceivedMarker = null;
        }
        mDataReceivedTripId = null;
        mDataReceivedFixTime = 0;
    }

    private void setupMarkerData() {
        if (mMarkerData == null) {
            mMarkerData = new MarkerData();
        }
    }

    // ========================================================================
    // MarkerData — manages the set of vehicle markers on the map
    // ========================================================================

    class MarkerData {

        private HashMap<String, VehicleMarkerState> mStates = new HashMap<>();
        private HashMap<Marker, VehicleMarkerState> mMarkerToState = new HashMap<>();

        /** Reusable Location to avoid per-frame allocation in extrapolation. */
        private final Location mReusableLocation = new Location("extrapolated");

        synchronized void populate(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
            int added = 0;
            int updated = 0;
            ObaTripDetails[] trips = response.getTrips();
            HashSet<String> activeTripIds = new HashSet<>();
            // Track vehicleId → tripId to remove stale markers when a vehicle switches trips
            HashMap<String, String> vehicleToTrip = new HashMap<>();
            long now = System.currentTimeMillis();
            TripDataManager dm = TripDataManager.getInstance();

            for (ObaTripDetails trip : trips) {
                ObaTripStatus status = trip.getStatus();
                if (status == null) continue;

                ObaTrip activeTrip = response.getTrip(status.getActiveTripId());
                if (activeTrip == null) continue;
                String activeRoute = activeTrip.getRouteId();
                if (!routeIds.contains(activeRoute) || Status.CANCELED.equals(status.getStatus()))
                    continue;

                recordTrajectoryState(dm, status, activeTrip, response);

                Location l = status.getLastKnownLocation();
                if (l == null) l = status.getPosition();
                if (l == null) continue;

                boolean isRealtime = ObaElementExtensionsKt.isLocationRealtime(status)
                        || ObaElementExtensionsKt.isRealtimeSpeedEstimable(status, now);

                String tripId = status.getActiveTripId();
                String vehicleId = status.getVehicleId();

                // If this vehicle previously had a marker on a different trip, remove it
                if (vehicleId != null) {
                    String prevTrip = vehicleToTrip.put(vehicleId, tripId);
                    if (prevTrip != null && !prevTrip.equals(tripId)) {
                        removeState(prevTrip);
                        activeTripIds.remove(prevTrip);
                    }
                }

                VehicleMarkerState state = mStates.get(tripId);
                if (state == null) {
                    state = addMarkerToMap(tripId, l, isRealtime, status, response);
                    added++;
                } else {
                    updateMarker(state, l, isRealtime, status, response, now);
                    updated++;
                }
                activeTripIds.add(tripId);
                fetchScheduleAndShapeIfNeeded(dm, status, activeTrip);
            }

            int removed = removeInactiveMarkers(activeTripIds);

            Log.d(TAG, "Added " + added + ", updated " + updated + ", removed " + removed
                    + ", total=" + mStates.size());
            Log.d(TAG, "Icon cache: " + mIconFactory.getCacheStats());
        }

        private void recordTrajectoryState(TripDataManager dm, ObaTripStatus status,
                                            ObaTrip activeTrip, ObaTripsForRouteResponse response) {
            dm.recordStatus(status);
            if (dm.getRouteType(status.getActiveTripId()) == null) {
                String routeId = activeTrip.getRouteId();
                ObaRoute route = routeId != null ? response.getRoute(routeId) : null;
                if (route != null) {
                    dm.putRouteType(status.getActiveTripId(), route.getType());
                }
            }
        }

        private void fetchScheduleAndShapeIfNeeded(TripDataManager dm, ObaTripStatus status,
                                                    ObaTrip activeTrip) {
            String tripId = status.getActiveTripId();
            if (tripId == null) return;
            dm.ensureSchedule(tripId);
            String shapeId = activeTrip.getShapeId();
            if (shapeId != null) {
                dm.ensureShape(tripId, shapeId);
            }
        }

        private VehicleMarkerState addMarkerToMap(String tripId, Location l, boolean isRealtime,
                                                   ObaTripStatus status,
                                                   ObaTripsForRouteResponse response) {
            Marker m = mMap.addMarker(new MarkerOptions()
                    .position(MapHelpV2.makeLatLng(l))
                    .title(status.getVehicleId())
                    .icon(mIconFactory.getVehicleIcon(isRealtime, status, response))
            );
            ProprietaryMapHelpV2.setZIndex(m, VEHICLE_MARKER_Z_INDEX);
            VehicleMarkerState state = new VehicleMarkerState(tripId, m, status);
            mStates.put(tripId, state);
            mMarkerToState.put(m, state);
            return state;
        }

        private void updateMarker(VehicleMarkerState state, Location l, boolean isRealtime,
                                  ObaTripStatus status, ObaTripsForRouteResponse response,
                                  long now) {
            Marker m = state.getMarker();
            boolean showInfo = m.isInfoWindowShown();
            m.setIcon(mIconFactory.getVehicleIcon(isRealtime, status, response));
            state.setStatus(status);

            String tripId = state.getTripId();
            TripDataManager dm = TripDataManager.getInstance();
            if (dm.getShape(tripId) == null || dm.getNewestValidEntry(tripId) == null) {
                Location markerLoc = MapHelpV2.makeLocation(m.getPosition());
                if (l.distanceTo(markerLoc) < MAX_VEHICLE_ANIMATION_DISTANCE) {
                    AnimationUtil.animateMarkerTo(m, MapHelpV2.makeLatLng(l));
                } else {
                    m.setPosition(MapHelpV2.makeLatLng(l));
                }
            }
            if (showInfo) {
                m.showInfoWindow();
            }
        }

        private int removeInactiveMarkers(HashSet<String> activeTripIds) {
            int removed = 0;
            Iterator<Map.Entry<String, VehicleMarkerState>> iterator =
                    mStates.entrySet().iterator();
            while (iterator.hasNext()) {
                VehicleMarkerState state = iterator.next().getValue();
                if (!activeTripIds.contains(state.getTripId())) {
                    state.getMarker().remove();
                    mMarkerToState.remove(state.getMarker());
                    iterator.remove();
                    removed++;
                }
            }
            return removed;
        }

        private void removeState(String tripId) {
            VehicleMarkerState state = mStates.remove(tripId);
            if (state != null) {
                state.getMarker().remove();
                mMarkerToState.remove(state.getMarker());
            }
        }

        synchronized Marker getMarkerForTrip(String tripId) {
            VehicleMarkerState state = mStates.get(tripId);
            return state != null ? state.getMarker() : null;
        }

        synchronized ObaTripStatus getStatusFromMarker(Marker marker) {
            VehicleMarkerState state = mMarkerToState.get(marker);
            return state != null ? state.getStatus() : null;
        }

        synchronized boolean isExtrapolating(Marker marker) {
            VehicleMarkerState state = mMarkerToState.get(marker);
            return state != null && state.isExtrapolating();
        }

        // --- Per-frame position updates ---

        synchronized void updatePositions(long now) {
            if (mStates.isEmpty()) return;

            TripDataManager dm = TripDataManager.getInstance();

            for (VehicleMarkerState state : mStates.values()) {
                ExtrapolationResult result = getOrCreateExtrapolator(state).extrapolate(now);
                state.setExtrapolating(result instanceof ExtrapolationResult.Success);

                LatLng target = (result instanceof ExtrapolationResult.Success)
                        ? mapToPolyline(state, (ExtrapolationResult.Success) result) : null;

                if (target != null) {
                    ObaTripStatus newestValid = dm.getNewestValidEntry(state.getTripId());
                    boolean freshData = detectFreshAvlData(state, newestValid);
                    if (freshData) {
                        startTransitionAnimation(state, target);
                    } else {
                        setPositionIfNotAnimating(state, target);
                    }
                    // Create the data-received marker on first fresh data;
                    // update it if already tracking this trip
                    if (freshData || state.getTripId().equals(mDataReceivedTripId)) {
                        updateDataReceivedMarkerIfNeeded(
                                state.getTripId(), newestValid, now);
                    }
                } else {
                    if (state.getTripId().equals(mDataReceivedTripId)) {
                        removeDataReceivedMarker();
                    }
                    ObaTripStatus lastState = dm.getLastState(state.getTripId());
                    if (lastState != null) {
                        Location loc = lastState.getLastKnownLocation();
                        if (loc == null) loc = lastState.getPosition();
                        if (loc != null) {
                            state.getMarker().setPosition(MapHelpV2.makeLatLng(loc));
                        }
                    }
                }
            }
        }

        private Extrapolator getOrCreateExtrapolator(VehicleMarkerState state) {
            Extrapolator ext = state.getExtrapolator();
            if (ext != null) return ext;
            ext = ExtrapolatorKt.createExtrapolator(state.getTripId(),
                    TripDataManager.getInstance());
            state.setExtrapolator(ext);
            return ext;
        }

        private LatLng mapToPolyline(VehicleMarkerState state,
                                      ExtrapolationResult.Success result) {
            TripDataManager.ShapeData sd = TripDataManager.getInstance()
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
            Marker marker = state.getMarker();
            if (marker.getPosition() != null) {
                state.animating = true;
                AnimationUtil.animateMarkerTo(marker, target, ANIMATE_DURATION_MS,
                        () -> state.animating = false);
            } else {
                marker.setPosition(target);
            }
        }

        private void setPositionIfNotAnimating(VehicleMarkerState state, LatLng target) {
            if (!state.animating) {
                LatLng current = state.getMarker().getPosition();
                if (current == null || current.latitude != target.latitude
                        || current.longitude != target.longitude) {
                    state.getMarker().setPosition(target);
                }
            }
        }

        synchronized void clear() {
            for (VehicleMarkerState state : mStates.values()) {
                state.getMarker().remove();
            }
            mStates.clear();
            mMarkerToState.clear();
        }

        synchronized int size() {
            return mStates.size();
        }
    }
}
