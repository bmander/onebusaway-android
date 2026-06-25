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
package org.onebusaway.android.ui.tripdetails

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.R
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.io.client.EntryWithReferences
import org.onebusaway.android.io.client.ObaEnvelope
import org.onebusaway.android.io.client.ObaWebService
import org.onebusaway.android.io.client.References
import org.onebusaway.android.io.client.RouteReference
import org.onebusaway.android.io.client.StopTime
import org.onebusaway.android.io.client.TripDetailsEntry
import org.onebusaway.android.io.client.TripReference
import org.onebusaway.android.io.client.TripStatus
import org.onebusaway.android.io.client.colorArgb
import org.onebusaway.android.util.ArrivalInfoUtils
import org.onebusaway.android.util.DBUtil
import org.onebusaway.android.util.DisplayFormat
import org.onebusaway.android.util.MyTextUtils
import org.onebusaway.android.util.ObaRequestErrors

/** A loaded snapshot of a trip's header + ordered stops, ready for the UI. */
data class TripDetailsData(
    val header: TripHeader,
    val stops: List<TripStopItem>,
    val scrollToIndex: Int,
    val routeId: String,
    val lineColorArgb: Int
)

/** Loads a trip's schedule + real-time status and projects it onto the UI model. */
interface TripDetailsRepository {

    /**
     * @param stopId the stop to focus/scroll to (from the launching intent), or null
     * @param scrollMode [TripDetailsLauncher.SCROLL_MODE_VEHICLE]/`_STOP`, or null for no auto-scroll
     * @param destinationId the destination-reminder stop to flag, or null
     */
    suspend fun getTripDetails(
        tripId: String,
        stopId: String?,
        scrollMode: String?,
        destinationId: String?
    ): Result<TripDetailsData>

    /**
     * Resolves the before/destination stops for [position] in the last loaded trip — persisting both
     * to the provider so [org.onebusaway.android.directions.NavigationService] can resolve them when
     * the reminder fires — or null if unavailable. A narrow, intent-revealing replacement for
     * exposing the whole response to the destination-reminder flow.
     */
    fun destinationStops(position: Int): DestinationReminderStops?

    /** The server `currentTime` of the last good response, or null if none has loaded yet. */
    fun lastLoadedTime(): Long?
}

/** The before/destination stop ids the destination-reminder flow needs for a chosen trip position. */
data class DestinationReminderStops(val beforeStopId: String, val destinationStopId: String)

/**
 * Default implementation backed by the modernized [ObaWebService]. Ports TripDetailsListFragment's
 * binding (header status/deviation, per-stop time + color, passed/vehicle markers, scroll target),
 * falling back to the last good response on failure. Stays on [Dispatchers.IO] for the blocking
 * provider write in [destinationStops]; all Android statics (resources, time formatting, color
 * resolution) are quarantined here so [TripDetailsViewModel] stays JVM-testable. Occupancy is
 * deferred (as in the Compose arrivals rows).
 */
class DefaultTripDetailsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: ObaWebService,
) : TripDetailsRepository {

    private var lastGood: ObaEnvelope<EntryWithReferences<TripDetailsEntry>>? = null

    override suspend fun getTripDetails(
        tripId: String,
        stopId: String?,
        scrollMode: String?,
        destinationId: String?
    ): Result<TripDetailsData> = withContext(Dispatchers.IO) {
        // A transport/parse failure surfaces as null here (getOrNull); a server error code is a
        // non-OK envelope. Either way we fall back to the last good response, matching legacy.
        val envelope = runCatching { service.tripDetails(tripId) }.getOrNull()
        envelope?.takeIf { it.code == ObaApi.OBA_OK && it.data != null }?.let { lastGood = it }
        lastGood?.let { Result.success(toData(it.data!!, stopId, scrollMode, destinationId)) }
            ?: Result.failure(
                IOException(
                    ObaRequestErrors.getRouteErrorString(context, envelope?.code ?: ObaApi.OBA_IO_EXCEPTION)
                )
            )
    }

    override fun destinationStops(position: Int): DestinationReminderStops? {
        val data = lastGood?.data ?: return null
        val stopTimes = data.entry.schedule?.stopTimes ?: return null
        if (position < 1 || position >= stopTimes.size) return null
        val destStop = data.references.stop(stopTimes[position].stopId) ?: return null
        val beforeStop = data.references.stop(stopTimes[position - 1].stopId) ?: return null
        // Persist both so NavigationService can resolve them when the reminder fires (legacy parity).
        DBUtil.addToDB(beforeStop.id, beforeStop.code, beforeStop.name, beforeStop.direction,
            beforeStop.lat, beforeStop.lon)
        DBUtil.addToDB(destStop.id, destStop.code, destStop.name, destStop.direction,
            destStop.lat, destStop.lon)
        return DestinationReminderStops(beforeStop.id, destStop.id)
    }

    override fun lastLoadedTime(): Long? = lastGood?.currentTime

    private fun toData(
        data: EntryWithReferences<TripDetailsEntry>,
        stopId: String?,
        scrollMode: String?,
        destinationId: String?
    ): TripDetailsData {
        val entry = data.entry
        val references = data.references
        // Missing trip/route refs fall back to empty values. (Legacy resolved these to null and
        // then dereferenced them, so an absent ref would have crashed; this is null-safe.)
        val trip = references.trip(entry.tripId) ?: TripReference()
        val route = references.route(trip.routeId) ?: RouteReference()
        // The API reports deviation/position relative to the trip the vehicle is *currently*
        // serving (status.activeTripId). When this view is a different trip in the same block,
        // that real-time data isn't about this trip — present schedule-only (legacy behavior).
        val status = entry.status?.takeIf { it.activeTripId == entry.tripId }
        val stopTimes = entry.schedule?.stopTimes ?: emptyList()

        val isRealtime = status != null && status.predicted
        val nextStopIndex = status?.let { findIndexForStop(stopTimes, it.nextStop) }
        val stopIndex = findIndexForStop(stopTimes, stopId)
        val destinationIndex = findIndexForStop(stopTimes, destinationId)

        // Time base: real-time service date + deviation, or midnight today for schedule-only.
        val deviation = status?.scheduleDeviation ?: 0L
        val serviceDate = status?.serviceDate ?: midnightToday()
        val canceled = status != null && status.status == CANCELED

        val lastIndex = stopTimes.lastIndex
        val stops = stopTimes.mapIndexed { i, stopTime ->
            val stop = references.stop(stopTime.stopId)
            val millis = serviceDate + stopTime.arrivalTime * 1000 + deviation * 1000
            TripStopItem(
                stopId = stopTime.stopId,
                name = MyTextUtils.formatDisplayText(stop?.name).orEmpty(),
                direction = stop?.direction,
                timeText = DisplayFormat.formatTime(context, millis),
                canceled = canceled,
                isPassed = nextStopIndex != null && i < nextStopIndex,
                linePosition = when (i) {
                    0 -> LinePosition.FIRST
                    lastIndex -> LinePosition.LAST
                    else -> LinePosition.MIDDLE
                },
                isVehicleHere = nextStopIndex != null && i == nextStopIndex - 1,
                pin = when (i) {
                    destinationIndex -> StopPin.DESTINATION
                    stopIndex -> StopPin.FOCUSED
                    else -> StopPin.NONE
                }
            )
        }

        val lineColorArgb = route.colorArgb() ?: context.getColor(R.color.theme_primary)

        return TripDetailsData(
            header = buildHeader(references, trip, route, status, isRealtime),
            stops = stops,
            scrollToIndex = resolveScrollIndex(scrollMode, stopIndex, destinationIndex, nextStopIndex),
            routeId = trip.routeId,
            lineColorArgb = lineColorArgb
        )
    }

    private fun buildHeader(
        references: References,
        trip: TripReference,
        route: RouteReference,
        status: TripStatus?,
        isRealtime: Boolean
    ): TripHeader {
        val deviation = status?.scheduleDeviation ?: 0L
        val statusColor = when {
            status == null || !status.predicted -> R.color.stop_info_scheduled_time
            else -> {
                val c = ArrivalInfoUtils.computeColorFromDeviation(TimeUnit.SECONDS.toMinutes(deviation))
                if (c == R.color.stop_info_ontime) R.color.theme_primary else c
            }
        }
        return TripHeader(
            routeShortName = route.shortName.orEmpty(),
            headsign = trip.tripHeadsign.orEmpty(),
            tripShortName = trip.tripShortName?.takeIf { it.isNotBlank() },
            agencyName = references.agency(route.agencyId)?.name.orEmpty(),
            vehicleId = status?.vehicleId?.takeIf { it.isNotEmpty() },
            statusText = headerStatusText(status, deviation),
            statusColor = statusColor,
            isRealtime = isRealtime
        )
    }

    private fun headerStatusText(
        status: TripStatus?,
        deviation: Long
    ): String = when {
        status == null -> context.getString(R.string.trip_details_scheduled_data)
        !status.predicted ->
            if (status.status == CANCELED) context.getString(R.string.stop_info_canceled)
            else context.getString(R.string.trip_details_scheduled_data)

        else -> {
            val minutes = abs(deviation) / 60
            val seconds = abs(deviation) % 60
            val lastUpdate = DisplayFormat.formatTime(context, status.lastUpdateTime)
            when {
                deviation >= 0 && deviation < 60 ->
                    context.getString(R.string.trip_details_real_time_sec_late, seconds, lastUpdate)
                deviation >= 0 ->
                    context.getString(R.string.trip_details_real_time_min_sec_late, minutes, seconds, lastUpdate)
                deviation > -60 ->
                    context.getString(R.string.trip_details_real_time_sec_early, seconds, lastUpdate)
                else ->
                    context.getString(R.string.trip_details_real_time_min_sec_early, minutes, seconds, lastUpdate)
            }
        }
    }

    /** Ports `setScroller`: vehicle first (then stop), or stop first (then vehicle); destination wins. */
    private fun resolveScrollIndex(
        scrollMode: String?,
        stopIndex: Int?,
        destinationIndex: Int?,
        nextStopIndex: Int?
    ): Int {
        destinationIndex?.let { return it }
        val vehicleIndex = nextStopIndex?.let { it - 1 }
        return when (scrollMode) {
            TripDetailsLauncher.SCROLL_MODE_VEHICLE -> vehicleIndex ?: stopIndex ?: -1
            else -> stopIndex ?: vehicleIndex ?: -1
        }
    }

    private fun findIndexForStop(stopTimes: List<StopTime>, stopId: String?): Int? {
        if (stopId == null) return null
        return stopTimes.indexOfFirst { it.stopId == stopId }.takeIf { it >= 0 }
    }

    private fun midnightToday(): Long = GregorianCalendar().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        /** The wire value of [org.onebusaway.android.io.elements.Status.CANCELED]. */
        const val CANCELED = "CANCELED"
    }
}
