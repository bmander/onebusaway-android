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
package org.onebusaway.android.io.client

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.app.Application
import org.onebusaway.android.models.NearbyStops
import org.onebusaway.android.models.RouteMapData
import org.onebusaway.android.util.PolylineDecoder

/**
 * Fetches map data (stops-for-location / stops-for-route) from the modernized OBA REST client,
 * adapting the wire references to the [ObaStop]/[ObaRoute] model interfaces so the map never sees a
 * DTO. Returns `success(null)` when there is no OBA endpoint to contact yet (no current region and no
 * custom API URL) — the controllers treat null as a no-op — and `failure` on IO / HTTP / non-OK code.
 */
interface MapDataSource {

    suspend fun nearbyStops(lat: Double, lon: Double, latSpan: Double, lonSpan: Double): Result<NearbyStops?>

    suspend fun routeMap(routeId: String): Result<RouteMapData?>
}

class DefaultMapDataSource @Inject constructor(
    private val service: ObaWebService,
) : MapDataSource {

    override suspend fun nearbyStops(
        lat: Double, lon: Double, latSpan: Double, lonSpan: Double,
    ): Result<NearbyStops?> = obaApiCall {
        val data = service.stopsForLocation(lat = lat, lon = lon, latSpan = latSpan, lonSpan = lonSpan)
            .requireData()
        NearbyStops(
            stops = data.list.map(::DtoStop),
            routes = data.references.routes.map(::DtoRoute),
            outOfRange = data.outOfRange,
            limitExceeded = data.limitExceeded,
        )
    }

    override suspend fun routeMap(routeId: String): Result<RouteMapData?> = obaApiCall {
        val data = service.stopsForRoute(routeId, includePolylines = true).requireData()
        val route = data.references.route(routeId)?.let(::DtoRoute)
        RouteMapData(
            route = route,
            agencyName = route?.agencyId?.let { data.references.agency(it)?.name },
            stops = data.references.stops.map(::DtoStop),
            routes = data.references.routes.map(::DtoRoute),
            polylines = data.entry.polylines.map { PolylineDecoder.decodeLine(it.points, it.length) },
        )
    }
}

/**
 * Runs a blocking OBA REST [block] on the IO dispatcher, wrapped in a [Result]. Returns
 * `success(null)` when there is no endpoint to contact yet (no current region and no custom API URL).
 */
private suspend fun <T> obaApiCall(block: suspend () -> T): Result<T?> =
    withContext(Dispatchers.IO) {
        runCatching { if (!hasObaApiEndpoint()) null else block() }
    }

/** True when there is an OBA REST endpoint to contact — a current region or a custom API URL. */
private fun hasObaApiEndpoint(): Boolean {
    val app = Application.get()
    return app.currentRegion != null || !app.customApiUrl.isNullOrEmpty()
}
