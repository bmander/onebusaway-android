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

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The modernized OBA REST ("where") API contract. Each method maps to one endpoint; Retrofit +
 * kotlinx.serialization handle transport and JSON, and [ObaUrlInterceptor] rewrites the relative
 * URL to the current region's host/scheme and appends the api key, version, and app identifiers.
 *
 * This interface is the single declarative source of truth for the API surface, replacing the
 * per-endpoint hand-rolled `Oba*Request` builder classes. Endpoints are added here as they migrate.
 */
interface ObaWebService {

    /**
     * route-details — info about a single route, with its operating agency in the references.
     * {http://developer.onebusaway.org/.../api/where/methods/route.html}
     */
    @GET("api/where/route/{routeId}.json")
    suspend fun route(
        @Path("routeId") routeId: String,
    ): ObaEnvelope<EntryWithReferences<RouteReference>>

    /**
     * stop — details for a single stop (the [StopReference] entry), with the routes serving it in
     * the references.
     * {http://developer.onebusaway.org/.../api/where/methods/stop.html}
     */
    @GET("api/where/stop/{stopId}.json")
    suspend fun stop(
        @Path("stopId") stopId: String,
    ): ObaEnvelope<EntryWithReferences<StopReference>>

    /**
     * agencies-with-coverage — every agency the current region covers, with full agency details
     * in the references.
     * {http://developer.onebusaway.org/.../api/where/methods/agencies-with-coverage.html}
     */
    @GET("api/where/agencies-with-coverage.json")
    suspend fun agenciesWithCoverage(): ObaEnvelope<ListWithReferences<AgencyCoverage>>

    /**
     * routes-for-location — routes near [lat]/[lon], optionally filtered by a short-name [query]
     * and bounded by [radius] (meters). Omitted (null) parameters are dropped from the request.
     * {http://developer.onebusaway.org/.../api/where/methods/routes-for-location.html}
     */
    @GET("api/where/routes-for-location.json")
    suspend fun routesForLocation(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("query") query: String? = null,
        @Query("radius") radius: Int? = null,
    ): ObaEnvelope<ListWithReferences<RouteReference>>

    /**
     * stops-for-location — stops near [lat]/[lon], optionally filtered by a code/name [query] and
     * bounded by [radius] (meters). Omitted (null) parameters are dropped from the request.
     * {http://developer.onebusaway.org/.../api/where/methods/stops-for-location.html}
     */
    @GET("api/where/stops-for-location.json")
    suspend fun stopsForLocation(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("query") query: String? = null,
        @Query("radius") radius: Int? = null,
    ): ObaEnvelope<ListWithReferences<StopReference>>

    /**
     * stops-for-route — a route's stops grouped by direction, with the stops themselves in the
     * references. [includePolylines] is false by default since callers that only need the stop
     * list don't want the (large) shape geometry.
     * {http://developer.onebusaway.org/.../api/where/methods/stops-for-route.html}
     */
    @GET("api/where/stops-for-route/{routeId}.json")
    suspend fun stopsForRoute(
        @Path("routeId") routeId: String,
        @Query("includePolylines") includePolylines: Boolean = false,
    ): ObaEnvelope<EntryWithReferences<StopsForRoute>>

    /**
     * trip-details — a trip's real-time status + schedule, with its trip/route/stop/agency in the
     * references.
     * {http://developer.onebusaway.org/.../api/where/methods/trip-details.html}
     */
    @GET("api/where/trip-details/{tripId}.json")
    suspend fun tripDetails(
        @Path("tripId") tripId: String,
    ): ObaEnvelope<EntryWithReferences<TripDetailsEntry>>

    /**
     * arrivals-and-departures-for-stop — real-time arrivals at a stop within the next
     * [minutesAfter] minutes, with the stop/routes/trips/situations in the references.
     * {http://developer.onebusaway.org/.../api/where/methods/arrivals-and-departures-for-stop.html}
     */
    @GET("api/where/arrivals-and-departures-for-stop/{stopId}.json")
    suspend fun arrivalsAndDeparturesForStop(
        @Path("stopId") stopId: String,
        @Query("minutesAfter") minutesAfter: Int? = null,
    ): ObaEnvelope<EntryWithReferences<ArrivalsForStop>>
}
