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

import android.location.Location
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaTrip
import org.onebusaway.android.io.elements.ObaTripDetails
import org.onebusaway.android.io.elements.ObaTripSchedule
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.ObaTripSchedule.StopTime as ObaStopTime
import org.onebusaway.android.io.elements.Occupancy
import org.onebusaway.android.io.elements.Status
import org.onebusaway.android.util.LocationUtils

/*
 * Adapters that present the io/client trip DTOs as the legacy io/elements interfaces
 * (ObaTripStatus/ObaTrip/ObaRoute/ObaTripDetails), so the speed-estimation/vehicle-render code —
 * which works through those interfaces — consumes the modernized fetch unchanged. The same
 * one-DTO-implements-the-legacy-interface pattern the map boundary used for stops/routes.
 */

private fun Position.toLocation(): Location = LocationUtils.makeLocation(lat, lon)

/** Presents a [TripStatus] DTO as an [ObaTripStatus]. */
class DtoTripStatus(private val dto: TripStatus) : ObaTripStatus {
    override fun getServiceDate(): Long = dto.serviceDate
    override fun isPredicted(): Boolean = dto.predicted
    override fun getScheduleDeviation(): Long = dto.scheduleDeviation
    override fun getVehicleId(): String? = dto.vehicleId
    override fun getClosestStop(): String? = dto.closestStop
    override fun getClosestStopTimeOffset(): Long = dto.closestStopTimeOffset
    override fun getPosition(): Location? = dto.position?.toLocation()
    // Absent active-trip id reads as null, like the legacy element (callers skip on it).
    override fun getActiveTripId(): String? = dto.activeTripId.ifBlank { null }
    override fun getDistanceAlongTrip(): Double? = dto.distanceAlongTrip
    override fun getScheduledDistanceAlongTrip(): Double? = dto.scheduledDistanceAlongTrip
    override fun getTotalDistanceAlongTrip(): Double? = dto.totalDistanceAlongTrip
    override fun getOrientation(): Double? = dto.orientation
    override fun getNextStop(): String? = dto.nextStop
    override fun getNextStopTimeOffset(): Long? = dto.nextStopTimeOffset
    override fun getPhase(): String? = dto.phase
    override fun getStatus(): Status? = Status.fromString(dto.status)
    override fun getLastUpdateTime(): Long = dto.lastUpdateTime
    override fun getLastKnownLocation(): Location? = dto.lastKnownLocation?.toLocation()
    override fun getLastLocationUpdateTime(): Long = dto.lastLocationUpdateTime
    override fun getLastKnownDistanceAlongTrip(): Double? = dto.lastKnownDistanceAlongTrip
    override fun getLastKnownOrientation(): Double? = dto.lastKnownOrientation
    override fun getBlockTripSequence(): Int = dto.blockTripSequence
    override fun getOccupancyStatus(): Occupancy? =
        dto.occupancyStatus?.takeIf { it.isNotEmpty() }?.let { Occupancy.fromString(it) }
}

/** Presents a [TripReference] as an [ObaTrip]. */
class DtoTrip(private val ref: TripReference) : ObaTrip {
    override fun getId(): String = ref.id
    override fun getShortName(): String? = ref.tripShortName
    override fun getShapeId(): String? = ref.shapeId
    override fun getDirectionId(): Int = ref.directionId?.toIntOrNull() ?: 0
    override fun getServiceId(): String? = ref.serviceId
    override fun getHeadsign(): String? = ref.tripHeadsign
    override fun getTimezone(): String? = ref.timeZone
    override fun getRouteId(): String = ref.routeId
    override fun getBlockId(): String? = ref.blockId
}

/** Presents a [RouteReference] as an [ObaRoute]. */
class DtoRoute(private val ref: RouteReference) : ObaRoute {
    override fun getId(): String = ref.id
    override fun getShortName(): String? = ref.shortName
    override fun getLongName(): String? = ref.longName
    override fun getDescription(): String? = ref.description
    override fun getType(): Int = ref.type
    override fun getUrl(): String? = ref.url
    override fun getColor(): Int? = ref.colorArgb()
    override fun getTextColor(): Int? = ref.textColorArgb()
    override fun getAgencyId(): String = ref.agencyId
}

/** Presents a [TripDetailsEntry] as an [ObaTripDetails]. */
class DtoTripDetails(private val entry: TripDetailsEntry) : ObaTripDetails {
    override fun getId(): String = entry.tripId
    override fun getStatus(): ObaTripStatus? = entry.status?.let { DtoTripStatus(it) }
    override fun getSchedule(): ObaTripSchedule? = entry.schedule?.toObaTripSchedule()
}

/** Maps the io/client [TripSchedule] DTO to the legacy [ObaTripSchedule] (consumed by schedule replay). */
fun TripSchedule.toObaTripSchedule(): ObaTripSchedule = ObaTripSchedule(
    stopTimes.map {
        ObaStopTime(
            it.stopId,
            it.stopHeadsign,
            it.arrivalTime,
            it.departureTime,
            it.historicalOccupancy,
            it.predictedOccupancy,
            it.distanceAlongTrip,
        )
    }.toTypedArray(),
    timeZone,
    previousTripId,
    nextTripId,
)
