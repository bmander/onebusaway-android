/*
 * Copyright (C) 2026 Open Transit Software Foundation
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
package org.onebusaway.android.map

import javax.inject.Inject
import android.location.Location
import org.onebusaway.android.io.client.MapDataSource
import org.onebusaway.android.models.NearbyStops
import org.onebusaway.android.map.render.CameraSnapshot

/**
 * Loads the stops visible in the current map viewport (stops-for-location), via the io.client
 * [MapDataSource] which fetches and adapts the wire references to the model interfaces.
 */
interface StopsRepository {
    /**
     * @return the nearby stops, or `success(null)` when there is no OBA REST API endpoint to contact
     * yet (no current region and no custom API URL) — the controller treats null as a no-op.
     */
    suspend fun getStops(center: Location, latSpan: Double, lonSpan: Double): Result<NearbyStops?>
}

class DefaultStopsRepository @Inject constructor(
    private val mapDataSource: MapDataSource,
) : StopsRepository {

    override suspend fun getStops(center: Location, latSpan: Double, lonSpan: Double): Result<NearbyStops?> =
        mapDataSource.nearbyStops(center.latitude, center.longitude, latSpan, lonSpan)
}

/**
 * The zoom/limit-exceeded half of the "is the last response still good for this viewport?" decision
 * (the legacy `StopsResponse.fulfills`), split out from the Android [Location] center comparison so
 * it can be unit-tested on the JVM. Assumes the caller already confirmed the centers match.
 *
 * @param hasResponse whether the last request produced a (non-null) response
 * @param lastLimitExceeded the last response's `limitExceeded` flag
 * @param lastZoom the zoom the last response was loaded at
 * @param newZoom the zoom of the new viewport
 * @return true if the last response still satisfies the new viewport (no reload needed)
 */
internal fun zoomFulfills(
    hasResponse: Boolean,
    lastLimitExceeded: Boolean,
    lastZoom: Double,
    newZoom: Double,
): Boolean {
    if (!hasResponse) {
        return true
    }
    // Zooming in past a capped response, or zooming out, both need a fresh load.
    if (newZoom > lastZoom && lastLimitExceeded) {
        return false
    }
    if (newZoom < lastZoom) {
        return false
    }
    return true
}

/**
 * The whole-request replacement for the legacy `StopsResponse.fulfills`: is the new viewport [next]
 * already satisfied by the last completed stop load, so no reload is needed? The reactive stop loader
 * in [MapViewModel] uses this in place of the controller's `lastResponse?.fulfills(request)` check.
 *
 * The legacy version compared the request center as an Android [Location] — which has *reference*
 * equality, so a fresh `getMapCenterAsLocation()` instance almost never matched and the center gate
 * rarely short-circuited. [CameraSnapshot.center] is a value type, so the center comparison here is
 * honest value-equality (the intended behavior; see [CameraSnapshot]). The zoom/limit-exceeded half
 * delegates to [zoomFulfills], unchanged.
 *
 * @param last the camera the last completed load was made at, or null if nothing has loaded yet
 * @param lastHadResponse whether that load produced a non-null response (a null response — e.g. no
 * API endpoint — fulfilled future same-center viewports, matching the legacy null-response no-op)
 * @param lastLimitExceeded that response's `limitExceeded` flag
 * @param next the new viewport
 */
internal fun stopRequestFulfilled(
    last: CameraSnapshot?,
    lastHadResponse: Boolean,
    lastLimitExceeded: Boolean,
    next: CameraSnapshot,
): Boolean {
    if (last == null) {
        return false
    }
    if (last.center != next.center) {
        return false
    }
    return zoomFulfills(lastHadResponse, lastLimitExceeded, last.zoom, next.zoom)
}
