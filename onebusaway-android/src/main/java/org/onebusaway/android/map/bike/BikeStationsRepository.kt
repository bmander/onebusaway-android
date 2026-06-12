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
package org.onebusaway.android.map.bike

import android.content.Context
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.io.request.bike.OtpBikeStationRequest
import org.opentripplanner.routing.bike_rental.BikeRentalStation

/**
 * Loads bike rental stations from OpenTripPlanner for a map bounding box. Replaces the
 * `BikeStationLoader` `AsyncTaskLoader` + `BikeLoaderCallbacks`; couriers the raw OTP
 * [BikeRentalStation] list for the bike overlay. The corners are passed in Google-Maps terms
 * (southWest / northEast); the request maps them to OTP's lowerLeft / upperRight.
 */
interface BikeStationsRepository {
    suspend fun getStations(southWest: Location, northEast: Location):
            Result<List<BikeRentalStation>>
}

class DefaultBikeStationsRepository(private val context: Context) : BikeStationsRepository {

    override suspend fun getStations(southWest: Location, northEast: Location):
            Result<List<BikeRentalStation>> = withContext(Dispatchers.IO) {
        runCatching {
            OtpBikeStationRequest.newRequest(context, southWest, northEast).call().stations
        }
    }
}

/**
 * Applies the directions-mode station filter, ported verbatim from
 * `BikeLoaderCallbacks.onLoadFinished`:
 *  - `null` filter → show all stations (returns [all])
 *  - empty filter → show nothing *at all* (returns `null` so the caller leaves the overlay
 *    untouched rather than clearing it — preserves the legacy quirk)
 *  - non-empty filter → only the stations whose id is in the filter
 */
internal fun filterStations(
    all: List<BikeRentalStation>,
    selectedIds: List<String>?,
): List<BikeRentalStation>? = when {
    selectedIds == null -> all
    selectedIds.isEmpty() -> null
    else -> all.filter { selectedIds.contains(it.id) }
}
