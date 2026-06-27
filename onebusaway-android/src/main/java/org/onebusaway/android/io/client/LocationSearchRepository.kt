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

import android.util.Log
import javax.inject.Inject
import org.onebusaway.android.models.ObaRoute
import org.onebusaway.android.models.ObaStop

/**
 * Fetches routes/stops near a location from the modernized OBA REST client, adapting the wire
 * references to the [ObaRoute]/[ObaStop] model interfaces so callers never see the DTOs. Returns
 * [Result.failure] (IO / HTTP / non-OK code) rather than throwing.
 */
interface LocationSearchRepository {

    suspend fun routesNear(lat: Double, lon: Double, query: String?, radius: Int?): Result<List<ObaRoute>>

    suspend fun stopsNear(lat: Double, lon: Double, query: String?, radius: Int?): Result<List<ObaStop>>
}

/** Default implementation backed by [ObaWebService]; adapts each reference via [DtoRoute]/[DtoStop]. */
class DefaultLocationSearchRepository @Inject constructor(
    private val service: ObaWebService,
) : LocationSearchRepository {

    override suspend fun routesNear(
        lat: Double, lon: Double, query: String?, radius: Int?,
    ): Result<List<ObaRoute>> = runCatching {
        service.routesForLocation(lat, lon, query, radius).requireData().list.map(::DtoRoute)
    }.onFailure { Log.e(TAG, "routesNear failed", it) }

    override suspend fun stopsNear(
        lat: Double, lon: Double, query: String?, radius: Int?,
    ): Result<List<ObaStop>> = runCatching {
        service.stopsForLocation(lat, lon, query, radius).requireData().list.map(::DtoStop)
    }.onFailure { Log.e(TAG, "stopsNear failed", it) }

    private companion object {
        const val TAG = "LocationSearchRepository"
    }
}
