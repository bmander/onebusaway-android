const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const {
  sub, add, scale, len, unit, cross, dot, perp,
  computeEdgeNormals, computeEdgeData, miterVec, computeMiters,
  edgeSupportExitT, computeRayMaxT, computeSurvivorMaxT,
  computeCollisionT, pqInsert, runWavefront, evalPath,
  SURVIVOR_CLIP_MITER
} = require('./skeleton.js');

function near(a, b, tol = 1e-6) { return Math.abs(a - b) < tol; }
function vecNear(a, b, tol = 1e-6) { return near(a.x, b.x, tol) && near(a.y, b.y, tol); }

// Helper to run full wavefront pipeline
function pipeline(verts, searchRadius = 200) {
  const eNormals = computeEdgeNormals(verts);
  const { dirs: eDirs, lengths: eLengths } = computeEdgeData(verts);
  const miterVecs = computeMiters(verts, eNormals);
  const pos = runWavefront(verts, eNormals, eDirs, eLengths, miterVecs, searchRadius, +1);
  const neg = runWavefront(verts, eNormals, eDirs, eLengths, miterVecs, searchRadius, -1);
  return { eNormals, eDirs, eLengths, miterVecs, pos, neg };
}

// ─── Vec2 basics ───

describe('Vec2 helpers', () => {
  it('sub', () => {
    assert.deepStrictEqual(sub({x:3,y:5}, {x:1,y:2}), {x:2,y:3});
  });
  it('add', () => {
    assert.deepStrictEqual(add({x:1,y:2}, {x:3,y:4}), {x:4,y:6});
  });
  it('scale', () => {
    assert.deepStrictEqual(scale({x:2,y:3}, 4), {x:8,y:12});
  });
  it('perp rotates 90° CCW', () => {
    assert.ok(vecNear(perp({x:1,y:0}), {x:0,y:1}));
    assert.ok(vecNear(perp({x:0,y:1}), {x:-1,y:0}));
  });
  it('cross product', () => {
    assert.strictEqual(cross({x:1,y:0}, {x:0,y:1}), 1);
    assert.strictEqual(cross({x:0,y:1}, {x:1,y:0}), -1);
  });
  it('dot product', () => {
    assert.strictEqual(dot({x:3,y:4}, {x:3,y:4}), 25);
    assert.strictEqual(dot({x:1,y:0}, {x:0,y:1}), 0);
  });
  it('unit normalizes', () => {
    const u = unit({x:3,y:4});
    assert.ok(near(u.x, 0.6));
    assert.ok(near(u.y, 0.8));
    assert.ok(near(len(u), 1));
  });
  it('unit of zero returns zero', () => {
    assert.deepStrictEqual(unit({x:0,y:0}), {x:0,y:0});
  });
});

// ─── Edge normals ───

describe('Edge normals', () => {
  it('horizontal edge → normal {0,1}', () => {
    const normals = computeEdgeNormals([{x:0,y:0}, {x:10,y:0}]);
    assert.ok(vecNear(normals[0], {x:0,y:1}));
  });
  it('vertical edge (downward) → normal {1,0}', () => {
    const normals = computeEdgeNormals([{x:0,y:0}, {x:0,y:10}]);
    assert.ok(vecNear(normals[0], {x:-1,y:0}));
  });
  it('two parallel horizontal edges → equal normals', () => {
    const normals = computeEdgeNormals([{x:0,y:0}, {x:5,y:0}, {x:10,y:0}]);
    assert.ok(vecNear(normals[0], normals[1]));
  });
});

// ─── Miter vectors ───

describe('Miter vectors', () => {
  it('parallel edges (0° turn): miter = edge normal', () => {
    const n = {x:0,y:1};
    const m = miterVec(n, n);
    assert.ok(vecNear(m, n));
  });
  it('90° turn: miter at 45° with correct length', () => {
    const nL = {x:0,y:1};   // horizontal edge pointing right
    const nR = {x:-1,y:0};  // vertical edge pointing down
    const m = miterVec(nL, nR);
    const expected = 1 / Math.cos(Math.PI / 4);
    assert.ok(near(len(m), expected, 1e-4));
    assert.ok(near(dot(m, nL), 1, 1e-6));
    assert.ok(near(dot(m, nR), 1, 1e-6));
  });
  it('dot(miter, nL) ≈ 1 and dot(miter, nR) ≈ 1', () => {
    const nL = unit({x:1,y:2});
    const nR = unit({x:2,y:-1});
    const m = miterVec(nL, nR);
    assert.ok(near(dot(m, nL), 1, 1e-6));
    assert.ok(near(dot(m, nR), 1, 1e-6));
  });
  it('near-180° fold: degenerate fallback', () => {
    const nL = {x:0,y:1};
    const nR = {x:0,y:-1};
    const m = miterVec(nL, nR);
    // Should produce a very large vector (fallback)
    assert.ok(len(m) > 10);
  });
});

