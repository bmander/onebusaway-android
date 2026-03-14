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

import org.junit.Test;

import static org.junit.Assert.*;

import java.util.List;

public class PolylineOffsetsTest {

    private static final double TOL = 1e-6;

    // ─── Edge normals ───

    @Test
    public void horizontalEdgeNormalPointsUp() {
        double[] xs = {0, 10}, ys = {0, 0};
        double[] nx = new double[1], ny = new double[1];
        PolylineOffsets.computeEdgeNormals(xs, ys, 2, nx, ny);
        assertEquals(0, nx[0], TOL);
        assertEquals(1, ny[0], TOL);
    }

    @Test
    public void verticalEdgeNormalPointsLeft() {
        double[] xs = {0, 0}, ys = {0, 10};
        double[] nx = new double[1], ny = new double[1];
        PolylineOffsets.computeEdgeNormals(xs, ys, 2, nx, ny);
        assertEquals(-1, nx[0], TOL);
        assertEquals(0, ny[0], TOL);
    }

    @Test
    public void parallelEdgesEqualNormals() {
        double[] xs = {0, 5, 10}, ys = {0, 0, 0};
        double[] nx = new double[2], ny = new double[2];
        PolylineOffsets.computeEdgeNormals(xs, ys, 3, nx, ny);
        assertEquals(nx[0], nx[1], TOL);
        assertEquals(ny[0], ny[1], TOL);
    }

    // ─── Edge data ───

    @Test
    public void edgeDataCorrect() {
        double[] xs = {0, 3}, ys = {0, 4};
        double[] dx = new double[1], dy = new double[1], len = new double[1];
        PolylineOffsets.computeEdgeData(xs, ys, 2, dx, dy, len);
        assertEquals(5, len[0], TOL);
        assertEquals(3.0 / 5, dx[0], TOL);
        assertEquals(4.0 / 5, dy[0], TOL);
    }

    // ─── Miter vectors ───

    @Test
    public void parallelEdgesMiterEqualsNormal() {
        double[] m = PolylineOffsets.miterVec(0, 1, 0, 1);
        assertEquals(0, m[0], TOL);
        assertEquals(1, m[1], TOL);
    }

    @Test
    public void rightAngleMiterDotProperty() {
        double[] m = PolylineOffsets.miterVec(0, 1, -1, 0);
        // dot(miter, nL) = dot(miter, nR) = 1
        assertEquals(1, m[0] * 0 + m[1] * 1, TOL);
        assertEquals(1, m[0] * (-1) + m[1] * 0, TOL);
        // Length should be 1/cos(45°) ≈ 1.414
        double mLen = Math.hypot(m[0], m[1]);
        assertEquals(1 / Math.cos(Math.PI / 4), mLen, 1e-4);
    }

    @Test
    public void arbitraryMiterDotProperty() {
        double len1 = Math.hypot(1, 2);
        double nLx = 1 / len1, nLy = 2 / len1;
        double len2 = Math.hypot(2, -1);
        double nRx = 2 / len2, nRy = -1 / len2;
        double[] m = PolylineOffsets.miterVec(nLx, nLy, nRx, nRy);
        assertEquals(1, m[0] * nLx + m[1] * nLy, TOL);
        assertEquals(1, m[0] * nRx + m[1] * nRy, TOL);
    }

    @Test
    public void near180FoldDegenerateFallback() {
        double[] m = PolylineOffsets.miterVec(0, 1, 0, -1);
        double mLen = Math.hypot(m[0], m[1]);
        assertTrue("Expected large miter vector, got length " + mLen, mLen > 10);
    }

    // ─── Compute miter vectors ───

    @Test
    public void computeMiterVectorsEndpoints() {
        double[] xs = {0, 10, 20}, ys = {0, 0, 0};
        double[] mx = new double[3], my = new double[3];
        PolylineOffsets.computeMiterVectors(xs, ys, 3, mx, my);
        // Straight line: all miters = edge normal = (0, 1)
        for (int i = 0; i < 3; i++) {
            assertEquals(0, mx[i], TOL);
            assertEquals(1, my[i], TOL);
        }
    }

    // ─── Straight line wavefront ───

