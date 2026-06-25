/*
 * Copyright (C) 2024-2026 Open Transit Software Foundation
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

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.onebusaway.android.io.client.ObaWebService
import org.onebusaway.android.io.client.requireData
import org.onebusaway.android.io.client.toObaTripSchedule
import org.onebusaway.android.io.elements.ObaShapeElement
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.util.Polyline
import org.onebusaway.android.util.SingleFlight

/**
 * The network I/O seam of the trip data layer: every OBA call the repository needs, and nothing
 * else. It knows nothing about the [TripStateCache] — it takes IDs, returns values, and leaves
 * caching and hydration to [DefaultTripObservationRepository]. Pulling the calls behind this
 * interface lets the repository's polling Flows be unit-tested against a fake fetcher with no
 * network.
 *
 * Volatile status ([tripDetails], [tripsForRoute]) is re-fetched every poll tick, so it isn't
 * deduplicated. Immutable resources ([tripSchedule], [shape]) are coalesced with [SingleFlight] —
 * concurrent callers for the same resource share one fetch — so a route backfill watching dozens
 * of trips can't fan out into dozens of duplicate requests.
 *
 * Failures resolve to null (logged once); callers retry on their next attempt.
 */
interface TripObservationFetcher {

    suspend fun tripDetails(tripId: String): TripDetails?

    suspend fun tripsForRoute(routeId: String): RouteTrips?

    suspend fun tripSchedule(tripId: String): ObaTripSchedule?

    suspend fun shape(shapeId: String): Polyline?
}

/**
 * What one trip-details poll distilled for the store: the vehicle [observations], the trip [schedule]
 * and [serviceDate], the [shapeId] of the polled trip (for on-demand shape activation), and the trip
 * the vehicle currently reports active ([vehicleActiveTripId], null without a status).
 */
data class TripDetails(
    val observations: List<TripObservation>,
    val schedule: ObaTripSchedule?,
    val serviceDate: Long,
    val vehicleActiveTripId: String?,
    val shapeId: String?,
)

private const val MAX_CONCURRENT_FETCHES = 2
private const val TAG = "TripObservationFetcher"

@Singleton
class DefaultTripObservationFetcher @Inject constructor(
        private val obaWebService: ObaWebService
) : TripObservationFetcher {

    /** Process-lifetime scope confined to the main thread; the SingleFlight maps live under it. */
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * All fetches run on this IO-dispatcher view, which admits at most [MAX_CONCURRENT_FETCHES] at
     * once — so a route backfill observing dozens of trips can't issue dozens of simultaneous API
     * requests.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fetchDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_FETCHES)

    private val scheduleFetches = SingleFlight<String, ObaTripSchedule>(fetchScope)
    private val shapeFetches = SingleFlight<String, Polyline>(fetchScope)

    override suspend fun tripDetails(tripId: String): TripDetails? =
            guarded("trip details for $tripId") {
                // requireData throws on a non-OK code; guarded maps it to null.
                val envelope = obaWebService.tripDetails(tripId)
                val routeTrips = envelope.asRouteTrips()
                val entry = envelope.requireData().entry
                TripDetails(
                    observations = routeTrips.toObservations(),
                    schedule = entry.schedule?.toObaTripSchedule(),
                    serviceDate = entry.status?.serviceDate ?: 0,
                    vehicleActiveTripId = entry.status?.activeTripId?.ifBlank { null },
                    shapeId = routeTrips.trip(tripId)?.shapeId?.takeIf { it.isNotEmpty() },
                )
            }

    override suspend fun tripsForRoute(routeId: String): RouteTrips? =
            guarded("trips for route $routeId") {
                // requireData (inside asRouteTrips) throws on a non-OK code; guarded maps it to null.
                obaWebService.tripsForRoute(routeId).asRouteTrips()
            }

    /**
     * Runs [block], resolving any failure to null (logged) so a transient network error becomes a
     * skipped poll tick rather than a crashed Flow. [CancellationException] propagates — a stopped
     * poll is not a failure. (The SingleFlight-coalesced fetches guard themselves the same way.)
     */
    private suspend fun <T : Any> guarded(what: String, block: suspend () -> T?): T? =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch $what", e)
                null
            }

    override suspend fun tripSchedule(tripId: String): ObaTripSchedule? =
            scheduleFetches.run(tripId) {
                guarded("schedule for $tripId") {
                    obaWebService.tripDetails(tripId).requireData().entry.schedule?.toObaTripSchedule()
                }.also {
                    if (it == null) Log.w(TAG, "Schedule fetch for $tripId yielded no schedule")
                }
            }

    override suspend fun shape(shapeId: String): Polyline? =
            shapeFetches.run(shapeId) {
                withContext(fetchDispatcher) {
                    // ObaShapeElement.decodeLine is the shared (Google-algorithm) polyline decoder,
                    // so the geometry matches the legacy path exactly. Error codes throw in
                    // requireData and resolve to null below, like the old null-coalescing path did.
                    runCatching {
                        val entry = obaWebService.shape(shapeId).requireData().entry
                        ObaShapeElement.decodeLine(entry.points, entry.length)
                    }.getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { Polyline(it) }
                }.also {
                    if (it == null) Log.w(TAG, "Shape fetch for $shapeId yielded no polyline")
                }
            }
}
