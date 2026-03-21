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
import org.onebusaway.android.extrapolation.data.TripDataManager;
import org.onebusaway.android.extrapolation.math.prob.ProbDistribution;
import org.onebusaway.android.extrapolation.VehicleTrajectoryTracker;
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
            mMarkerData.extrapolatePositions(now);
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
    }

    void updateDataReceivedMarkerIfNeeded(String tripId, ObaTripStatus newestValid, long now) {
        if (mDataReceivedMarker == null || mDataReceivedTripId == null) return;
        if (!mDataReceivedTripId.equals(tripId)) return;
        if (newestValid == null || !newestValid.isPredicted()) return;

        Location loc = newestValid.getPosition();
        if (loc == null) return;

        LatLng target = MapHelpV2.makeLatLng(loc);
        AnimationUtil.animateMarkerTo(mDataReceivedMarker, target, ANIMATE_DURATION_MS);

        mDataReceivedMarker.setSnippet(
                UIUtils.formatElapsedTime(now - newestValid.getLastLocationUpdateTime()));
    }

    private void removeDataReceivedMarker() {
        if (mDataReceivedMarker != null) {
            mDataReceivedMarker.remove();
            mDataReceivedMarker = null;
        }
        mDataReceivedTripId = null;
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

        private HashMap<Marker, ObaTripStatus> mVehicles;
        private HashMap<String, Marker> mVehicleMarkers;
        private static final int INITIAL_HASHMAP_SIZE = 5;

        /** Reusable Location to avoid per-frame allocation in extrapolation. */
        private final Location mReusableLocation = new Location("extrapolated");

        /** Tracks last AVL fix time per trip to detect fresh data. */
        private final HashMap<String, Long> mLastFixTimes = new HashMap<>();
        /** Tracks when an animation was started, to avoid overriding it with setPosition. */
        private final HashMap<String, Long> mAnimatingUntil = new HashMap<>();

        MarkerData() {
            mVehicles = new HashMap<>(INITIAL_HASHMAP_SIZE);
            mVehicleMarkers = new HashMap<>(INITIAL_HASHMAP_SIZE);
        }

        synchronized void populate(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
            int added = 0;
            int updated = 0;
            ObaTripDetails[] trips = response.getTrips();
            HashSet<String> activeTripIds = new HashSet<>();
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

                Marker m = mVehicleMarkers.get(status.getActiveTripId());
                if (m == null) {
                    addMarkerToMap(l, isRealtime, status, response);
                    added++;
                } else {
                    updateMarker(m, l, isRealtime, status, response, now);
                    updated++;
                }
                activeTripIds.add(status.getActiveTripId());
                fetchScheduleAndShapeIfNeeded(dm, status, activeTrip);
            }

            int removed = removeInactiveMarkers(activeTripIds);

            Log.d(TAG, "Added " + added + ", updated " + updated + ", removed " + removed
                    + ", total=" + mVehicleMarkers.size());
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

        private void addMarkerToMap(Location l, boolean isRealtime, ObaTripStatus status,
                                    ObaTripsForRouteResponse response) {
            Marker m = mMap.addMarker(new MarkerOptions()
                    .position(MapHelpV2.makeLatLng(l))
                    .title(status.getVehicleId())
                    .icon(mIconFactory.getVehicleIcon(isRealtime, status, response))
            );
            ProprietaryMapHelpV2.setZIndex(m, VEHICLE_MARKER_Z_INDEX);
            mVehicleMarkers.put(status.getActiveTripId(), m);
            mVehicles.put(m, status);
        }

        private void updateMarker(Marker m, Location l, boolean isRealtime, ObaTripStatus status,
                                  ObaTripsForRouteResponse response, long now) {
            boolean showInfo = m.isInfoWindowShown();
            m.setIcon(mIconFactory.getVehicleIcon(isRealtime, status, response));
            mVehicles.put(m, status);

            String tripId = status.getActiveTripId();
            if (tripId == null || TripDataManager.getInstance().getShape(tripId) == null
                    || !VehicleTrajectoryTracker.getInstance().canExtrapolate(tripId, now)) {
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
            Iterator<Map.Entry<String, Marker>> iterator = mVehicleMarkers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Marker> entry = iterator.next();
                String tripId = entry.getKey();
                Marker m = entry.getValue();
                if (!activeTripIds.contains(tripId)) {
                    m.remove();
                    mVehicles.remove(m);
                    mLastFixTimes.remove(tripId);
                    mAnimatingUntil.remove(tripId);
                    iterator.remove();
                    removed++;
                }
            }
            return removed;
        }

        synchronized Marker getMarkerForTrip(String tripId) {
            return mVehicleMarkers.get(tripId);
        }

        synchronized ObaTripStatus getStatusFromMarker(Marker marker) {
            return mVehicles.get(marker);
        }

        // --- Extrapolation ---

        synchronized void extrapolatePositions(long now) {
            if (mVehicleMarkers == null || mVehicleMarkers.isEmpty()) return;

            VehicleTrajectoryTracker tracker = VehicleTrajectoryTracker.getInstance();
            TripDataManager dm = TripDataManager.getInstance();

            for (Map.Entry<String, Marker> entry : mVehicleMarkers.entrySet()) {
                String tripId = entry.getKey();
                LatLng target = computeExtrapolatedPosition(tracker, tripId, now);
                if (target == null) continue;

                Marker marker = entry.getValue();
                ObaTripStatus newestValid = dm.getNewestValidEntry(tripId);
                if (detectFreshAvlData(tripId, newestValid)) {
                    startTransitionAnimation(tripId, marker, target, now);
                    updateDataReceivedMarkerIfNeeded(tripId, newestValid, now);
                } else {
                    setPositionIfNotAnimating(tripId, marker, target, now);
                }
            }
        }

        private LatLng computeExtrapolatedPosition(
                VehicleTrajectoryTracker tracker, String tripId, long now) {
            TripDataManager.ShapeData sd = TripDataManager.getInstance()
                    .getShapeWithDistances(tripId);
            if (sd == null || sd.points.isEmpty()) return null;
            ProbDistribution dist = tracker.extrapolate(tripId, now);
            if (dist == null) return null;
            if (!LocationUtils.interpolateAlongPolyline(
                    sd.points, sd.cumulativeDistances, dist.median(), mReusableLocation))
                return null;
            return new LatLng(mReusableLocation.getLatitude(), mReusableLocation.getLongitude());
        }

        private boolean detectFreshAvlData(String tripId, ObaTripStatus newest) {
            long fixTime = newest != null ? newest.getLastLocationUpdateTime() : 0;
            Long prevFixTime = mLastFixTimes.put(tripId, fixTime);
            return prevFixTime != null && fixTime != prevFixTime;
        }

        private void startTransitionAnimation(String tripId, Marker marker,
                                               LatLng target, long now) {
            if (marker.getPosition() != null) {
                AnimationUtil.animateMarkerTo(marker, target, ANIMATE_DURATION_MS);
            } else {
                marker.setPosition(target);
            }
            mAnimatingUntil.put(tripId, now + ANIMATE_DURATION_MS);
        }

        private void setPositionIfNotAnimating(String tripId, Marker marker,
                                                LatLng target, long now) {
            Long animEnd = mAnimatingUntil.get(tripId);
            if (animEnd == null || now >= animEnd) {
                LatLng current = marker.getPosition();
                if (current == null || current.latitude != target.latitude
                        || current.longitude != target.longitude) {
                    marker.setPosition(target);
                }
            }
        }

        synchronized void clear() {
            if (mVehicleMarkers != null) {
                for (Map.Entry<String, Marker> entry : mVehicleMarkers.entrySet()) {
                    entry.getValue().remove();
                }
                mVehicleMarkers.clear();
                mVehicleMarkers = null;
            }
            if (mVehicles != null) {
                mVehicles.clear();
                mVehicles = null;
            }
            mLastFixTimes.clear();
            mAnimatingUntil.clear();
        }

        synchronized int size() {
            return mVehicleMarkers != null ? mVehicleMarkers.size() : 0;
        }
    }
}
