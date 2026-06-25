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
    val routes: List<RouteReference> = emptyList(),
    val trips: List<TripReference> = emptyList(),
    val situations: List<SituationReference> = emptyList(),
) {
    /** Resolves an agency in this pool by id, or null when absent. */
    fun agency(id: String): AgencyReference? = agencies.firstOrNull { it.id == id }

    /** Resolves a stop in this pool by id, or null when absent. */
    fun stop(id: String): StopReference? = stops.firstOrNull { it.id == id }

    /** Resolves a route in this pool by id, or null when absent. */
    fun route(id: String): RouteReference? = routes.firstOrNull { it.id == id }

    /** Resolves a trip in this pool by id, or null when absent. */
    fun trip(id: String): TripReference? = trips.firstOrNull { it.id == id }

    /** Resolves a situation in this pool by id, or null when absent. */
    fun situation(id: String): SituationReference? = situations.firstOrNull { it.id == id }
}

/** Wire model for a route, as it appears in an entry or the references pool. */
@Serializable
data class RouteReference(
    val id: String = "",
    val shortName: String? = null,
    val longName: String? = null,
    val description: String? = null,
    val type: Int = 0,
    val url: String? = null,
    // Raw hex strings as returned by the API (e.g. "FDB71A"), or null; parsed to an Android color by
    // the consumer that needs it (trip-details / arrivals line color).
    val color: String? = null,
    val textColor: String? = null,
    val agencyId: String = "",
)

/**
 * Wire model for a transit agency — the full agency record returned by the `agency` endpoint and
 * carried (typically with just id/name/url consumed) in the references pool of other responses.
 */
@Serializable
data class AgencyReference(
    val id: String = "",
    val name: String = "",
    val url: String? = null,
    val timezone: String? = null,
    val lang: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val fareUrl: String? = null,
    val disclaimer: String? = null,
    val privateService: Boolean = false,
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
    val code: String? = null,
    val direction: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val locationType: Int = 0,
    val routeIds: List<String> = emptyList(),
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

/** Wire model for a trip in the references pool. Names match the wire (`tripHeadsign`/`tripShortName`). */
@Serializable
data class TripReference(
    val id: String = "",
    val routeId: String = "",
    val tripHeadsign: String? = null,
    val tripShortName: String? = null,
    val blockId: String? = null,
)

/** Wire model for the trip-details entry: real-time [status] and the [schedule] of stop times. */
@Serializable
data class TripDetailsEntry(
    val tripId: String = "",
    val status: TripStatus? = null,
    val schedule: TripSchedule? = null,
)

/**
 * Real-time status for a trip. Only the fields the trip-details screen reads are modeled; times are
 * epoch millis, [scheduleDeviation] is seconds (+late/−early), [status] is the wire string (e.g.
 * "CANCELED"), and [activeTripId] is the trip the vehicle is currently serving.
 */
@Serializable
data class TripStatus(
    val activeTripId: String = "",
    val predicted: Boolean = false,
    val scheduleDeviation: Long = 0,
    val serviceDate: Long = 0,
    val status: String = "",
    val nextStop: String? = null,
    val vehicleId: String? = null,
    val lastUpdateTime: Long = 0,
    val lastKnownLocation: Position? = null,
)

/** A lat/lon point (e.g. a trip's last-known vehicle location). */
@Serializable
data class Position(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
)

/** The scheduled stop times of a trip, in order. */
@Serializable
data class TripSchedule(
    val stopTimes: List<StopTime> = emptyList(),
)

/** One scheduled stop on a trip; [arrivalTime] is seconds since the service-date midnight. */
@Serializable
data class StopTime(
    val stopId: String = "",
    val arrivalTime: Long = 0,
)

/**
 * The arrivals-and-departures-for-stop entry: the [arrivalsAndDepartures] at [stopId], plus the
 * [nearbyStopIds] and stop-level [situationIds] (resolved against [References]).
 */
@Serializable
data class ArrivalsForStop(
    val stopId: String = "",
    val arrivalsAndDepartures: List<ArrivalDeparture> = emptyList(),
    val nearbyStopIds: List<String> = emptyList(),
    val situationIds: List<String> = emptyList(),
)

/**
 * One predicted/scheduled arrival-departure at a stop. Wire names `tripHeadsign`/`routeShortName`
 * match the API; times are epoch millis; occupancy/status are wire strings mapped to the display
 * enums by the projection. Only the fields the arrivals projection reads are modeled.
 */
@Serializable
data class ArrivalDeparture(
    val routeId: String = "",
    val tripId: String = "",
    val stopId: String = "",
    val tripHeadsign: String? = null,
    val routeShortName: String? = null,
    val routeLongName: String? = null,
    val stopSequence: Int = 0,
    val serviceDate: Long = 0,
    val vehicleId: String? = null,
    val predicted: Boolean = false,
    val scheduledArrivalTime: Long = 0,
    val predictedArrivalTime: Long = 0,
    val scheduledDepartureTime: Long = 0,
    val predictedDepartureTime: Long = 0,
    val tripStatus: TripStatus? = null,
    val frequency: Frequency? = null,
    val historicalOccupancy: String? = null,
    val occupancyStatus: String? = null,
    val situationIds: List<String> = emptyList(),
)

/** Headway-based (exact_times=0) service window for a frequency trip; all epoch millis / seconds. */
@Serializable
data class Frequency(
    val startTime: Long = 0,
    val endTime: Long = 0,
    val headway: Long = 0,
)

/** Wire model for a service alert (situation) in the references pool. */
@Serializable
data class SituationReference(
    val id: String = "",
    val summary: SituationText = SituationText(),
    val description: SituationText = SituationText(),
    val url: SituationText = SituationText(),
    val severity: String? = null,
    val activeWindows: List<SituationWindow> = emptyList(),
    val allAffects: List<SituationAffects> = emptyList(),
)

/** An OBA localized-string wrapper (`{value, lang}`); only the [value] is modeled. */
@Serializable
data class SituationText(
    val value: String? = null,
)

/** A situation active window; [from]/[to] are epoch seconds (to == 0 means no end). */
@Serializable
data class SituationWindow(
    val from: Long = 0,
    val to: Long = 0,
)

/** A situation's affects clause; only [routeId] is modeled (for route-filtered alerts). */
@Serializable
data class SituationAffects(
    val routeId: String? = null,
)
