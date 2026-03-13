/*
 * Copyright (C) 2024 Open Transit Software Foundation
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
package org.onebusaway.android.speed;

import android.location.Location;

import org.onebusaway.android.io.elements.ObaTripSchedule;

import org.onebusaway.android.util.LocationUtils;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared extrapolation logic for estimating vehicle distance along a trip.
 * Used by both TrajectoryGraphView (for the graph) and TripDetailsListFragment
 * (for positioning the vehicle icon in the stop list).
 */
public final class DistanceExtrapolator {

    /** Max age of the newest AVL entry before we consider extrapolation unreliable. */
    private static final long MAX_EXTRAPOLATION_AGE_MS = 5 * 60 * 1000;

    private DistanceExtrapolator() {
    }

    /**
     * Returns the newest history entry with a valid distance and timestamp, or null.
     */
    public static VehicleHistoryEntry findNewestValidEntry(List<VehicleHistoryEntry> history) {
        if (history == null) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            VehicleHistoryEntry e = history.get(i);
            if (e.getBestDistanceAlongTrip() != null && e.getLastLocationUpdateTime() > 0) {
                return e;
            }
        }
        return null;
    }

    /**
     * Extrapolates the current distance along the trip based on the newest valid history entry
     * and estimated speed. Returns null if extrapolation is not possible.
     *
     * @param history       vehicle history entries for the trip
     * @param speedMps      estimated speed in meters per second
     * @param currentTimeMs current time in milliseconds
     * @return extrapolated distance in meters, or null
     */
    public static Double extrapolateDistance(List<VehicleHistoryEntry> history,
                                             double speedMps, long currentTimeMs) {
        if (speedMps <= 0) return null;

        VehicleHistoryEntry newest = findNewestValidEntry(history);
        if (newest == null) return null;

        long lastTime = newest.getLastLocationUpdateTime();
        if (currentTimeMs - lastTime > MAX_EXTRAPOLATION_AGE_MS) return null;

        Double lastDist = newest.getBestDistanceAlongTrip();
        if (lastDist == null) return null;

        return lastDist + speedMps * (currentTimeMs - lastTime) / 1000.0;
    }

    /**
     * Finds the index of the next stop the vehicle has not yet reached, based on
     * distance along the trip.
     *
     * @param stopTimes        array of stop times with distance info
     * @param distanceAlongTrip current distance along the trip in meters
     * @return index of the next stop, or stopTimes.length if past the last stop,
     *         or null if stopTimes is null/empty
     */
    public static Integer findNextStopIndex(ObaTripSchedule.StopTime[] stopTimes,
                                             double distanceAlongTrip) {
        if (stopTimes == null || stopTimes.length == 0) return null;

        for (int i = 0; i < stopTimes.length; i++) {
            if (stopTimes[i].getDistanceAlongTrip() > distanceAlongTrip) {
                return i;
            }
        }
        return stopTimes.length;
    }

    /**
     * Builds a cumulative distance array for a polyline. Entry i holds the total
     * distance from the first point to point i. Entry 0 is always 0.
     * <p>
     * Uses the same Haversine formula and Earth radius as the OBA server
     * (SphericalGeometryLibrary) so that distance values are consistent with
     * the server's distanceAlongTrip values.
     *
     * @param polylinePoints decoded polyline points
     * @return cumulative distance array (same length as polylinePoints), or null
     */
    public static double[] buildCumulativeDistances(List<Location> polylinePoints) {
        if (polylinePoints == null || polylinePoints.isEmpty()) return null;
        double[] cumDist = new double[polylinePoints.size()];
        cumDist[0] = 0;
        for (int i = 1; i < polylinePoints.size(); i++) {
            Location prev = polylinePoints.get(i - 1);
            Location cur = polylinePoints.get(i);
            cumDist[i] = cumDist[i - 1]
                    + haversineDistance(prev.getLatitude(), prev.getLongitude(),
                                       cur.getLatitude(), cur.getLongitude());
        }
        return cumDist;
    }

    /**
     * Haversine great-circle distance matching the OBA server's
     * SphericalGeometryLibrary.distance() exactly — same formula, same
     * Earth radius (6371.01 km).
     *
     * @return distance in meters
     */
    static double haversineDistance(double lat1, double lon1,
                                   double lat2, double lon2) {
        lat1 = Math.toRadians(lat1);
        lon1 = Math.toRadians(lon1);
        lat2 = Math.toRadians(lat2);
        lon2 = Math.toRadians(lon2);

        double deltaLon = lon2 - lon1;
        double cosLat2 = Math.cos(lat2);
        double sinDeltaLon = Math.sin(deltaLon);
        double cosLat1 = Math.cos(lat1);
        double sinLat2 = Math.sin(lat2);
        double sinLat1 = Math.sin(lat1);
        double cosDeltaLon = Math.cos(deltaLon);

        double y = Math.sqrt(
                (cosLat2 * sinDeltaLon) * (cosLat2 * sinDeltaLon)
              + (cosLat1 * sinLat2 - sinLat1 * cosLat2 * cosDeltaLon)
              * (cosLat1 * sinLat2 - sinLat1 * cosLat2 * cosDeltaLon));
        double x = sinLat1 * sinLat2 + cosLat1 * cosLat2 * cosDeltaLon;

        return EARTH_RADIUS_METERS * Math.atan2(y, x);
    }

    /** Matches OBA server's SphericalGeometryLibrary.RADIUS_OF_EARTH_IN_KM * 1000. */
    private static final double EARTH_RADIUS_METERS = 6371010.0;

    /**
     * Interpolates a position along a polyline at a given distance from the start.
     * Uses a precomputed cumulative distance array for O(log n) binary search.
     *
     * @param polylinePoints decoded polyline points (lat/lng)
     * @param cumDist        precomputed cumulative distances from {@link #buildCumulativeDistances}
     * @param distanceMeters target distance along the polyline in meters
     * @return interpolated Location, or null if the polyline is empty
     */
    public static Location interpolateAlongPolyline(List<Location> polylinePoints,
                                                     double[] cumDist,
                                                     double distanceMeters) {
        Location result = LocationUtils.makeLocation(0, 0);
        return interpolateAlongPolyline(polylinePoints, cumDist, distanceMeters, result)
                ? result : null;
    }

    /**
     * Interpolates a position along a polyline, writing into a reusable Location
     * to avoid allocation on the hot path. Returns true if successful.
     *
     * @param polylinePoints decoded polyline points (lat/lng)
     * @param cumDist        precomputed cumulative distances from {@link #buildCumulativeDistances}
     * @param distanceMeters target distance along the polyline in meters
     * @param out            reusable Location to write the result into
     * @return true if interpolation succeeded, false if inputs were invalid
     */
    public static boolean interpolateAlongPolyline(List<Location> polylinePoints,
                                                    double[] cumDist,
                                                    double distanceMeters,
                                                    Location out) {
        if (polylinePoints == null || polylinePoints.isEmpty() || cumDist == null || out == null) {
            return false;
        }
        if (distanceMeters <= 0) {
            Location first = polylinePoints.get(0);
            out.setLatitude(first.getLatitude());
            out.setLongitude(first.getLongitude());
            return true;
        }

        int idx = Arrays.binarySearch(cumDist, distanceMeters);
        if (idx >= 0) {
            Location exact = polylinePoints.get(idx);
            out.setLatitude(exact.getLatitude());
            out.setLongitude(exact.getLongitude());
            return true;
        }
        // binarySearch returns -(insertion point) - 1
        int insertionPoint = -idx - 1;
        if (insertionPoint >= polylinePoints.size()) {
            Location last = polylinePoints.get(polylinePoints.size() - 1);
            out.setLatitude(last.getLatitude());
            out.setLongitude(last.getLongitude());
            return true;
        }

        int segStart = insertionPoint - 1;
        if (segStart < 0) {
            Location first = polylinePoints.get(0);
            out.setLatitude(first.getLatitude());
            out.setLongitude(first.getLongitude());
            return true;
        }

        double segLen = cumDist[insertionPoint] - cumDist[segStart];
        if (segLen <= 0) {
            Location p = polylinePoints.get(segStart);
            out.setLatitude(p.getLatitude());
            out.setLongitude(p.getLongitude());
            return true;
        }

        double fraction = (distanceMeters - cumDist[segStart]) / segLen;
        Location p0 = polylinePoints.get(segStart);
        Location p1 = polylinePoints.get(insertionPoint);
        out.setLatitude(p0.getLatitude() + fraction * (p1.getLatitude() - p0.getLatitude()));
        out.setLongitude(p0.getLongitude() + fraction * (p1.getLongitude() - p0.getLongitude()));
        return true;
    }

    /**
     * Extracts a sub-polyline between two distances along the route, returning LatLng
     * points ready for use in a Google Maps Polyline. The result includes interpolated
     * start/end points plus all original polyline vertices in between.
     *
     * @param polylinePoints decoded polyline points
     * @param cumDist        precomputed cumulative distances
     * @param startDist      start distance in meters
     * @param endDist        end distance in meters
     * @param out            list to populate (cleared first); null-safe (returns empty)
     */
    public static void subPolylineLatLng(List<Location> polylinePoints, double[] cumDist,
                                          double startDist, double endDist,
                                          List<LatLng> out) {
        out.clear();
        if (polylinePoints == null || polylinePoints.isEmpty()
                || cumDist == null || startDist >= endDist) {
            return;
        }
        // Add interpolated start point
        Location loc = interpolateAlongPolyline(polylinePoints, cumDist, startDist);
        if (loc == null) return;
        out.add(new LatLng(loc.getLatitude(), loc.getLongitude()));

        // Add all original polyline vertices between startDist and endDist
        for (int i = 0; i < cumDist.length; i++) {
            if (cumDist[i] > startDist && cumDist[i] < endDist) {
                Location p = polylinePoints.get(i);
                out.add(new LatLng(p.getLatitude(), p.getLongitude()));
            }
        }

        // Add interpolated end point
        loc = interpolateAlongPolyline(polylinePoints, cumDist, endDist);
        if (loc == null) return;
        out.add(new LatLng(loc.getLatitude(), loc.getLongitude()));
    }

    /**
     * Returns the bearing (in degrees clockwise from north) of the polyline segment
     * at the given distance along the polyline. Returns NaN if inputs are invalid.
     *
     * @param polylinePoints decoded polyline points (lat/lng)
     * @param cumDist        precomputed cumulative distances
     * @param distanceMeters target distance along the polyline in meters
     * @return bearing in degrees [0, 360), or NaN
     */
    public static double headingAlongPolyline(List<Location> polylinePoints,
                                               double[] cumDist,
                                               double distanceMeters) {
        if (polylinePoints == null || polylinePoints.size() < 2 || cumDist == null) {
            return Double.NaN;
        }

        // Clamp to valid range
        if (distanceMeters <= 0) {
            return bearing(polylinePoints.get(0), polylinePoints.get(1));
        }

        int idx = Arrays.binarySearch(cumDist, distanceMeters);
        int insertionPoint;
        if (idx >= 0) {
            insertionPoint = idx + 1;
        } else {
            insertionPoint = -idx - 1;
        }

        if (insertionPoint >= polylinePoints.size()) {
            int n = polylinePoints.size();
            return bearing(polylinePoints.get(n - 2), polylinePoints.get(n - 1));
        }

        int segStart = insertionPoint - 1;
        if (segStart < 0) {
            return bearing(polylinePoints.get(0), polylinePoints.get(1));
        }

        return bearing(polylinePoints.get(segStart), polylinePoints.get(insertionPoint));
    }

    /**
     * Computes the initial bearing from point A to point B in degrees [0, 360).
     */
    private static double bearing(Location a, Location b) {
        double lat1 = Math.toRadians(a.getLatitude());
        double lat2 = Math.toRadians(b.getLatitude());
        double dLon = Math.toRadians(b.getLongitude() - a.getLongitude());

        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                 - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double brng = Math.toDegrees(Math.atan2(y, x));
        return (brng + 360) % 360;
    }
}
