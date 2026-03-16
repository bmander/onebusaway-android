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
package org.onebusaway.android.extrapolation.math.speed;

/**
 * Five-parameter power-law blend gamma distribution (H12) for vehicle speed modeling.
 * Pure-math utility, no Android dependencies.
 */
public final class GammaSpeedModel {

    // Fitted parameters
    private static final double START_B0 = 0.1793;
    private static final double END_B0 = 0.0604;
    private static final double KINK = 26.95; // mph
    private static final double C = 1.0793;
    private static final double D = 0.1699;

    public static final double MPS_TO_MPH = 2.23694;

    private static final int CDF_MAX_ITERATIONS = 200;
    private static final double CDF_EPSILON = 1e-10;

    private GammaSpeedModel() {
    }

    /** Parameters for a Gamma distribution: shape (alpha) and scale (theta). */
    public static final class GammaParams {
        public final double alpha;
        public final double scale;

        public GammaParams(double alpha, double scale) {
            this.alpha = alpha;
            this.scale = scale;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GammaParams)) return false;
            GammaParams that = (GammaParams) o;
            return Double.doubleToLongBits(alpha) == Double.doubleToLongBits(that.alpha)
                    && Double.doubleToLongBits(scale) == Double.doubleToLongBits(that.scale);
        }

        @Override
        public int hashCode() {
            long h = 17;
            h = 31 * h + Double.doubleToLongBits(alpha);
            h = 31 * h + Double.doubleToLongBits(scale);
            return (int) (h ^ (h >>> 32));
        }
    }

    /**
     * Computes Gamma distribution parameters from schedule and previous observed speeds.
     *
     * @param schedSpeedMps scheduled speed in m/s
     * @param prevSpeedMps  previous observed speed in m/s
     * @return GammaParams, or null if inputs are invalid
     */
    public static GammaParams fromSpeeds(double schedSpeedMps, double prevSpeedMps) {
        // Fallback: prevSpeed <= 0 → use schedSpeed
        if (prevSpeedMps <= 0) {
            prevSpeedMps = schedSpeedMps;
        }
        // schedSpeed <= 0 → return null
        if (schedSpeedMps <= 0) {
            return null;
        }

        double vSchedMph = schedSpeedMps * MPS_TO_MPH;
        double vPrevMph = prevSpeedMps * MPS_TO_MPH;

        double vEff = Math.pow(vSchedMph, 1.0 - D) * Math.pow(vPrevMph, D);

        double b0 = beta0(vEff);
        double alpha = b0 * C * vEff;
        double scale = C / b0;

        if (alpha <= 0 || scale <= 0) {
            return null;
        }

        return new GammaParams(alpha, scale);
    }

    /** Piecewise linear ramp from START_B0 to END_B0, flat after KINK. */
    private static double beta0(double vEff) {
        if (vEff >= KINK) {
            return END_B0;
        }
        if (vEff <= 0) {
            return START_B0;
        }
        return START_B0 + (END_B0 - START_B0) * (vEff / KINK);
    }

    /**
     * Returns the mean speed in m/s from the gamma distribution.
     */
    public static double meanSpeedMps(GammaParams params) {
        return params.alpha * params.scale / MPS_TO_MPH;
    }

    /**
     * Returns the median (50th percentile) speed in m/s from the gamma distribution.
     * For a right-skewed gamma, median &lt; mean, giving a more intuitive
     * "equal probability ahead or behind" position estimate.
     */
    public static double medianSpeedMps(GammaParams params) {
        return quantileMps(0.50, params);
    }

    /**
     * Gamma PDF: f(x; alpha, scale) = x^(alpha-1) * exp(-x/scale) / (scale^alpha * Gamma(alpha))
     *
     * @param speedMph speed in mph
     * @param params   gamma parameters
     * @return PDF value
     */
    public static double pdf(double speedMph, GammaParams params) {
        if (speedMph <= 0) return 0;
        double a = params.alpha;
        double s = params.scale;
        double lnPdf = (a - 1) * Math.log(speedMph) - speedMph / s
                - a * Math.log(s) - lnGamma(a);
        return Math.exp(lnPdf);
    }

    /**
     * Regularized lower incomplete gamma function P(a, x) = CDF of Gamma(a, 1) at x/scale.
     * Uses series expansion for x < a+1, continued fraction otherwise.
     *
     * @param speedMph speed in mph
     * @param params   gamma parameters
     * @return CDF value in [0, 1]
     */
    public static double cdf(double speedMph, GammaParams params) {
        if (speedMph <= 0) return 0;
        double x = speedMph / params.scale;
        double a = params.alpha;
        return regularizedGammaP(a, x);
    }

    /**
     * Returns the speed at the given quantile in m/s.
     *
     * @param p      probability in (0, 1)
     * @param params gamma parameters
     * @return speed in m/s at the given quantile
     */
    public static double quantileMps(double p, GammaParams params) {
        return quantile(p, params) / MPS_TO_MPH;
    }

    /**
     * Inverse CDF via bisection.
     *
     * @param p      probability in (0, 1)
     * @param params gamma parameters
     * @return speed in mph at the given quantile
     */
    public static double quantile(double p, GammaParams params) {
        if (p <= 0) return 0;
        if (p >= 1) return Double.MAX_VALUE;

        // Initial bracket: [0, mean + 10*stddev]
        double mean = params.alpha * params.scale;
        double hi = mean + 10 * Math.sqrt(params.alpha) * params.scale;
        double lo = 0;

        // Expand upper bracket if needed for heavy-tailed distributions
        while (cdf(hi, params) < p) {
            hi *= 2;
        }

        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (cdf(mid, params) < p) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /**
     * Regularized lower incomplete gamma function P(a, x).
     * Series for x < a+1, continued fraction otherwise.
     */
    private static double regularizedGammaP(double a, double x) {
        if (x <= 0) return 0;

        if (x < a + 1) {
            // Series expansion
            double sum = 1.0 / a;
            double term = 1.0 / a;
            for (int n = 1; n <= CDF_MAX_ITERATIONS; n++) {
                term *= x / (a + n);
                sum += term;
                if (Math.abs(term) < CDF_EPSILON * Math.abs(sum)) break;
            }
            return sum * Math.exp(-x + a * Math.log(x) - lnGamma(a));
        } else {
            // Continued fraction (Legendre)
            double c = 1.0;
            double d = 1.0 / (x - a + 1);
            double f = d;

            for (int n = 1; n <= CDF_MAX_ITERATIONS; n++) {
                double an = -n * (n - a);
                double bn = x - a + 1 + 2 * n;

                d = bn + an * d;
                if (Math.abs(d) < 1e-30) d = 1e-30;
                d = 1.0 / d;

                c = bn + an / c;
                if (Math.abs(c) < 1e-30) c = 1e-30;

                double delta = c * d;
                f *= delta;

                if (Math.abs(delta - 1.0) < CDF_EPSILON) break;
            }

            // P(a,x) = 1 - Q(a,x), where Q uses the continued fraction
            return 1.0 - Math.exp(-x + a * Math.log(x) - lnGamma(a)) * f;
        }
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
    private static double lnGamma(double x) {
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
