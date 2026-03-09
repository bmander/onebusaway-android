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
 * Beta distribution utilities for vehicle position prediction.
 * Provides parameter estimation from Kalman filter output, PDF, CDF,
 * and PIT (Probability Integral Transform) computation.
 */
public final class BetaDistribution {

    /** Fallback upper bound on vehicle speed in m/s (~56 mph) when schedule is unavailable. */
    public static final double V_MAX_FALLBACK_MPS = 25.0;
    /** Multiplier applied to local schedule speed to get the Beta distribution upper bound. */
    public static final double V_MAX_SCHEDULE_FACTOR = 1.5;

    private static final double CDF_EPSILON = 1e-10;
    private static final int CDF_MAX_ITERATIONS = 200;

    private BetaDistribution() {
    }

    /** Parameters for a Beta distribution over normalized velocity [0, 1]. */
    public static final class BetaParams {
        public final double alpha;
        public final double beta;
        public final double vMax;

        public BetaParams(double alpha, double beta, double vMax) {
            this.alpha = alpha;
            this.beta = beta;
            this.vMax = vMax;
        }
    }

    /**
     * Computes Beta distribution parameters from a Kalman filter speed estimate.
     *
     * @param speed         estimated speed in m/s
     * @param variance      velocity variance from Kalman filter
     * @param scheduleSpeed local schedule speed in m/s (0 if unavailable)
     * @return BetaParams, or null if the distribution is invalid
     */
    public static BetaParams fromKalmanEstimate(double speed, double variance,
                                                 double scheduleSpeed) {
        if (variance <= 0 || speed <= 0) return null;

        double vMaxSchedule = scheduleSpeed > 0
                ? scheduleSpeed * V_MAX_SCHEDULE_FACTOR : V_MAX_FALLBACK_MPS;
        double vMaxUncertainty = speed + 3 * Math.sqrt(variance);
        double vMax = Math.max(vMaxSchedule, vMaxUncertainty);

        double normMean = speed / vMax;
        double normVar = variance / (vMax * vMax);

        if (normMean <= 0 || normMean >= 1 || normVar >= normMean * (1 - normMean)) {
            return null;
        }

        double sumAB = normMean * (1 - normMean) / normVar - 1;
        double alpha = normMean * sumAB;
        double beta = (1 - normMean) * sumAB;

        if (alpha <= 0 || beta <= 0) return null;
        return new BetaParams(alpha, beta, vMax);
    }

    /** Unnormalized Beta PDF: t^(a-1) * (1-t)^(b-1) for t in (0,1). */
    public static double pdf(double t, double alpha, double beta) {
        if (t <= 0 || t >= 1) return 0;
        return Math.exp((alpha - 1) * Math.log(t) + (beta - 1) * Math.log(1 - t));
    }

    /**
     * Regularized incomplete beta function I_x(a, b) = CDF of Beta(a, b) at x.
     * Uses Lentz's continued fraction method with symmetry relation.
     */
    public static double cdf(double x, double a, double b) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;

        // Symmetry relation for faster convergence
        if (x > (a + 1) / (a + b + 2)) {
            return 1.0 - cdf(1.0 - x, b, a);
        }

        double lnPrefactor = a * Math.log(x) + b * Math.log(1 - x)
                - Math.log(a) - lnBeta(a, b);
        double prefactor = Math.exp(lnPrefactor);

        // Lentz's continued fraction for I_x(a, b)
        double f = 1.0;
        double c = 1.0;
        double d = 1.0 - (a + b) * x / (a + 1);
        if (Math.abs(d) < 1e-30) d = 1e-30;
        d = 1.0 / d;
        f = d;

        for (int m = 1; m <= CDF_MAX_ITERATIONS; m++) {
            // Even step: a_{2m}
            int m2 = 2 * m;
            double numerator = m * (b - m) * x / ((a + m2 - 1) * (a + m2));

            d = 1.0 + numerator * d;
            if (Math.abs(d) < 1e-30) d = 1e-30;
            d = 1.0 / d;

            c = 1.0 + numerator / c;
            if (Math.abs(c) < 1e-30) c = 1e-30;

            f *= c * d;

            // Odd step: a_{2m+1}
            numerator = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1));

            d = 1.0 + numerator * d;
            if (Math.abs(d) < 1e-30) d = 1e-30;
            d = 1.0 / d;

            c = 1.0 + numerator / c;
            if (Math.abs(c) < 1e-30) c = 1e-30;

            double delta = c * d;
            f *= delta;

            if (Math.abs(delta - 1.0) < CDF_EPSILON) break;
        }

        return prefactor * f;
    }

    /**
     * Computes the PIT (Probability Integral Transform) value for an observed position.
     *
     * @param params         Beta distribution parameters (may be null)
     * @param lastDist       distance at last AVL observation (meters)
     * @param lastTimeMs     time of last AVL observation (epoch ms)
     * @param observedDist   distance at new AVL observation (meters)
     * @param observedTimeMs time of new AVL observation (epoch ms)
     * @return CDF value in [0,1], or null if params are null or timing is invalid
     */
    public static Double computePIT(BetaParams params, double lastDist, long lastTimeMs,
                                     double observedDist, long observedTimeMs) {
        if (params == null) return null;
        if (observedTimeMs <= lastTimeMs) return null;

        double dtSec = (observedTimeMs - lastTimeMs) / 1000.0;
        double posMin = lastDist;
        double posMax = lastDist + params.vMax * dtSec;

        if (posMax <= posMin) return null;

        // Normalize observed distance to [0, 1] in Beta space
        double t = (observedDist - posMin) / (posMax - posMin);

        // Clamp: backward motion -> 0, beyond max -> 1
        if (t <= 0) return 0.0;
        if (t >= 1) return 1.0;

        return cdf(t, params.alpha, params.beta);
    }

    /** Log of the Beta function: ln(B(a,b)) = lnGamma(a) + lnGamma(b) - lnGamma(a+b). */
    private static double lnBeta(double a, double b) {
        return lnGamma(a) + lnGamma(b) - lnGamma(a + b);
    }

    private static final double[] LN_GAMMA_COEF = {
            76.18009172947146,
            -86.50532032941677,
            24.01409824083091,
            -1.231739572450155,
            0.1208650973866179e-2,
            -0.5395239384953e-5
    };

    /** Lanczos approximation for ln(Gamma(x)), valid for x > 0. */
    static double lnGamma(double x) {
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double c : LN_GAMMA_COEF) {
            y += 1.0;
            ser += c / y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }
}
