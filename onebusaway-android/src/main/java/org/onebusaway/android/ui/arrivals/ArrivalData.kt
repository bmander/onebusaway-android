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
package org.onebusaway.android.ui.arrivals

import org.onebusaway.android.io.elements.ObaArrivalInfo
import org.onebusaway.android.io.elements.Occupancy
import org.onebusaway.android.io.elements.Status

/**
 * The arrival fields [ArrivalInfo] needs to compute its display model, abstracted from the wire
 * type. [ArrivalInfo] depends on this rather than the concrete legacy `ObaArrivalInfo`, so the same
 * display logic serves both the legacy fetch (My Lists badges, via [asArrivalData]) and the
 * modernized arrivals fetch (via `ArrivalDeparture.asArrivalData()`). Times are epoch millis.
 */
interface ArrivalData {
    val routeId: String
    val tripId: String
    val stopId: String
    val headsign: String?
    val shortName: String?
    val routeLongName: String?
    val stopSequence: Int
    val serviceDate: Long
    val vehicleId: String?
    val predicted: Boolean
    val scheduledArrivalTime: Long
    val predictedArrivalTime: Long
    val scheduledDepartureTime: Long
    val predictedDepartureTime: Long
    val status: Status?
    val frequency: FrequencyWindow?
    val historicalOccupancy: Occupancy?
    val predictedOccupancy: Occupancy?
    /** Real-time fields, for the report context; defaults when there is no trip status. */
    val hasTripStatus: Boolean
    val scheduleDeviation: Long
    val lastKnownLat: Double?
    val lastKnownLon: Double?
}

/** Headway-based (exact_times=0) service window; epoch millis / seconds, matching the wire. */
data class FrequencyWindow(val startTime: Long, val endTime: Long, val headway: Long)

/** Adapts the legacy [ObaArrivalInfo] (still produced by the My Lists badge fetch) to [ArrivalData]. */
fun ObaArrivalInfo.asArrivalData(): ArrivalData = LegacyArrivalData(this)

private class LegacyArrivalData(private val a: ObaArrivalInfo) : ArrivalData {
    override val routeId get() = a.routeId
    override val tripId get() = a.tripId
    override val stopId get() = a.stopId
    override val headsign get() = a.headsign
    override val shortName get() = a.shortName
    override val routeLongName get() = a.routeLongName
    override val stopSequence get() = a.stopSequence
    override val serviceDate get() = a.serviceDate
    override val vehicleId get() = a.vehicleId
    override val predicted get() = a.predicted
    override val scheduledArrivalTime get() = a.scheduledArrivalTime
    override val predictedArrivalTime get() = a.predictedArrivalTime
    override val scheduledDepartureTime get() = a.scheduledDepartureTime
    override val predictedDepartureTime get() = a.predictedDepartureTime
    override val status get() = a.tripStatus?.status
    override val frequency
        get() = a.frequency?.let { FrequencyWindow(it.startTime, it.endTime, it.headway) }
    override val historicalOccupancy get() = a.historicalOccupancy
    override val predictedOccupancy get() = a.occupancyStatus
    override val hasTripStatus get() = a.tripStatus != null
    override val scheduleDeviation get() = a.tripStatus?.scheduleDeviation ?: 0L
    override val lastKnownLat get() = a.tripStatus?.lastKnownLocation?.latitude
    override val lastKnownLon get() = a.tripStatus?.lastKnownLocation?.longitude
}
