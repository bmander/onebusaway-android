// skeleton.js — Pure geometry functions for straight-skeleton wavefront algorithm.
// Extracted from normal-paths.html for testability and reuse.
//
// Loaded as a plain <script src> in the browser (functions become globals).
// Loaded via require() in Node.js tests (CJS exports at bottom).

var SURVIVOR_CLIP_MITER = 8;

// --- Vec2 helpers ---
function sub(a, b) { return {x: a.x - b.x, y: a.y - b.y}; }
function add(a, b) { return {x: a.x + b.x, y: a.y + b.y}; }
function scale(v, s) { return {x: v.x * s, y: v.y * s}; }
function len(v) { return Math.hypot(v.x, v.y); }
function unit(v) { var l = len(v); return l < 1e-12 ? {x:0,y:0} : {x: v.x/l, y: v.y/l}; }
function cross(a, b) { return a.x * b.y - a.y * b.x; }
function dot(a, b) { return a.x * b.x + a.y * b.y; }
function perp(v) { return {x: -v.y, y: v.x}; }

// --- Edge normals & miter vectors ---

function computeEdgeNormals(verts) {
  var out = [];
  for (var i = 0; i < verts.length - 1; i++) {
    out.push(unit(perp(sub(verts[i + 1], verts[i]))));
  }
  return out;
}

function computeEdgeData(verts) {
  var dirs = [];
  var lengths = [];
  for (var i = 0; i < verts.length - 1; i++) {
    var delta = sub(verts[i + 1], verts[i]);
    var length = len(delta);
    lengths.push(length);
    dirs.push(length < 1e-12 ? { x: 0, y: 0 } : scale(delta, 1 / length));
  }
  return { dirs: dirs, lengths: lengths };
}

// Miter vector from two adjacent edge normals.
// Satisfies dot(m, nL) = dot(m, nR) = 1, so offsetting a vertex
// by t*m pushes both adjacent edges out by exactly t.
function miterVec(nL, nR) {
  var d = 1 + dot(nL, nR);
  if (d < 1e-9) return scale(perp(nL), 100);
  return scale(add(nL, nR), 1 / d);
}

function edgeSupportExitT(origin, collapseT, dir, edgeIdx, verts, eDirs, eLengths) {
  var edgeDir = eDirs[edgeIdx];
  var edgeLen = eLengths[edgeIdx];
  var along = Math.max(0, Math.min(edgeLen, dot(sub(origin, verts[edgeIdx]), edgeDir)));
  var speed = dot(dir, edgeDir);
  if (speed > 1e-9) return collapseT + (edgeLen - along) / speed;
  if (speed < -1e-9) return collapseT + (0 - along) / speed;
  return Infinity;
}

function computeRayMaxT(ray, verts, eDirs, eLengths, searchRadius) {
  return Math.min(
    searchRadius,
    edgeSupportExitT(ray.origin, ray.collapseT, ray.dir, ray.leftEdge, verts, eDirs, eLengths),
    edgeSupportExitT(ray.origin, ray.collapseT, ray.dir, ray.rightEdge, verts, eDirs, eLengths)
  );
}

function computeSurvivorMaxT(ray, verts, eDirs, eLengths, searchRadius) {
  return len(ray.dir) > SURVIVOR_CLIP_MITER
    ? computeRayMaxT(ray, verts, eDirs, eLengths, searchRadius)
    : searchRadius;
}

function computeMiters(verts, eNormals) {
  var N = verts.length;
  var out = [];
  for (var i = 0; i < N; i++) {
    if (i === 0)          out.push(eNormals[0]);
    else if (i === N - 1) out.push(eNormals[N - 2]);
    else                  out.push(miterVec(eNormals[i - 1], eNormals[i]));
  }
  return out;
}

// --- Wavefront cascade (straight skeleton) ---

function computeCollisionT(rayA, rayB) {
  var denom = cross(rayA.dir, rayB.dir);
  if (Math.abs(denom) < 1e-12) return Infinity;
  var pA = sub(rayA.origin, scale(rayA.dir, rayA.collapseT));
  var pB = sub(rayB.origin, scale(rayB.dir, rayB.collapseT));
  return cross(sub(pB, pA), rayB.dir) / denom;
}

// Binary insertion into a sorted-descending array (pop from end = extract min)
function pqInsert(pq, ev) {
  var lo = 0, hi = pq.length;
  while (lo < hi) {
    var mid = (lo + hi) >> 1;
    if (pq[mid].t > ev.t) lo = mid + 1; else hi = mid;
  }
  pq.splice(lo, 0, ev);
}