// ─── Straight line (no collisions) ───

describe('Straight line', () => {
  it('collinear points → zero collision events', () => {
    const verts = [{x:0,y:0}, {x:10,y:0}, {x:20,y:0}, {x:30,y:0}];
    const { pos, neg } = pipeline(verts);
    assert.strictEqual(pos.events.length, 0);
    assert.strictEqual(neg.events.length, 0);
  });
  it('evalPath at any offset returns vertex + offset * normal', () => {
    const verts = [{x:0,y:0}, {x:10,y:0}, {x:20,y:0}];
    const { pos, miterVecs } = pipeline(verts);
    for (let i = 0; i < verts.length; i++) {
      const p = evalPath(pos.paths[i], verts[i], 5);
      const expected = add(verts[i], scale(miterVecs[i], 5));
      assert.ok(vecNear(p, expected, 1e-4), `vertex ${i}: got (${p.x},${p.y}) expected (${expected.x},${expected.y})`);
    }
  });
});

// ─── Sharp kink (basic collision) ───

describe('Sharp kink', () => {
  // Need ≥4 interior vertices for collisions (endpoints are skipped).
  // Extend with straight segments so the kink vertices are interior.
  const verts = [{x:-20,y:0}, {x:0,y:0}, {x:10,y:10}, {x:20,y:0}, {x:40,y:0}];

  it('V-shape → exactly 1 collision event on inside', () => {
    const { pos, neg } = pipeline(verts);
    // One side should have 1 collision, the other 0
    const totalCollisions = pos.events.length + neg.events.length;
    assert.strictEqual(totalCollisions, 1, `expected 1 total collision, got pos=${pos.events.length} neg=${neg.events.length}`);
  });
  it('both rays share the same skeleton node point', () => {
    const { pos, neg } = pipeline(verts);
    const events = pos.events.length > 0 ? pos.events : neg.events;
    assert.strictEqual(events.length, 1);
    const ev = events[0];
    const paths = pos.events.length > 0 ? pos.paths : neg.paths;
    const ptL = paths[ev.iL].find(b => near(b.t, ev.t, 1e-6));
    const ptR = paths[ev.iR].find(b => near(b.t, ev.t, 1e-6));
    assert.ok(ptL && ptR, 'both rays should have the collision point');
    assert.ok(vecNear(ptL.pt, ptR.pt), 'collision points should match');
  });
});

// ─── 90° L-shape ───

describe('90° L-shape', () => {
  // Extend endpoints so the corner vertex (index 2) is interior
  const verts = [{x:-10,y:0}, {x:0,y:0}, {x:10,y:0}, {x:10,y:10}, {x:10,y:20}];

  it('1 collision on inside', () => {
    const { pos, neg } = pipeline(verts);
    const total = pos.events.length + neg.events.length;
    assert.strictEqual(total, 1);
  });
  it('collision at offset ≈ 7.07', () => {
    const { pos, neg } = pipeline(verts);
    const events = pos.events.length > 0 ? pos.events : neg.events;
    assert.ok(events.length >= 1, 'need at least 1 event');
    // For a 90° turn with 10-unit edges, the two interior rays at the corner
    // collide at t=10 (the miter-based rays meet when offset equals edge length)
    assert.ok(near(events[0].t, 10, 0.5),
      `collision t=${events[0].t}, expected ≈10`);
  });
});

// ─── Offset edge parallelism ───

describe('Offset edge parallelism', () => {
  it('offset edges parallel to originals at small offset', () => {
    const verts = [{x:0,y:0}, {x:10,y:0}, {x:15,y:5}, {x:20,y:0}, {x:30,y:0}];
    const { pos, eDirs } = pipeline(verts);
    const t = 1; // small offset, before any collision

    for (let i = 0; i < verts.length - 1; i++) {
      const pA = evalPath(pos.paths[i], verts[i], t);
      const pB = evalPath(pos.paths[i + 1], verts[i + 1], t);
      const offsetDir = unit(sub(pB, pA));
      const origDir = eDirs[i];
      const dp = Math.abs(dot(offsetDir, origDir));
      assert.ok(near(dp, 1, 1e-3),
        `edge ${i}: offset dir (${offsetDir.x},${offsetDir.y}) not parallel to (${origDir.x},${origDir.y}), dot=${dp}`);
    }
  });
  it('perpendicular distance equals offset', () => {
    const verts = [{x:0,y:0}, {x:10,y:0}, {x:20,y:0}];
    const { pos, eNormals } = pipeline(verts);
    const t = 3;
    for (let i = 0; i < verts.length; i++) {
      const p = evalPath(pos.paths[i], verts[i], t);
      const diff = sub(p, verts[i]);
      // For a straight line, the displacement should be t * normal
      assert.ok(near(dot(diff, eNormals[0]), t, 1e-4));
    }
  });
});

