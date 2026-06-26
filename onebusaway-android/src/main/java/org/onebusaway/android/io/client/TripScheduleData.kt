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

import org.onebusaway.android.models.ObaTripSchedule
import org.onebusaway.android.models.Occupancy

/**
 * Plain in-memory [ObaTripSchedule], built by [toObaTripSchedule] from the wire DTO. Not a `data`
 * class — equality is identity, matching the schedule's use as a cached value where structural
 * equality over the [stopTimes] array would be meaningless.
 */
class TripScheduleData(
    override val stopTimes: Array<ObaTripSchedule.StopTime> = emptyArray(),
    override val timeZone: String? = null,
    override val previousTripId: String? = null,
    override val nextTripId: String? = null,
) : ObaTripSchedule {

    companion object {
        @JvmField
        val EMPTY = TripScheduleData()
    }
}

/** Plain in-memory [ObaTripSchedule.StopTime]. */
class StopTimeData(
    override val stopId: String = "",
    override val headsign: String? = null,
    override val arrivalTime: Long = 0,
    override val departureTime: Long = 0,
    override val historicalOccupancy: Occupancy? = null,
    override val predictedOccupancy: Occupancy? = null,
    override val distanceAlongTrip: Double = 0.0,
) : ObaTripSchedule.StopTime
