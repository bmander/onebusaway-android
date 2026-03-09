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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Records model predictions every second and scores them against actual AVL observations
 * using the Probability Integral Transform (PIT). A well-calibrated model produces
 * PIT values that are uniformly distributed on [0,1].
 *
 * Thread safety: only accessed from the main thread (UI refresh handler).
 */
public final class CalibrationTracker {

    private static final int MAX_PREDICTION_RECORDS = 300;
    private static final int MAX_CALIBRATION_SAMPLES = 500;
    /** Maximum age gap (ms) between a prediction record and an AVL observation to consider a match. */
    private static final long MAX_PREDICTION_AGE_MS = 5_000;
    public static final int DEFAULT_HISTOGRAM_BINS = 10;

    private final Deque<PredictionRecord> mPredictions = new ArrayDeque<>();
    private final Deque<CalibrationSample> mSamples = new ArrayDeque<>();
    private long mLastSeenAvlTime = 0;

    private static final class PredictionRecord {
        final long timestamp;
        final double lastDist;
        final long lastAvlTime;
        final BetaDistribution.BetaParams betaParams;

        PredictionRecord(long timestamp, double lastDist, long lastAvlTime,
                         BetaDistribution.BetaParams betaParams) {
            this.timestamp = timestamp;
            this.lastDist = lastDist;
            this.lastAvlTime = lastAvlTime;
            this.betaParams = betaParams;
        }
    }

    private static final class CalibrationSample {
        final double pitValue;

        CalibrationSample(double pitValue) {
            this.pitValue = pitValue;
        }
    }

    /**
     * Records the current model prediction. Called every UI refresh (~1s).
     *
     * @param now            current time in epoch ms
     * @param lastDist       distance at the most recent valid AVL observation
     * @param lastAvlTime    time of the most recent valid AVL observation
     * @param speed          Kalman estimated speed (m/s)
     * @param variance       Kalman velocity variance
     * @param scheduleSpeed  local schedule speed (m/s)
     */
    public void recordPrediction(long now, double lastDist, long lastAvlTime,
                                  double speed, double variance, double scheduleSpeed) {
        BetaDistribution.BetaParams bp = BetaDistribution.fromKalmanEstimate(
                speed, variance, scheduleSpeed);
        if (bp == null) return;

        mPredictions.addLast(new PredictionRecord(now, lastDist, lastAvlTime, bp));

        while (mPredictions.size() > MAX_PREDICTION_RECORDS) {
            mPredictions.removeFirst();
        }
    }

    /**
     * Checks if the given history entry represents a new AVL observation and,
     * if so, scores it against the closest prediction record.
     *
     * @param entry the latest vehicle history entry
     */
    public void checkNewAvl(VehicleHistoryEntry entry) {
        if (entry == null) return;
        long avlTime = entry.getLastLocationUpdateTime();
        if (avlTime <= 0 || avlTime == mLastSeenAvlTime) return;

        Double observedDist = entry.getBestDistanceAlongTrip();
        if (observedDist == null) return;

        mLastSeenAvlTime = avlTime;

        PredictionRecord best = findPredictionAt(avlTime);
        if (best == null) return;

        Double pit = BetaDistribution.computePIT(
                best.betaParams, best.lastDist, best.lastAvlTime,
                observedDist, avlTime);
        if (pit == null) return;

        mSamples.addLast(new CalibrationSample(pit));
        while (mSamples.size() > MAX_CALIBRATION_SAMPLES) {
            mSamples.removeFirst();
        }
    }

    /**
     * Finds the prediction record closest to the given time, within MAX_PREDICTION_AGE_MS.
     * Returns null if no prediction is close enough.
     */
    private PredictionRecord findPredictionAt(long time) {
        if (mPredictions.isEmpty() || time <= 0) return null;

        PredictionRecord best = null;
        long bestDelta = Long.MAX_VALUE;
        for (PredictionRecord pr : mPredictions) {
            long delta = Math.abs(pr.timestamp - time);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = pr;
            } else {
                // Predictions are time-ordered; delta is now increasing, so we've passed the closest
                break;
            }
        }

        if (best == null || bestDelta > MAX_PREDICTION_AGE_MS) return null;
        return best;
    }

    /**
     * Computes the PIT value for a given observation using the prediction record
     * that was active at that time. Returns null if no prediction was recorded
     * near the AVL time.
     */
    public Double computePitAt(long avlTime, double observedDist) {
        PredictionRecord best = findPredictionAt(avlTime);
        if (best == null) return null;
        return BetaDistribution.computePIT(
                best.betaParams, best.lastDist, best.lastAvlTime,
                observedDist, avlTime);
    }

    /** Returns the number of scored calibration samples. */
    public int getSampleCount() {
        return mSamples.size();
    }

    /** Returns the timestamp of the oldest prediction record, or 0 if empty. */
    public long getMinPredictionTime() {
        PredictionRecord first = mPredictions.peekFirst();
        return first != null ? first.timestamp : 0;
    }

    /** Returns the timestamp of the newest prediction record, or 0 if empty. */
    public long getMaxPredictionTime() {
        PredictionRecord last = mPredictions.peekLast();
        return last != null ? last.timestamp : 0;
    }

    /**
     * Returns a normalized PIT histogram.
     *
     * @param numBins number of histogram bins
     * @return array of length numBins where values sum to 1.0 (or all zeros if no samples)
     */
    public double[] getPitHistogram(int numBins) {
        double[] counts = new double[numBins];
        if (mSamples.isEmpty()) return counts;

        for (CalibrationSample s : mSamples) {
            int bin = (int) (s.pitValue * numBins);
            if (bin >= numBins) bin = numBins - 1;
            counts[bin]++;
        }

        double total = mSamples.size();
        for (int i = 0; i < numBins; i++) {
            counts[i] /= total;
        }
        return counts;
    }

    /**
     * Returns the empirical coverage at a given confidence level.
     * E.g., getCoverageAt(0.80) returns the fraction of PIT values in [0.10, 0.90].
     */
    public double getCoverageAt(double level) {
        if (mSamples.isEmpty()) return 0;
        double tail = (1.0 - level) / 2.0;
        int count = 0;
        for (CalibrationSample s : mSamples) {
            if (s.pitValue >= tail && s.pitValue <= 1.0 - tail) {
                count++;
            }
        }
        return (double) count / mSamples.size();
    }

    /**
     * Returns the Mean Absolute Calibration Error: average |empirical - uniform| across bins.
     * A perfectly calibrated model returns 0.
     */
    public double getMeanAbsoluteCalibrationError(double[] histogram) {
        double uniform = 1.0 / histogram.length;
        double sum = 0;
        for (double h : histogram) {
            sum += Math.abs(h - uniform);
        }
        return sum / histogram.length;
    }
}
