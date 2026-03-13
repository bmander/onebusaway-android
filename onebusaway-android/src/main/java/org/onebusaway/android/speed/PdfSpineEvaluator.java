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

/**
 * Composes a {@link RouteSpine} with gamma PDF evaluation. Builds a lookup table
 * from {@link GammaSpeedModel} and converts spine distances into perpendicular offsets.
 * Pure Java — no Android UI or Maps dependencies.
 */
public final class PdfSpineEvaluator {

    /** Meters of perpendicular offset per unit of raw PDF value. */
    public static final double PDF_METERS_PER_UNIT = 197.0;

    static final int PDF_TABLE_SIZE = 256;
    static final double PDF_TABLE_MAX_U = 50.0;
    private static final double PDF_TABLE_INV_STEP =
            (PDF_TABLE_SIZE - 1) / PDF_TABLE_MAX_U;

    private final double[] mNormalizedPdfTable = new double[PDF_TABLE_SIZE];

    /** (distance[i] - avlDist) * MPS_TO_MPH, rebuilt when spine or AVL changes. */
    private double[] distDeltaMph;

    private double cachedAlpha = Double.NaN;
    private double cachedAvlDist = Double.NaN;
    private int cachedSpineGeneration = -1;

    /**
     * Rebuilds the PDF lookup table if alpha has changed.
     */
    public void ensureTable(double alpha) {
        if (Double.doubleToLongBits(alpha)
                == Double.doubleToLongBits(cachedAlpha)) {
            return;
        }
        cachedAlpha = alpha;
        GammaSpeedModel.GammaParams unitParams =
                new GammaSpeedModel.GammaParams(alpha, 1.0);
        double step = PDF_TABLE_MAX_U / (PDF_TABLE_SIZE - 1);
        for (int k = 0; k < PDF_TABLE_SIZE; k++) {
            double u = k * step;
            mNormalizedPdfTable[k] = (u > 0)
                    ? GammaSpeedModel.pdf(u, unitParams) : 0;
        }
    }

    /**
     * Rebuilds the distDeltaMph array if the spine or AVL distance has changed.
     */
    public void ensureDistDeltas(RouteSpine spine, double avlDist) {
        if (spine.getGeneration() == cachedSpineGeneration
                && Double.doubleToLongBits(avlDist)
                   == Double.doubleToLongBits(cachedAvlDist)) {
            return;
        }
        cachedSpineGeneration = spine.getGeneration();
        cachedAvlDist = avlDist;
        int n = spine.size();
        if (distDeltaMph == null || distDeltaMph.length != n) {
            distDeltaMph = new double[n];
        }
        for (int i = 0; i < n; i++) {
            distDeltaMph[i] = (spine.distanceAt(i) - avlDist)
                    * GammaSpeedModel.MPS_TO_MPH;
        }
    }

    /**
     * Computes the perpendicular offset in meters for spine point i
     * using the cached PDF table and the given invScaleDt.
     *
     * @param spineIndex index into the spine arrays
     * @param invScaleDt 1 / (scale * dtSec)
     * @return offset in meters
     */
    public double computeOffset(int spineIndex, double invScaleDt) {
        double u = distDeltaMph[spineIndex] * invScaleDt;
        double pdfVal;
        if (u <= 0 || u >= PDF_TABLE_MAX_U) {
            pdfVal = 0;
        } else {
            double idx = u * PDF_TABLE_INV_STEP;
            int lo = (int) idx;
            double frac = idx - lo;
            pdfVal = mNormalizedPdfTable[lo]
                    + frac * (mNormalizedPdfTable[lo + 1]
                              - mNormalizedPdfTable[lo]);
        }
        return pdfVal * PDF_METERS_PER_UNIT;
    }
}
