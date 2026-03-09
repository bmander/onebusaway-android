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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estimates speed using a mean-reverting Kalman filter over vehicle trajectory history.
 *
 * State vector: [distance_along_trip, velocity]
 * Process model: velocity decays exponentially toward a prior (schedule speed) with
 * time constant τ. This gives smooth, responsive estimates for fresh data that gracefully
 * degrade toward the schedule speed as data goes stale.
 *
 * Each AVL observation is incorporated exactly once via a Kalman update step, eliminating
 * the wobble artifact of window-based averaging.
 */
public class KalmanSpeedEstimator implements SpeedEstimator {

    // Process noise spectral density (m/s²)².
    // Bus acceleration variance ~(0.7 m/s²)² = 0.5.
    // Over a 30s AVL gap this adds ~15 (m/s)² to velocity variance (σ≈3.9 m/s).
    static final double PROCESS_NOISE_ACCEL = 0.5;

    // Measurement noise variance (m²).
    // AVL position snapped to route shape: ~20m std dev → 400 m²
    static final double MEASUREMENT_NOISE_R = 400.0;

    // Mean-reversion time constant (ms).
    // Velocity decays toward the prior with this half-life-ish constant.
    // τ=120s means ~63% decay in 2 minutes, ~86% in 4 minutes.
    static final long VELOCITY_TAU_MS = 120_000;

    // Minimum dt between observations to process (avoid numerical issues)
    private static final long MIN_DT_MS = 500;

    // Maximum gap before reinitializing the filter
    private static final long MAX_STALE_MS = 600_000;

    // Initial velocity uncertainty: (5 m/s)² — covers bus speed range, converges quickly
    private static final double INITIAL_VEL_VARIANCE = 25.0;

    private final Map<String, KalmanState> mStateMap = new HashMap<>();
    private double mLastPredictedVelVariance;

    @Override
    public Double estimateSpeed(String vehicleId, VehicleState state,
                                VehicleTrajectoryTracker tracker) {
        return estimateSpeed(vehicleId, state, tracker, 0.0);
    }

    /**
     * Estimates speed with a velocity prior that the filter reverts toward as data ages.
     * @param velPrior the prior velocity (e.g., schedule speed) in m/s, or 0 if unknown
     */
    public Double estimateSpeed(String vehicleId, VehicleState state,
                                VehicleTrajectoryTracker tracker, double velPrior) {
        mLastPredictedVelVariance = 0;

        String tripId = state.getActiveTripId();
        if (tripId == null) return null;

        List<VehicleHistoryEntry> history = tracker.getHistory(tripId);
        if (history.isEmpty()) return null;

        KalmanState ks = mStateMap.get(tripId);

        for (VehicleHistoryEntry entry : history) {
            Double dist = entry.getBestDistanceAlongTrip();
            long time = entry.getLastLocationUpdateTime();
            if (dist == null || time <= 0) continue;

            if (ks == null) {
                ks = new KalmanState(dist, velPrior, time);
                mStateMap.put(tripId, ks);
                continue;
            }

            if (time <= ks.lastUpdateTimeMs) continue;

            long dtMs = time - ks.lastUpdateTimeMs;
            if (dtMs < MIN_DT_MS) continue;

            if (dtMs > MAX_STALE_MS) {
                ks = new KalmanState(dist, velPrior, time);
                mStateMap.put(tripId, ks);
                continue;
            }

            predictAndUpdate(ks, dist, dtMs, velPrior);
        }

        if (ks == null || !ks.initialized) return null;

        // Predict velocity forward to now without mutating filter state.
        // This applies the mean-reversion decay so the returned velocity
        // becomes more conservative as data goes stale.
        long now = System.currentTimeMillis();
        long ageSinceUpdate = now - ks.lastUpdateTimeMs;
        if (ageSinceUpdate > 0) {
            double alpha = Math.exp(-(double) ageSinceUpdate / VELOCITY_TAU_MS);
            double dt = ageSinceUpdate / 1000.0;
            double decayedVel = alpha * ks.x_vel + (1.0 - alpha) * velPrior;
            mLastPredictedVelVariance = alpha * alpha * ks.p11
                    + PROCESS_NOISE_ACCEL * alpha * alpha * dt;
            return Math.max(0.0, decayedVel);
        }

        mLastPredictedVelVariance = ks.p11;
        return Math.max(0.0, ks.x_vel);
    }

