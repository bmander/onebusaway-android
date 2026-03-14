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

import java.util.ArrayList;
import java.util.List;

/**
 * Straight-skeleton wavefront algorithm for polyline offset computation.
 * Computes miter vectors and runs a wavefront cascade to prevent offset
 * edges from crossing at sharp turns.
 * <p>
 * Ported from demo/skeleton.js. Pure Java — no Android dependencies.
 */
public final class PolylineOffsets {

    static final double SURVIVOR_CLIP_MITER = 8.0;
    private static final double EPS_ZERO = 1e-12;
    private static final double EPS_TOL = 1e-9;

    private PolylineOffsets() {}

    // ═══════════════════════════════════════════════════════════════════
    //  Vec2 inline helpers (private, no allocation)
    // ═══════════════════════════════════════════════════════════════════

    private static double vLen(double x, double y) {
        return Math.hypot(x, y);
    }

    private static double vCross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }

    private static double vDot(double ax, double ay, double bx, double by) {
        return ax * bx + ay * by;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Edge normals & miter vectors
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Compute unit edge normals for a polyline with n vertices.
     * Output arrays must have length &ge; n-1 (one normal per edge).
     * The normal is the left-hand perpendicular of the edge direction.
     */
    public static void computeEdgeNormals(double[] xs, double[] ys, int n,
                                           double[] outNx, double[] outNy) {
        for (int i = 0; i < n - 1; i++) {
            double dx = xs[i + 1] - xs[i];
            double dy = ys[i + 1] - ys[i];
            // perp: (-dy, dx), then unit
            double px = -dy, py = dx;
            double len = vLen(px, py);
            if (len < EPS_ZERO) {
                outNx[i] = 0;
                outNy[i] = 0;
            } else {
                outNx[i] = px / len;
                outNy[i] = py / len;
            }
        }
    }

    /**
     * Compute unit edge directions and lengths for a polyline with n vertices.
     * Output arrays must have length &ge; n-1.
     */
    public static void computeEdgeData(double[] xs, double[] ys, int n,
                                        double[] outDirX, double[] outDirY,
                                        double[] outLengths) {
        for (int i = 0; i < n - 1; i++) {
            double dx = xs[i + 1] - xs[i];
            double dy = ys[i + 1] - ys[i];
            double len = vLen(dx, dy);
            outLengths[i] = len;
            if (len < EPS_ZERO) {
                outDirX[i] = 0;
                outDirY[i] = 0;
            } else {
                outDirX[i] = dx / len;
                outDirY[i] = dy / len;
            }
        }
    }

    /**
     * Miter vector from two adjacent unit edge normals.
     * Returns {mx, my} satisfying dot(m, nL) = dot(m, nR) = 1.
     */
    static double[] miterVec(double nLx, double nLy, double nRx, double nRy) {
        double d = 1.0 + vDot(nLx, nLy, nRx, nRy);
        if (d < EPS_TOL) {
            // Near-180° fold: degenerate fallback
            return new double[]{-nLy * 100, nLx * 100};
        }
        double inv = 1.0 / d;
        return new double[]{(nLx + nRx) * inv, (nLy + nRy) * inv};
    }

    /**
     * Compute miter vectors for a polyline with n vertices.
     * Output arrays must have length &ge; n.
     * At endpoints, the miter equals the adjacent edge normal.
     * At interior vertices, the miter bisects adjacent normals such that
     * dot(miter, normalLeft) = dot(miter, normalRight) = 1.
     */
    public static void computeMiterVectors(double[] xs, double[] ys, int n,
                                            double[] outMx, double[] outMy) {
        if (n < 2) return;
        // Compute edge normals into temp arrays
        double[] enx = new double[n - 1];
        double[] eny = new double[n - 1];
        computeEdgeNormals(xs, ys, n, enx, eny);

        // Endpoints: use adjacent edge normal
        outMx[0] = enx[0];
        outMy[0] = eny[0];
        outMx[n - 1] = enx[n - 2];
        outMy[n - 1] = eny[n - 2];

        // Interior: miter from left and right normals
        for (int i = 1; i < n - 1; i++) {
            double[] m = miterVec(enx[i - 1], eny[i - 1], enx[i], eny[i]);
            outMx[i] = m[0];
            outMy[i] = m[1];
        }
    }

    /**
     * Offset polyline vertices using miter vectors and per-vertex offsets.
     * For each vertex i:
     * <pre>
     *   outX[i] = xs[i] + offsets[i] * sign * miterX[i]
     *   outY[i] = ys[i] + offsets[i] * sign * miterY[i]
     * </pre>
     * where miterVec ensures offset edges stay parallel to originals.
     *
     * @param xs      vertex x coordinates
     * @param ys      vertex y coordinates
     * @param n       number of vertices
     * @param offsets per-vertex offset magnitudes
     * @param sign    +1 or -1 for each side
     * @param outX    output x coordinates (length &ge; n)
     * @param outY    output y coordinates (length &ge; n)
     */
    public static void offsetPolyline(double[] xs, double[] ys, int n,
                                       double[] offsets, int sign,
                                       double[] outX, double[] outY) {
        double[] mx = new double[n];
        double[] my = new double[n];
        computeMiterVectors(xs, ys, n, mx, my);
        for (int i = 0; i < n; i++) {
            outX[i] = xs[i] + offsets[i] * sign * mx[i];
            outY[i] = ys[i] + offsets[i] * sign * my[i];
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Wavefront (straight skeleton for uniform offset)
    // ═══════════════════════════════════════════════════════════════════

    /** A bend point along a normal path. */
    public static final class Bend {
        public final double t, x, y;

        Bend(double t, double x, double y) {
            this.t = t;
            this.x = x;
            this.y = y;
        }
    }

    /** A collision event in the wavefront. */
    public static final class Event {
        public final double t, x, y;
        public final int iL, iR;

        Event(double t, double x, double y, int iL, int iR) {
            this.t = t;
            this.x = x;
            this.y = y;
            this.iL = iL;
            this.iR = iR;
        }
    }

    /** Per-vertex normal path with surviving ray for extrapolation. */
    public static final class NormalPath {
        public final List<Bend> bends = new ArrayList<>();
        public double rayDirX, rayDirY;
        public double rayMaxT;
        public boolean rayAlive;
    }

    /** Result of a wavefront computation. */
    public static final class WavefrontResult {
        public final NormalPath[] paths;
        public final List<Event> events;

        WavefrontResult(NormalPath[] paths, List<Event> events) {
            this.paths = paths;
            this.events = events;
        }
    }

    // Internal ray state for wavefront
    private static final class Ray {
        double originX, originY;
        double dirX, dirY;
        double leftENx, leftENy;
        double rightENx, rightENy;
        int leftEdge, rightEdge;
        double collapseT;
        int left, right;
        boolean alive;
        int version;
        double maxT;
        int mergedInto = -1;
    }

    // PQ event
    private static final class PQEvent {
        double t;
        int iL, iR, vL, vR;
    }

    private static double edgeSupportExitT(double originX, double originY,
                                            double collapseT,
                                            double dirX, double dirY,
                                            int edgeIdx,
                                            double[] xs, double[] ys,
                                            double[] eDirX, double[] eDirY,
                                            double[] eLengths) {
        double edx = eDirX[edgeIdx];
        double edy = eDirY[edgeIdx];
        double edgeLen = eLengths[edgeIdx];
        double ox = originX - xs[edgeIdx];
        double oy = originY - ys[edgeIdx];
        double along = Math.max(0, Math.min(edgeLen, vDot(ox, oy, edx, edy)));
        double speed = vDot(dirX, dirY, edx, edy);
        if (speed > EPS_TOL) return collapseT + (edgeLen - along) / speed;
        if (speed < -EPS_TOL) return collapseT + -along / speed;
        return Double.POSITIVE_INFINITY;
    }

    private static double computeRayMaxT(Ray ray, double[] xs, double[] ys,
                                          double[] eDirX, double[] eDirY,
                                          double[] eLengths, double searchRadius) {
        return Math.min(searchRadius, Math.min(
                edgeSupportExitT(ray.originX, ray.originY, ray.collapseT,
                        ray.dirX, ray.dirY, ray.leftEdge,
                        xs, ys, eDirX, eDirY, eLengths),
                edgeSupportExitT(ray.originX, ray.originY, ray.collapseT,
                        ray.dirX, ray.dirY, ray.rightEdge,
                        xs, ys, eDirX, eDirY, eLengths)));
    }

    private static double computeSurvivorMaxT(Ray ray, double[] xs, double[] ys,
                                               double[] eDirX, double[] eDirY,
                                               double[] eLengths, double searchRadius) {
        return vLen(ray.dirX, ray.dirY) > SURVIVOR_CLIP_MITER
                ? computeRayMaxT(ray, xs, ys, eDirX, eDirY, eLengths, searchRadius)
                : searchRadius;
    }

    static double computeCollisionT(double aOriginX, double aOriginY,
                                     double aDirX, double aDirY, double aCollapseT,
                                     double bOriginX, double bOriginY,
                                     double bDirX, double bDirY, double bCollapseT) {
        double denom = vCross(aDirX, aDirY, bDirX, bDirY);
        if (Math.abs(denom) < EPS_ZERO) return Double.POSITIVE_INFINITY;
        double pAx = aOriginX - aDirX * aCollapseT;
        double pAy = aOriginY - aDirY * aCollapseT;
        double pBx = bOriginX - bDirX * bCollapseT;
        double pBy = bOriginY - bDirY * bCollapseT;
        return vCross(pBx - pAx, pBy - pAy, bDirX, bDirY) / denom;
    }

    // Binary insertion into sorted-descending list (pop from end = min)
    static void pqInsert(List<PQEvent> pq, PQEvent ev) {
        int lo = 0, hi = pq.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (pq.get(mid).t > ev.t) lo = mid + 1;
            else hi = mid;
        }
        pq.add(lo, ev);
    }

    /**
     * Run the straight-skeleton wavefront on a polyline with n vertices.
     *
     * @param xs           vertex x coordinates
     * @param ys           vertex y coordinates
     * @param n            number of vertices (&ge; 2)
     * @param searchRadius maximum offset distance
     * @param sign         +1 or -1 for each side
     * @return wavefront result with per-vertex paths and collision events
     */
    public static WavefrontResult runWavefront(double[] xs, double[] ys, int n,
                                                double searchRadius, int sign) {
        List<Event> events = new ArrayList<>();
        if (n < 2) return new WavefrontResult(new NormalPath[0], events);

        // Compute edge data
        double[] enx = new double[n - 1], eny = new double[n - 1];
        double[] edx = new double[n - 1], edy = new double[n - 1];
        double[] elen = new double[n - 1];
        computeEdgeNormals(xs, ys, n, enx, eny);
        computeEdgeData(xs, ys, n, edx, edy, elen);

        // Compute miters
        double[] mx = new double[n], my = new double[n];
        mx[0] = enx[0]; my[0] = eny[0];
        mx[n - 1] = enx[n - 2]; my[n - 1] = eny[n - 2];
        for (int i = 1; i < n - 1; i++) {
            double[] m = miterVec(enx[i - 1], eny[i - 1], enx[i], eny[i]);
            mx[i] = m[0]; my[i] = m[1];
        }

        // Initialize rays
        Ray[] rays = new Ray[n];
        for (int i = 0; i < n; i++) {
            Ray r = new Ray();
            r.originX = xs[i]; r.originY = ys[i];
            r.dirX = mx[i] * sign; r.dirY = my[i] * sign;
            r.leftENx = enx[i > 0 ? i - 1 : 0];
            r.leftENy = eny[i > 0 ? i - 1 : 0];
            r.rightENx = enx[i < n - 1 ? i : n - 2];
            r.rightENy = eny[i < n - 1 ? i : n - 2];
            r.leftEdge = i > 0 ? i - 1 : 0;
            r.rightEdge = i < n - 1 ? i : n - 2;
            r.left = i - 1; r.right = i + 1;
            r.alive = true;
            r.maxT = searchRadius;
            rays[i] = r;
        }

        NormalPath[] paths = new NormalPath[n];
        for (int i = 0; i < n; i++) paths[i] = new NormalPath();

        List<PQEvent> pq = new ArrayList<>();

        // Seed events for adjacent pairs
        for (int i = 0; i < n - 1; i++) {
            enqueueIfValid(pq, rays, i, i + 1, n, searchRadius);
        }

        // Process events
        while (!pq.isEmpty()) {
            PQEvent ev = pq.remove(pq.size() - 1); // pop min
            Ray rL = rays[ev.iL], rR = rays[ev.iR];
            if (!rL.alive || !rR.alive) continue;
            if (rL.right != ev.iR) continue;
            if (rL.version != ev.vL || rR.version != ev.vR) continue;
            if (ev.t > rL.maxT + EPS_TOL || ev.t > rR.maxT + EPS_TOL) continue;

            double ptx = rL.originX + rL.dirX * (ev.t - rL.collapseT);
            double pty = rL.originY + rL.dirY * (ev.t - rL.collapseT);

            paths[ev.iL].bends.add(new Bend(ev.t, ptx, pty));
            paths[ev.iR].bends.add(new Bend(ev.t, ptx, pty));
            events.add(new Event(ev.t, ptx, pty, ev.iL, ev.iR));

            rR.alive = false;
            rR.mergedInto = ev.iL;
            rL.originX = ptx; rL.originY = pty;
            rL.collapseT = ev.t;
            rL.rightENx = rR.rightENx; rL.rightENy = rR.rightENy;
            rL.rightEdge = rR.rightEdge;

            int newRight = rR.right;
            rL.right = newRight;
            if (newRight < n) rays[newRight].left = ev.iL;

            double nLx = rL.leftENx, nLy = rL.leftENy;
            double nRx = rL.rightENx, nRy = rL.rightENy;
            if (rL.left < 0) { nLx = nRx; nLy = nRy; }
            if (newRight >= n) { nRx = nLx; nRy = nLy; }
            rL.leftENx = nLx; rL.leftENy = nLy;
            rL.rightENx = nRx; rL.rightENy = nRy;
            double[] newMiter = miterVec(nLx, nLy, nRx, nRy);
            rL.dirX = newMiter[0] * sign; rL.dirY = newMiter[1] * sign;
            rL.version++;
            rL.maxT = computeSurvivorMaxT(rL, xs, ys, edx, edy, elen, searchRadius);

            if (newRight < n) {
                double tNew = computeCollisionT(
                        rL.originX, rL.originY, rL.dirX, rL.dirY, rL.collapseT,
                        rays[newRight].originX, rays[newRight].originY,
                        rays[newRight].dirX, rays[newRight].dirY, rays[newRight].collapseT);
                if (tNew > ev.t) enqueueIfValid(pq, rays, ev.iL, newRight, n, searchRadius);
            }
            int newLeft = rL.left;
            if (newLeft >= 0) {
                double tNew = computeCollisionT(
                        rays[newLeft].originX, rays[newLeft].originY,
                        rays[newLeft].dirX, rays[newLeft].dirY, rays[newLeft].collapseT,
                        rL.originX, rL.originY, rL.dirX, rL.dirY, rL.collapseT);
                if (tNew > ev.t) enqueueIfValid(pq, rays, newLeft, ev.iL, n, searchRadius);
            }
        }

        // Post-process: extend killed rays' paths along merge chains
        for (int i = 0; i < n; i++) {
            int target = i;
            while (rays[target].mergedInto >= 0) {
                target = rays[target].mergedInto;
                List<Bend> targetBends = paths[target].bends;
                List<Bend> myBends = paths[i].bends;
                double lastT = myBends.isEmpty() ? 0 : myBends.get(myBends.size() - 1).t;
                for (Bend bend : targetBends) {
                    if (bend.t > lastT + EPS_ZERO) {
                        myBends.add(bend);
                    }
                }
            }
            paths[i].rayDirX = rays[target].dirX;
            paths[i].rayDirY = rays[target].dirY;
            paths[i].rayMaxT = rays[target].maxT;
            paths[i].rayAlive = rays[target].alive;
        }

        return new WavefrontResult(paths, events);
    }

    private static void enqueueIfValid(List<PQEvent> pq, Ray[] rays,
                                        int iL, int iR, int n,
                                        double searchRadius) {
        Ray rL = rays[iL], rR = rays[iR];
        if (rL.left < 0 || rL.right >= n) return; // endpoint
        if (rR.left < 0 || rR.right >= n) return;
        double t = computeCollisionT(
                rL.originX, rL.originY, rL.dirX, rL.dirY, rL.collapseT,
                rR.originX, rR.originY, rR.dirX, rR.dirY, rR.collapseT);
        if (t > 0 && t < searchRadius && Double.isFinite(t)
                && t <= rL.maxT + EPS_TOL && t <= rR.maxT + EPS_TOL) {
            PQEvent ev = new PQEvent();
            ev.t = t; ev.iL = iL; ev.iR = iR;
            ev.vL = rL.version; ev.vR = rR.version;
            pqInsert(pq, ev);
        }
    }

    /**
     * Evaluate the position along a normal path at a given offset.
     *
     * @param path   normal path from a wavefront result
     * @param vx     vertex x position
     * @param vy     vertex y position
     * @param offset offset distance
     * @param out    output array [x, y] (must have length &ge; 2)
     */
    public static void evalPath(NormalPath path, double vx, double vy,
                                 double offset, double[] out) {
        if (offset <= 0) {
            out[0] = vx; out[1] = vy;
            return;
        }
        double targetT = Math.min(offset, path.rayMaxT);
        double posX = vx, posY = vy;
        double currentT = 0;

        for (Bend bend : path.bends) {
            if (bend.t >= targetT) {
                double dt = bend.t - currentT;
                double frac = (dt > EPS_ZERO) ? (targetT - currentT) / dt : 0;
                out[0] = posX + frac * (bend.x - posX);
                out[1] = posY + frac * (bend.y - posY);
                return;
            }
            posX = bend.x; posY = bend.y;
            currentT = bend.t;
        }

        if (targetT > currentT + EPS_TOL) {
            out[0] = posX + path.rayDirX * (targetT - currentT);
            out[1] = posY + path.rayDirY * (targetT - currentT);
        } else {
            out[0] = posX; out[1] = posY;
        }
    }
}
