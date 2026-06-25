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

import org.onebusaway.android.io.client.DtoRoute
import org.onebusaway.android.io.client.DtoTrip
import org.onebusaway.android.io.client.DtoTripDetails
import org.onebusaway.android.io.client.EntryWithReferences
import org.onebusaway.android.io.client.ListWithReferences
import org.onebusaway.android.io.client.ObaEnvelope
import org.onebusaway.android.io.client.References
import org.onebusaway.android.io.client.TripDetailsEntry
import org.onebusaway.android.io.client.requireData
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaTrip
import org.onebusaway.android.io.elements.ObaTripDetails

/**
 * The result of a trips-for-route fetch, narrowed to exactly what the speed-estimation/vehicle-render
 * code consumes: the per-vehicle [trips] (each carrying an [ObaTripStatus]), the reference [trip]/[route]
 * lookups those statuses point into, and the server clock ([currentTimeMs]).
 *
 * It exists so consumers (extrapolation, the vehicle renderer + info window, the repository flow) don't
 * depend on the concrete wire model; the modernized io/client trips-for-route fetch adapts to it via
 * [asRouteTrips] (DTO-backed). The downstream logic, which works through the kept
 * `ObaTripStatus`/`ObaTrip`/`ObaRoute` interfaces, is unchanged.
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

/** Presents trip-details entries + their references as [RouteTrips] (DTOs as the legacy interfaces). */
private fun routeTripsOf(
    entries: List<TripDetailsEntry>,
    references: References,
    serverTimeMs: Long,
): RouteTrips = object : RouteTrips {
    override val trips: List<ObaTripDetails> = entries.map { DtoTripDetails(it) }
    override fun trip(tripId: String): ObaTrip? = references.trip(tripId)?.let { DtoTrip(it) }
    override fun route(routeId: String): ObaRoute? = references.route(routeId)?.let { DtoRoute(it) }
    override val currentTimeMs: Long = serverTimeMs
}

/** Adapts a modernized trips-for-route envelope (a list of vehicles) to [RouteTrips]. */
@JvmName("listAsRouteTrips")
internal fun ObaEnvelope<ListWithReferences<TripDetailsEntry>>.asRouteTrips(): RouteTrips {
    val data = requireData()
    return routeTripsOf(data.list, data.references, currentTime)
}

/** Adapts a modernized trip-details envelope (a single trip) to [RouteTrips]. */
@JvmName("entryAsRouteTrips")
internal fun ObaEnvelope<EntryWithReferences<TripDetailsEntry>>.asRouteTrips(): RouteTrips {
    val data = requireData()
    return routeTripsOf(listOf(data.entry), data.references, currentTime)
}
