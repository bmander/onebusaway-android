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
package org.onebusaway.android.extrapolation.data

import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaTrip
import org.onebusaway.android.io.elements.ObaTripDetails
import org.onebusaway.android.io.request.ObaTripsForRouteResponse

/**
 * The result of a trips-for-route fetch, narrowed to exactly what the speed-estimation/vehicle-render
 * code consumes: the per-vehicle [trips] (each carrying an [ObaTripStatus]), the reference [trip]/[route]
 * lookups those statuses point into, and the server clock ([currentTimeMs]).
 *
 * It exists so consumers (extrapolation, the vehicle renderer + info window, the repository flow) don't
 * depend on the concrete Jackson [ObaTripsForRouteResponse]; the legacy fetch adapts to it via
 * [asRouteTrips], and the modernized io/client fetch supplies a DTO-backed implementation — the
 * downstream logic, which works through the kept `ObaTripStatus`/`ObaTrip`/`ObaRoute` interfaces, is
 * unchanged either way.
 */
interface RouteTrips {

    /** The active/served trips in this poll; each exposes its vehicle [ObaTripDetails.getStatus]. */
    val trips: List<ObaTripDetails>

    /** Resolves a trip from the references pool by id, or null when absent. */
    fun trip(tripId: String): ObaTrip?

    /** Resolves a route from the references pool by id, or null when absent. */
    fun route(routeId: String): ObaRoute?

    /** The server's response time, epoch millis (the observation's server clock). */
    val currentTimeMs: Long
}

/** Adapts the legacy Jackson trips-for-route response to [RouteTrips] (interim; dropped at the fetch flip). */
internal fun ObaTripsForRouteResponse.asRouteTrips(): RouteTrips = object : RouteTrips {
    override val trips: List<ObaTripDetails> get() = this@asRouteTrips.trips.toList()
    override fun trip(tripId: String): ObaTrip? = getTrip(tripId)
    override fun route(routeId: String): ObaRoute? = getRoute(routeId)
    override val currentTimeMs: Long get() = currentTime
}
