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
     * Interpolates a position along a polyline at a given distance from the start.
     * Walks along consecutive segments, accumulating distance, and linearly interpolates
     * within the segment that contains the target distance.
     *
     * @param polylinePoints decoded polyline points (lat/lng)
     * @param distanceMeters target distance along the polyline in meters
     * @return interpolated Location, or null if the polyline is empty
     */
    public static Location interpolateAlongPolyline(List<Location> polylinePoints,
                                                     double distanceMeters) {
        if (polylinePoints == null || polylinePoints.isEmpty()) return null;
        if (distanceMeters <= 0) return polylinePoints.get(0);

        double accumulated = 0;
        for (int i = 1; i < polylinePoints.size(); i++) {
            Location p0 = polylinePoints.get(i - 1);
            Location p1 = polylinePoints.get(i);
            float segLen = p0.distanceTo(p1);

            if (accumulated + segLen >= distanceMeters) {
                double fraction = (distanceMeters - accumulated) / segLen;
                Location result = new Location("extrapolated");
                result.setLatitude(p0.getLatitude() + fraction * (p1.getLatitude() - p0.getLatitude()));
                result.setLongitude(p0.getLongitude() + fraction * (p1.getLongitude() - p0.getLongitude()));
                return result;
            }
            accumulated += segLen;
        }
        // Past the end — return last point
        return polylinePoints.get(polylinePoints.size() - 1);
    }
}
