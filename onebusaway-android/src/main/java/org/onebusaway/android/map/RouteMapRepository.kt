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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.io.request.ObaStopsForRouteRequest
import org.onebusaway.android.io.request.ObaStopsForRouteResponse
import org.onebusaway.android.io.request.ObaTripsForRouteRequest
import org.onebusaway.android.io.request.ObaTripsForRouteResponse

/**
 * Loads a route's stops + shapes (one-shot). Replaces the `RoutesLoader` `AsyncTaskLoader` formerly
 * nested in `RouteMapController`; couriers the raw [ObaStopsForRouteResponse] for the route/stop
 * overlays.
 */
interface RouteShapesRepository {
    /** @return the stops-for-route response, or `success(null)` when there is no API endpoint. */
    suspend fun getRoute(routeId: String): Result<ObaStopsForRouteResponse?>
}

/**
 * Loads a route's real-time vehicles (polled every 10s by the controller). Replaces the
 * `VehiclesLoader` `AsyncTaskLoader`; couriers the raw [ObaTripsForRouteResponse] for the vehicle
 * overlay.
 */
interface RouteVehiclesRepository {
    /** @return the trips-for-route response, or `success(null)` when there is no API endpoint. */
    suspend fun getVehicles(routeId: String): Result<ObaTripsForRouteResponse?>
}

class DefaultRouteMapRepository(private val context: Context) :
    RouteShapesRepository, RouteVehiclesRepository {

    override suspend fun getRoute(routeId: String): Result<ObaStopsForRouteResponse?> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!hasObaApiEndpoint()) {
                    null
                } else {
                    ObaStopsForRouteRequest.Builder(context, routeId)
                        .setIncludeShapes(true)
                        .build()
                        .call()
                }
            }
        }

    override suspend fun getVehicles(routeId: String): Result<ObaTripsForRouteResponse?> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!hasObaApiEndpoint()) {
                    null
                } else {
                    ObaTripsForRouteRequest.Builder(context, routeId)
                        .setIncludeStatus(true)
                        .build()
                        .call()
                }
            }
        }
}
