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

import kotlinx.serialization.Serializable

/**
 * The OBA REST API envelope wrapping every `/api/where` response:
 * `{version, code, currentTime, text, data}`.
 *
 * [code] is the OBA status code (see `ObaApi.OBA_*`), **not** the HTTP status. [T] is the shape of
 * the `data` payload for a given endpoint (e.g. [EntryWithReferences]).
 *
 * This is the modernized, kotlinx.serialization-backed replacement for the hand-rolled Jackson
 * `ObaResponse` hierarchy; new endpoints model their payloads as Kotlin data classes and migrate
 * to it one at a time.
 */
@Serializable
data class ObaEnvelope<T>(
    // The OBA API returns `version` as a JSON number (e.g. 2), not a string.
    val version: Int = 0,
    val code: Int = 0,
    val currentTime: Long = 0,
    val text: String = "",
    val data: T? = null,
)

/**
 * The common `data` shape for single-entry endpoints: one [entry] plus the shared [references]
 * pool that entries point into by id.
 */
@Serializable
data class EntryWithReferences<T>(
    val entry: T,
    val references: References = References(),
)

/**
 * The common `data` shape for list endpoints: a [list] of entries plus the shared [references].
 * [limitExceeded] is true when the API truncated the result to its maximum response size.
 */
@Serializable
data class ListWithReferences<T>(
    val list: List<T> = emptyList(),
    val references: References = References(),
    val limitExceeded: Boolean = false,
)

/**
 * The shared reference pool returned alongside an entry. Only the reference kinds a migrated
 * endpoint actually consumes are modeled; unmodeled kinds (stops, trips, situations, routes) are
 * tolerated on the wire via `ignoreUnknownKeys` and get added as endpoints need them.
 */
@Serializable
data class References(
    val agencies: List<AgencyReference> = emptyList(),
    val stops: List<StopReference> = emptyList(),
) {
    /** Resolves an agency in this pool by id, or null when absent. */
    fun agency(id: String): AgencyReference? = agencies.firstOrNull { it.id == id }
}

/** Wire model for a route, as it appears in an entry or the references pool. */
@Serializable
data class RouteReference(
    val id: String = "",
    val shortName: String? = null,
    val longName: String? = null,
    val description: String? = null,
    val url: String? = null,
    val agencyId: String = "",
)

/** Wire model for an agency in the references pool. */
@Serializable
data class AgencyReference(
    val id: String = "",
    val name: String = "",
    val url: String? = null,
)

/**
 * Wire model for an agencies-with-coverage list entry. Identifies an agency in [References] by
 * [agencyId]; the coverage geometry (lat/lon/spans) is unmodeled until a consumer needs it.
 */
@Serializable
data class AgencyCoverage(
    val agencyId: String = "",
)

/**
 * Wire model for a stop, as it appears in a list entry or the references pool. Only the fields a
 * consumer reads are modeled so far (code, locationType, routeIds, etc. are added when needed).
 */
@Serializable
data class StopReference(
    val id: String = "",
    val name: String? = null,
    val direction: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
)

/**
 * Wire model for the stops-for-route entry. Only [stopGroupings] (the directional grouping of
 * stops) is modeled; the stops themselves are resolved by id from [References.stops].
 */
@Serializable
data class StopsForRoute(
    val stopGroupings: List<StopGrouping> = emptyList(),
)

/** A grouping of stops (typically by direction) within a route. */
@Serializable
data class StopGrouping(
    val stopGroups: List<StopGroup> = emptyList(),
)

/** One directional group: a display [name] and the ordered [stopIds] it contains. */
@Serializable
data class StopGroup(
    val name: StopGroupName = StopGroupName(),
    val stopIds: List<String> = emptyList(),
) {
    /** The group's display name — the first entry of the name object's `names` array, like legacy. */
    val displayName: String? get() = name.names.firstOrNull()
}

/**
 * The group's name object. The canonical OBA field is the `names` array (the scalar `name` some
 * servers also emit is non-standard); [StopGroup.displayName] reads `names[0]` to match legacy.
 */
@Serializable
data class StopGroupName(
    val names: List<String> = emptyList(),
)
