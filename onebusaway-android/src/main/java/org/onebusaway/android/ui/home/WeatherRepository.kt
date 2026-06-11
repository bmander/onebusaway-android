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
package org.onebusaway.android.ui.home

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.io.request.weather.ObaWeatherRequest
import org.onebusaway.android.io.request.weather.models.ObaWeatherResponse

/** Provides the current weather forecast for a region, for the home map's weather chip. */
interface WeatherRepository {

    suspend fun currentForecast(regionId: Long): Result<ObaWeatherResponse>
}

/**
 * Default implementation wrapping the blocking weather REST call (replaces WeatherRequestTask).
 * The legacy AsyncTask treated a null response or any thrown exception as a failure and only
 * surfaced a response whose current forecast was present, so the same is mapped to
 * [Result.failure] here.
 */
class DefaultWeatherRepository : WeatherRepository {

    override suspend fun currentForecast(regionId: Long): Result<ObaWeatherResponse> =
        withContext(Dispatchers.IO) {
            runCatching { ObaWeatherRequest.newRequest(regionId).call() }
                .mapCatching { response ->
                    if (response?.current_forecast == null) {
                        throw IOException("No weather forecast for region $regionId")
                    }
                    response
                }
        }
}