function runWavefront(verts, eNormals, eDirs, eLengths, miterVecs, searchRadius, sign) {
  var N = verts.length;
  if (N < 2) return { paths: [], events: [] };

  var rays = verts.map(function(v, i) {
    return {
      origin: v,
      dir: scale(miterVecs[i], sign),
      leftEN:  eNormals[i > 0 ? i - 1 : 0],
      rightEN: eNormals[i < N - 1 ? i : N - 2],
      leftEdge: i > 0 ? i - 1 : 0,
      rightEdge: i < N - 1 ? i : N - 2,
      collapseT: 0,
      left: i - 1,
      right: i + 1,
      alive: true,
      version: 0,
      maxT: searchRadius
    };
  });

  var paths = Array.from({length: N}, function() { return []; });
  var events = [];
  var pq = []; // sorted descending by t; pop from end = min

  function isEndpointRay(i) {
    return rays[i].left < 0 || rays[i].right >= N;
  }

  function enq(t, iL, iR) {
    if (isEndpointRay(iL) || isEndpointRay(iR)) return;
    if (t > 0 && t < searchRadius && isFinite(t) &&
        t <= rays[iL].maxT + 1e-9 && t <= rays[iR].maxT + 1e-9) {
      pqInsert(pq, { t: t, iL: iL, iR: iR, vL: rays[iL].version, vR: rays[iR].version });
    }
  }

  for (var i = 0; i < N - 1; i++) {
    enq(computeCollisionT(rays[i], rays[i + 1]), i, i + 1);
  }

  while (pq.length > 0) {
    var ev = pq.pop();
    var t = ev.t, iL = ev.iL, iR = ev.iR, vL = ev.vL, vR = ev.vR;
    if (!rays[iL].alive || !rays[iR].alive) continue;
    if (rays[iL].right !== iR) continue;
    if (rays[iL].version !== vL || rays[iR].version !== vR) continue;
    if (t > rays[iL].maxT + 1e-9 || t > rays[iR].maxT + 1e-9) continue;

    var pt = add(rays[iL].origin, scale(rays[iL].dir, t - rays[iL].collapseT));
    paths[iL].push({ t: t, pt: pt });
    paths[iR].push({ t: t, pt: pt });
    events.push({ t: t, pt: pt, iL: iL, iR: iR });

    rays[iR].alive = false;
    rays[iR].mergedInto = iL;
    rays[iL].origin = pt;
    rays[iL].collapseT = t;
    rays[iL].rightEN = rays[iR].rightEN;
    rays[iL].rightEdge = rays[iR].rightEdge;

    var newRight = rays[iR].right;
    rays[iL].right = newRight;
    if (newRight < N) rays[newRight].left = iL;

    var nL = rays[iL].leftEN;
    var nR = rays[iL].rightEN;
    if (rays[iL].left < 0) nL = nR;
    if (newRight >= N)      nR = nL;
    rays[iL].leftEN = nL;
    rays[iL].rightEN = nR;
    rays[iL].dir = scale(miterVec(nL, nR), sign);
    rays[iL].version++;
    rays[iL].maxT = computeSurvivorMaxT(rays[iL], verts, eDirs, eLengths, searchRadius);

    if (newRight < N) {
      var tNew = computeCollisionT(rays[iL], rays[newRight]);
      if (tNew > t) enq(tNew, iL, newRight);
    }
    var newLeft = rays[iL].left;
    if (newLeft >= 0) {
      var tNew2 = computeCollisionT(rays[newLeft], rays[iL]);
      if (tNew2 > t) enq(tNew2, newLeft, iL);
    }
  }

  // Post-process: extend each killed ray's path along its merge chain
  for (var i = 0; i < N; i++) {
    var target = i;
    while (rays[target].mergedInto !== undefined) {
      target = rays[target].mergedInto;
      var lastT = paths[i].length > 0 ? paths[i][paths[i].length - 1].t : 0;
      for (var j = 0; j < paths[target].length; j++) {
        var bend = paths[target][j];
        if (bend.t > lastT + 1e-12) {
          paths[i].push(bend);
        }
      }
    }
    paths[i].ray = rays[target];
    paths[i].maxSafeT = searchRadius;
  }
  return { paths: paths, events: events };
}

// Evaluate position along a normal path at offset t.
function evalPath(pathData, vertexPos, offset) {
  if (offset <= 0) return vertexPos;

  var targetT = pathData.ray ? Math.min(offset, pathData.ray.maxT) : offset;
  var pos = vertexPos;
  var currentT = 0;

  for (var i = 0; i < pathData.length; i++) {
    var bend = pathData[i];
    if (bend.t >= targetT) {
      var frac = (targetT - currentT) / (bend.t - currentT);
      return add(pos, scale(sub(bend.pt, pos), frac));
    }
    pos = bend.pt;
    currentT = bend.t;
  }

  if (pathData.ray && targetT > currentT + 1e-9) {
    return add(pos, scale(pathData.ray.dir, targetT - currentT));
  }
  return pos;
}

// --- Node.js CJS exports (no-op in browser) ---
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    SURVIVOR_CLIP_MITER: SURVIVOR_CLIP_MITER,
    sub: sub, add: add, scale: scale, len: len, unit: unit,
    cross: cross, dot: dot, perp: perp,
    computeEdgeNormals: computeEdgeNormals, computeEdgeData: computeEdgeData,
    miterVec: miterVec, computeMiters: computeMiters,
    edgeSupportExitT: edgeSupportExitT, computeRayMaxT: computeRayMaxT,
    computeSurvivorMaxT: computeSurvivorMaxT,
    computeCollisionT: computeCollisionT, pqInsert: pqInsert,
    runWavefront: runWavefront, evalPath: evalPath
  };
}
