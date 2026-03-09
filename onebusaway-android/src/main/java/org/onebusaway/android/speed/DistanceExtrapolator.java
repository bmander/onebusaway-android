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
     *
     * @param polylinePoints decoded polyline points
     * @return cumulative distance array (same length as polylinePoints), or null
     */
    public static double[] buildCumulativeDistances(List<Location> polylinePoints) {
        if (polylinePoints == null || polylinePoints.isEmpty()) return null;
        double[] cumDist = new double[polylinePoints.size()];
        cumDist[0] = 0;
        for (int i = 1; i < polylinePoints.size(); i++) {
            cumDist[i] = cumDist[i - 1]
                    + polylinePoints.get(i - 1).distanceTo(polylinePoints.get(i));
        }
        return cumDist;
    }

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
}