    @Test
    public void straightLineNoCollisions() {
        double[] xs = {0, 10, 20, 30}, ys = {0, 0, 0, 0};
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 4, 200, +1);
        PolylineOffsets.WavefrontResult neg = PolylineOffsets.runWavefront(xs, ys, 4, 200, -1);
        assertEquals(0, pos.events.size());
        assertEquals(0, neg.events.size());
    }

    @Test
    public void straightLineEvalPathReturnsVertexPlusOffsetTimesNormal() {
        double[] xs = {0, 10, 20}, ys = {0, 0, 0};
        double[] mx = new double[3], my = new double[3];
        PolylineOffsets.computeMiterVectors(xs, ys, 3, mx, my);
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 3, 200, +1);
        double[] out = new double[2];
        for (int i = 0; i < 3; i++) {
            PolylineOffsets.evalPath(pos.paths[i], xs[i], ys[i], 5, out);
            assertEquals(xs[i] + mx[i] * 5, out[0], 1e-4);
            assertEquals(ys[i] + my[i] * 5, out[1], 1e-4);
        }
    }

    // ─── Sharp kink ───

    @Test
    public void sharpKinkOneCollision() {
        double[] xs = {-20, 0, 10, 20, 40}, ys = {0, 0, 10, 0, 0};
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 5, 200, +1);
        PolylineOffsets.WavefrontResult neg = PolylineOffsets.runWavefront(xs, ys, 5, 200, -1);
        int total = pos.events.size() + neg.events.size();
        assertEquals("Expected 1 total collision", 1, total);
    }

    @Test
    public void sharpKinkBothRaysShareSkeletonNode() {
        double[] xs = {-20, 0, 10, 20, 40}, ys = {0, 0, 10, 0, 0};
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 5, 200, +1);
        PolylineOffsets.WavefrontResult neg = PolylineOffsets.runWavefront(xs, ys, 5, 200, -1);
        List<PolylineOffsets.Event> events = pos.events.size() > 0 ? pos.events : neg.events;
        assertEquals(1, events.size());
        PolylineOffsets.Event ev = events.get(0);
        PolylineOffsets.NormalPath[] paths = pos.events.size() > 0 ? pos.paths : neg.paths;
        // Both rays should have the collision point
        boolean foundL = false, foundR = false;
        for (PolylineOffsets.Bend b : paths[ev.iL].bends) {
            if (Math.abs(b.t - ev.t) < TOL) foundL = true;
        }
        for (PolylineOffsets.Bend b : paths[ev.iR].bends) {
            if (Math.abs(b.t - ev.t) < TOL) foundR = true;
        }
        assertTrue("Left ray should have collision point", foundL);
        assertTrue("Right ray should have collision point", foundR);
    }

    // ─── 90° L-shape ───

    @Test
    public void lShapeOneCollision() {
        double[] xs = {-10, 0, 10, 10, 10}, ys = {0, 0, 0, 10, 20};
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 5, 200, +1);
        PolylineOffsets.WavefrontResult neg = PolylineOffsets.runWavefront(xs, ys, 5, 200, -1);
        int total = pos.events.size() + neg.events.size();
        assertEquals(1, total);
    }

    @Test
    public void lShapeCollisionAtExpectedOffset() {
        double[] xs = {-10, 0, 10, 10, 10}, ys = {0, 0, 0, 10, 20};
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 5, 200, +1);
        PolylineOffsets.WavefrontResult neg = PolylineOffsets.runWavefront(xs, ys, 5, 200, -1);
        List<PolylineOffsets.Event> events = pos.events.size() > 0 ? pos.events : neg.events;
        assertTrue("Need at least 1 event", events.size() >= 1);
        assertEquals("Collision t should be ~10", 10, events.get(0).t, 0.5);
    }

    // ─── Offset edge parallelism ───

    @Test
    public void offsetEdgesParallelAtSmallOffset() {
        double[] xs = {0, 10, 15, 20, 30}, ys = {0, 0, 5, 0, 0};
        int n = 5;
        double[] edx = new double[n - 1], edy = new double[n - 1], elen = new double[n - 1];
        PolylineOffsets.computeEdgeData(xs, ys, n, edx, edy, elen);
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, n, 200, +1);

        double t = 1; // small offset, before any collision
        double[] outA = new double[2], outB = new double[2];
        for (int i = 0; i < n - 1; i++) {
            PolylineOffsets.evalPath(pos.paths[i], xs[i], ys[i], t, outA);
            PolylineOffsets.evalPath(pos.paths[i + 1], xs[i + 1], ys[i + 1], t, outB);
            double dx = outB[0] - outA[0], dy = outB[1] - outA[1];
            double len = Math.hypot(dx, dy);
            if (len < 1e-9) continue;
            double offsetDirX = dx / len, offsetDirY = dy / len;
            double dp = Math.abs(offsetDirX * edx[i] + offsetDirY * edy[i]);
            assertEquals("Edge " + i + " should be parallel", 1, dp, 1e-3);
        }
    }

    @Test
    public void perpendicularDistanceEqualsOffset() {
        double[] xs = {0, 10, 20}, ys = {0, 0, 0};
        double[] nx = new double[2], ny = new double[2];
        PolylineOffsets.computeEdgeNormals(xs, ys, 3, nx, ny);
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 3, 200, +1);

        double t = 3;
        double[] out = new double[2];
        for (int i = 0; i < 3; i++) {
            PolylineOffsets.evalPath(pos.paths[i], xs[i], ys[i], t, out);
            double diffX = out[0] - xs[i], diffY = out[1] - ys[i];
            double perpDist = diffX * nx[0] + diffY * ny[0];
            assertEquals(t, perpDist, 1e-4);
        }
    }

    // ─── evalPath ───

    @Test
    public void evalPathAtZeroReturnsVertex() {
        double[] xs = {5, 15}, ys = {10, 10};
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 2, 200, +1);
        double[] out = new double[2];
        PolylineOffsets.evalPath(pos.paths[0], xs[0], ys[0], 0, out);
        assertEquals(5, out[0], TOL);
        assertEquals(10, out[1], TOL);
    }

    @Test
    public void evalPathBeforeCollisionUseMiter() {
        double[] xs = {0, 10, 10}, ys = {0, 0, 10};
        double[] mx = new double[3], my = new double[3];
        PolylineOffsets.computeMiterVectors(xs, ys, 3, mx, my);
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 3, 200, +1);
        double[] out = new double[2];
        // Use endpoint (index 0) which has no collision
        PolylineOffsets.evalPath(pos.paths[0], xs[0], ys[0], 3, out);
        assertEquals(xs[0] + mx[0] * 3, out[0], 1e-4);
        assertEquals(ys[0] + my[0] * 3, out[1], 1e-4);
    }

    @Test
    public void evalPathRespectsMaxTClamping() {
        double[] xs = {0, 10}, ys = {0, 0};
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 2, 5, +1);
        double[] outFar = new double[2], outClamped = new double[2];
        PolylineOffsets.evalPath(pos.paths[0], xs[0], ys[0], 100, outFar);
        PolylineOffsets.evalPath(pos.paths[0], xs[0], ys[0], 5, outClamped);
        assertEquals(outClamped[0], outFar[0], 1e-4);
        assertEquals(outClamped[1], outFar[1], 1e-4);
    }

    // ─── Merge chain continuity ───

    @Test
    public void zigzagMergeChainExtends() {
        double[] xs = {-20, 0, 10, 20, 30, 40, 60};
        double[] ys = {0, 0, 20, 0, 20, 0, 0};
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 7, 200, +1);
        PolylineOffsets.WavefrontResult neg = PolylineOffsets.runWavefront(xs, ys, 7, 200, -1);
        int totalEvents = pos.events.size() + neg.events.size();
        assertTrue("Expected ≥2 collisions in zigzag, got " + totalEvents, totalEvents >= 2);

        // Check monotonicity of all paths
        for (PolylineOffsets.WavefrontResult side : new PolylineOffsets.WavefrontResult[]{pos, neg}) {
            for (int i = 0; i < 7; i++) {
                List<PolylineOffsets.Bend> bends = side.paths[i].bends;
                for (int j = 1; j < bends.size(); j++) {
                    assertTrue("Path[" + i + "] not monotonic: t[" + (j - 1) + "]=" +
                                    bends.get(j - 1).t + " > t[" + j + "]=" + bends.get(j).t,
                            bends.get(j).t >= bends.get(j - 1).t - 1e-9);
                }
            }
        }
    }

    // ─── Edge support clipping ───

    @Test
    public void largeMiterGetsClamped() {
        double[] xs = {0, 10, 10.01, 20}, ys = {0, 0, 0.1, 0};
        double[] mx = new double[4], my = new double[4];
        PolylineOffsets.computeMiterVectors(xs, ys, 4, mx, my);
        double miterLen = Math.hypot(mx[2], my[2]);
        if (miterLen > PolylineOffsets.SURVIVOR_CLIP_MITER) {
            PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 4, 500, +1);
            assertTrue("Expected clipped maxT < 500, got " + pos.paths[2].rayMaxT,
                    pos.paths[2].rayMaxT < 500);
        }
    }

    @Test
    public void normalMiterGetsFullSearchRadius() {
        double[] xs = {0, 10, 20}, ys = {0, 0, 0};
        PolylineOffsets.WavefrontResult pos = PolylineOffsets.runWavefront(xs, ys, 3, 200, +1);
        for (int i = 0; i < 3; i++) {
            assertTrue("Vertex " + i + ": expected maxT ≈ 200, got " + pos.paths[i].rayMaxT,
                    pos.paths[i].rayMaxT >= 200 - TOL);
        }
    }

    // ─── offsetPolyline convenience ───

    @Test
    public void offsetPolylineStraightLine() {
        double[] xs = {0, 10, 20}, ys = {0, 0, 0};
        double[] offsets = {5, 5, 5};
        double[] outX = new double[3], outY = new double[3];
        PolylineOffsets.offsetPolyline(xs, ys, 3, offsets, +1, outX, outY);
        // Straight horizontal line: offset should move up by 5
        for (int i = 0; i < 3; i++) {
            assertEquals(xs[i], outX[i], TOL);
            assertEquals(5, outY[i], TOL);
        }
    }
}
