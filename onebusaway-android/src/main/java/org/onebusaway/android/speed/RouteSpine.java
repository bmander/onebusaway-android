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

import java.util.List;
import java.util.TreeSet;

/**
 * Reusable geometric sampling of a polyline. Builds evenly-spaced points along
 * a route with their positions and perpendicular ("vertebra") geometry.
 * Pure Java — no Android UI or Maps dependencies.
 */
public final class RouteSpine {

    public static final double DEFAULT_SPACING_METERS = 20.0;

    private static final double DEGREES_PER_METER =
            Math.toDegrees(1.0) / DistanceExtrapolator.EARTH_RADIUS_METERS;

    // Public arrays for zero-copy access by consumers
    public double[] distances;
    public double[] lats;
    public double[] lngs;
    public double[] perpCos;
    public double[] perpSin;
    public double[] latCosInv;

    // Cache keys
    private List<Location> cachedShape;
    private double cachedReferencePoint = Double.NaN;
    private int generation;

    /**
     * Returns true if the spine needs to be rebuilt because the shape or
     * reference point has changed.
     */
    public boolean needsRebuild(List<Location> shape, double referencePoint) {
        return distances == null
                || shape != cachedShape
                || Double.doubleToLongBits(referencePoint)
                   != Double.doubleToLongBits(cachedReferencePoint);
    }

    /**
     * Builds the spine from a polyline by merging:
     * 1. Regular interval points (every spacingMeters)
     * 2. Every vertex on the original polyline
     * 3. A reference point (e.g. AVL position), always included as a sample
     *
     * @param shape          decoded polyline points
     * @param cumDist        precomputed cumulative distances
     * @param spacingMeters  spacing between regular interval samples
     * @param referencePoint distance along the polyline for the reference point
     */
    public void build(List<Location> shape, double[] cumDist,
                      double spacingMeters, double referencePoint) {
        if (shape == null || shape.isEmpty() || cumDist == null) return;
        cachedShape = shape;
        cachedReferencePoint = referencePoint;
        generation++;
        double totalDist = cumDist[cumDist.length - 1];

        TreeSet<Double> distSet = new TreeSet<>();

        // Regular interval points
        for (double d = 0; d <= totalDist; d += spacingMeters) {
            distSet.add(d);
        }
        distSet.add(totalDist);

        // Every polyline vertex
        for (double d : cumDist) {
            distSet.add(d);
        }

        // Reference point
        if (!Double.isNaN(referencePoint)
                && referencePoint >= 0 && referencePoint <= totalDist) {
            distSet.add(referencePoint);
        }

        int count = distSet.size();
        distances = new double[count];
        lats = new double[count];
        lngs = new double[count];
        perpCos = new double[count];
        perpSin = new double[count];
        latCosInv = new double[count];

        Location reusable = new Location("spine");
        int i = 0;
        for (Double d : distSet) {
            distances[i] = d;
            DistanceExtrapolator.interpolateAlongPolyline(shape, cumDist, d, reusable);
            double lat = reusable.getLatitude();
            double lng = reusable.getLongitude();
            lats[i] = lat;
            lngs[i] = lng;

            double heading = DistanceExtrapolator.headingAlongPolyline(
                    shape, cumDist, d);
            double perpBearing = Math.toRadians(heading + 90.0);
            perpCos[i] = Math.cos(perpBearing) * DEGREES_PER_METER;
            perpSin[i] = Math.sin(perpBearing) * DEGREES_PER_METER;
            latCosInv[i] = 1.0 / Math.cos(Math.toRadians(lat));
            i++;
        }
    }

    public int size() {
        return distances != null ? distances.length : 0;
    }

    /** Monotonically increasing counter, incremented on each {@link #build} call. */
    public int getGeneration() {
        return generation;
    }

    public double distanceAt(int i) {
        return distances[i];
    }

    public double latAt(int i) {
        return lats[i];
    }

    public double lngAt(int i) {
        return lngs[i];
    }

    /**
     * Binary search returning the insertion point for the given distance.
     */
    public int findIndex(double distance) {
        int idx = java.util.Arrays.binarySearch(distances, distance);
        return idx >= 0 ? idx : -idx - 1;
    }
}