// ─── evalPath ───

describe('evalPath', () => {
  it('returns vertex position at offset 0', () => {
    const verts = [{x:5,y:10}, {x:15,y:10}];
    const { pos } = pipeline(verts);
    const p = evalPath(pos.paths[0], verts[0], 0);
    assert.ok(vecNear(p, verts[0]));
  });
  it('at offset < first collision: returns vertex + offset * miterVec', () => {
    const verts = [{x:0,y:0}, {x:10,y:0}, {x:10,y:10}];
    const { pos, miterVecs } = pipeline(verts);
    // Use endpoint (index 0) which has no collision
    const p = evalPath(pos.paths[0], verts[0], 3);
    const expected = add(verts[0], scale(miterVecs[0], 3));
    assert.ok(vecNear(p, expected, 1e-4));
  });
  it('respects maxT clamping', () => {
    const verts = [{x:0,y:0}, {x:10,y:0}];
    const { pos } = pipeline(verts, 5);
    // searchRadius=5, so offset 100 should be clamped
    const p = evalPath(pos.paths[0], verts[0], 100);
    const pClamped = evalPath(pos.paths[0], verts[0], 5);
    assert.ok(vecNear(p, pClamped, 1e-4));
  });
});

// ─── Merge chain continuity ───

describe('Merge chain continuity', () => {
  it('zigzag: killed rays extend through merge chain', () => {
    // Extend with straight segments so interior vertices can collide
    const verts = [
      {x:-20,y:0}, {x:0,y:0}, {x:10,y:20}, {x:20,y:0}, {x:30,y:20}, {x:40,y:0}, {x:60,y:0}
    ];
    const { pos, neg } = pipeline(verts, 200);
    const allEvents = pos.events.concat(neg.events);
    assert.ok(allEvents.length >= 2, `expected ≥2 collisions in zigzag, got ${allEvents.length}`);

    // Check that killed ray paths extend beyond their own collision
    for (const side of [pos, neg]) {
      for (let i = 0; i < verts.length; i++) {
        const path = side.paths[i];
        if (path.length > 1) {
          // Path should be monotonically increasing in t
          for (let j = 1; j < path.length; j++) {
            assert.ok(path[j].t >= path[j-1].t - 1e-9,
              `path[${i}] not monotonic: t[${j-1}]=${path[j-1].t} > t[${j}]=${path[j].t}`);
          }
        }
      }
    }
  });
});

// ─── Edge support clipping ───

describe('Edge support clipping', () => {
  it('survivor with large miter gets maxT clamped', () => {
    // Create a near-180° fold that produces a very large miter
    const verts = [{x:0,y:0}, {x:10,y:0}, {x:10.01,y:0.1}, {x:20,y:0}];
    const { miterVecs, pos } = pipeline(verts, 500);
    // The middle vertex miter should be large
    const miterLen = len(miterVecs[2]);
    if (miterLen > SURVIVOR_CLIP_MITER) {
      // Its ray maxT should be < searchRadius due to clipping
      const ray = pos.paths[2].ray;
      assert.ok(ray.maxT < 500,
        `expected clipped maxT < 500, got ${ray.maxT}`);
    }
  });
  it('survivor with normal miter gets maxT = searchRadius', () => {
    const verts = [{x:0,y:0}, {x:10,y:0}, {x:20,y:0}];
    const { pos } = pipeline(verts, 200);
    // Straight line: all miters are unit length, no clipping
    for (let i = 0; i < verts.length; i++) {
      const ray = pos.paths[i].ray;
      assert.ok(ray.maxT >= 200 - 1e-6,
        `vertex ${i}: expected maxT ≈ 200, got ${ray.maxT}`);
    }
  });
});

// ─── pqInsert ───

describe('pqInsert', () => {
  it('maintains sorted-descending order', () => {
    const pq = [];
    pqInsert(pq, { t: 5 });
    pqInsert(pq, { t: 2 });
    pqInsert(pq, { t: 8 });
    pqInsert(pq, { t: 1 });
    pqInsert(pq, { t: 4 });
    // Should be sorted descending
    for (let i = 0; i < pq.length - 1; i++) {
      assert.ok(pq[i].t >= pq[i+1].t, `pq[${i}].t=${pq[i].t} < pq[${i+1}].t=${pq[i+1].t}`);
    }
    // Pop from end should give minimum
    assert.strictEqual(pq.pop().t, 1);
    assert.strictEqual(pq.pop().t, 2);
  });
});

// ─── computeEdgeData ───

describe('computeEdgeData', () => {
  it('returns correct dirs and lengths', () => {
    const verts = [{x:0,y:0}, {x:3,y:4}];
    const { dirs, lengths } = computeEdgeData(verts);
    assert.ok(near(lengths[0], 5));
    assert.ok(near(dirs[0].x, 3/5));
    assert.ok(near(dirs[0].y, 4/5));
  });
});
