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

import android.location.Location
import org.onebusaway.android.io.elements.ObaTripStatus

/** Immutable snapshot of a vehicle's state derived from an ObaTripStatus. */
data class VehicleState(
        val vehicleId: String?,
        val activeTripId: String?,
        val position: Location?,
        val lastKnownLocation: Location?,
        val distanceAlongTrip: Double?,
        val lastKnownDistanceAlongTrip: Double?,
        val scheduledDistanceAlongTrip: Double?,
        val totalDistanceAlongTrip: Double?,
        val lastUpdateTime: Long,
        val lastLocationUpdateTime: Long,
        val scheduleDeviation: Long,
        val isPredicted: Boolean,
        val timestamp: Long
) {
    companion object {
        /** Creates a VehicleState with explicit values. Useful for testing. */
        @JvmStatic
        fun create(
                vehicleId: String?,
                activeTripId: String?,
                position: Location?,
                lastKnownLocation: Location?,
                distanceAlongTrip: Double?,
                scheduledDistanceAlongTrip: Double?,
                totalDistanceAlongTrip: Double?,
                lastUpdateTime: Long,
                lastLocationUpdateTime: Long,
                scheduleDeviation: Long,
                predicted: Boolean,
                timestamp: Long
        ) =
                VehicleState(
                        vehicleId,
                        activeTripId,
                        position,
                        lastKnownLocation,
                        distanceAlongTrip,
                        null,
                        scheduledDistanceAlongTrip,
                        totalDistanceAlongTrip,
                        lastUpdateTime,
                        lastLocationUpdateTime,
                        scheduleDeviation,
                        predicted,
                        timestamp
                )

        /** Creates a VehicleState from an ObaTripStatus. */
        @JvmStatic
        fun fromTripStatus(status: ObaTripStatus) =
                VehicleState(
                        vehicleId = status.vehicleId,
                        activeTripId = status.activeTripId,
                        position = status.position,
                        lastKnownLocation = status.lastKnownLocation,
                        distanceAlongTrip = status.distanceAlongTrip,
                        lastKnownDistanceAlongTrip = status.lastKnownDistanceAlongTrip,
                        scheduledDistanceAlongTrip = status.scheduledDistanceAlongTrip,
                        totalDistanceAlongTrip = status.totalDistanceAlongTrip,
                        lastUpdateTime = status.lastUpdateTime,
                        lastLocationUpdateTime = status.lastLocationUpdateTime,
                        scheduleDeviation = status.scheduleDeviation,
                        isPredicted = status.isPredicted,
                        timestamp = System.currentTimeMillis()
                )
    }
}
