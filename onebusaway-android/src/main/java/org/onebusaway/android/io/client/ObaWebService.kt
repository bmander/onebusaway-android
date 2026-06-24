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
     * agencies-with-coverage — every agency the current region covers, with full agency details
     * in the references.
     * {http://developer.onebusaway.org/.../api/where/methods/agencies-with-coverage.html}
     */
    @GET("api/where/agencies-with-coverage.json")
    suspend fun agenciesWithCoverage(): ObaEnvelope<ListWithReferences<AgencyCoverage>>
}
