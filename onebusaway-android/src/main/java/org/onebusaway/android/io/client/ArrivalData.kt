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

import org.onebusaway.android.models.Occupancy
import org.onebusaway.android.models.Status

/**
 * The arrival fields the display model (`ArrivalInfo`) computes from, abstracted from the wire type
 * so feature code never sees the [ArrivalDeparture] DTO. The io.client arrivals fetch
 * ([StopArrivals.arrivals]) produces these; times are epoch millis.
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

/** Adapts a modernized [ArrivalDeparture] DTO (arrivals fetch) to [ArrivalData]. */
internal fun ArrivalDeparture.asArrivalData(): ArrivalData = DtoArrivalData(this)

private class DtoArrivalData(private val d: ArrivalDeparture) : ArrivalData {
    override val routeId get() = d.routeId
    override val tripId get() = d.tripId
    override val stopId get() = d.stopId
    override val headsign get() = d.tripHeadsign
    override val shortName get() = d.routeShortName
    override val routeLongName get() = d.routeLongName
    override val stopSequence get() = d.stopSequence
    override val serviceDate get() = d.serviceDate
    override val vehicleId get() = d.vehicleId
    override val predicted get() = d.predicted
    override val scheduledArrivalTime get() = d.scheduledArrivalTime
    override val predictedArrivalTime get() = d.predictedArrivalTime
    override val scheduledDepartureTime get() = d.scheduledDepartureTime
    override val predictedDepartureTime get() = d.predictedDepartureTime
    override val status get() = d.tripStatus?.status?.let { Status.fromString(it) }
    override val frequency
        get() = d.frequency?.let { FrequencyWindow(it.startTime, it.endTime, it.headway) }
    override val historicalOccupancy
        get() = d.historicalOccupancy?.takeIf { it.isNotEmpty() }?.let { Occupancy.fromString(it) }
    override val predictedOccupancy
        get() = d.occupancyStatus?.takeIf { it.isNotEmpty() }?.let { Occupancy.fromString(it) }
    override val hasTripStatus get() = d.tripStatus != null
    override val scheduleDeviation get() = d.tripStatus?.scheduleDeviation ?: 0L
    override val lastKnownLat get() = d.tripStatus?.lastKnownLocation?.lat
    override val lastKnownLon get() = d.tripStatus?.lastKnownLocation?.lon
}