    @Override
    public double getLastPredictedVelVariance() {
        return mLastPredictedVelVariance;
    }

    /**
     * Runs the Kalman predict step (with mean-reverting velocity) followed by the update step.
     */
    private void predictAndUpdate(KalmanState ks, double measurement, long dtMs, double velPrior) {
        double dt = dtMs / 1000.0;
        double tau = VELOCITY_TAU_MS / 1000.0;

        // Mean-reversion factor: α = exp(-dt / τ)
        double alpha = Math.exp(-dt / tau);

        // Effective integration time for the decaying velocity component
        // integral of exp(-t/τ) from 0 to dt = τ * (1 - α)
        double effectiveDt = tau * (1.0 - alpha);

        // --- Predict step ---
        // Velocity decays toward velPrior:
        //   vel_pred = α * vel + (1 - α) * velPrior
        // Distance integrates the decaying velocity:
        //   dist_pred = dist + velPrior * dt + (vel - velPrior) * effectiveDt
        double velDev = ks.x_vel - velPrior;
        double predDist = ks.x_dist + velPrior * dt + velDev * effectiveDt;
        double predVel = alpha * ks.x_vel + (1.0 - alpha) * velPrior;

        // Covariance predict: P_pred = F * P * F' + Q
        // F = [[1, effectiveDt], [0, α]]
        // Q ≈ q * [[dt³/3, α*dt²/2], [α*dt²/2, α²*dt]]
        double q = PROCESS_NOISE_ACCEL;
        double f01 = effectiveDt;
        double f11 = alpha;

        // F * P * F'
        double fp00 = ks.p00 + f01 * ks.p01;
        double fp01 = ks.p01 * f11 + f01 * ks.p11 * f11;
        double fp10 = f11 * ks.p01;

        double pp00 = fp00 + (ks.p01 + f01 * ks.p11) * f01;
        double pp01 = fp01;
        double pp11 = f11 * ks.p11 * f11;

        // Add process noise Q
        pp00 += q * dt * dt * dt / 3.0;
        pp01 += q * alpha * dt * dt / 2.0;
        pp11 += q * alpha * alpha * dt;

        // --- Update step ---
        // H = [1, 0], so innovation = measurement - predDist
        double innovation = measurement - predDist;
        double s = pp00 + MEASUREMENT_NOISE_R;
        double k0 = pp00 / s;
        double k1 = pp01 / s;

        ks.x_dist = predDist + k0 * innovation;
        ks.x_vel = predVel + k1 * innovation;

        // Joseph form for numerical stability: P = (I - K*H) * P_pred * (I - K*H)' + K*R*K'
        // Simplified since H = [1, 0]:
        double imk0 = 1.0 - k0;
        ks.p00 = imk0 * pp00 + k0 * k0 * MEASUREMENT_NOISE_R;
        ks.p01 = imk0 * pp01 - imk0 * k1 * pp00 + k0 * k1 * MEASUREMENT_NOISE_R;
        ks.p11 = pp11 - k1 * pp01 - k1 * pp01 + k1 * k1 * (pp00 + MEASUREMENT_NOISE_R);

        ks.lastUpdateTimeMs += dtMs;
        ks.initialized = true;
    }

    @Override
    public void clearState() {
        mStateMap.clear();
    }

    /**
     * Per-trip Kalman filter state.
     */
    static class KalmanState {
        double x_dist;
        double x_vel;
        double p00, p01, p11;
        long lastUpdateTimeMs;
        boolean initialized;

        KalmanState(double dist, double vel, long timeMs) {
            this.x_dist = dist;
            this.x_vel = vel;
            this.lastUpdateTimeMs = timeMs;
            this.initialized = false;
            this.p00 = MEASUREMENT_NOISE_R;
            this.p01 = 0;
            this.p11 = INITIAL_VEL_VARIANCE;
        }
    }
}
