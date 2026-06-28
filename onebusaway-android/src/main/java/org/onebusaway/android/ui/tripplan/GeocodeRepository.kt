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
package org.onebusaway.android.ui.tripplan

import android.content.Context
import android.location.Address
import android.location.Geocoder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.directions.util.CustomAddress
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.util.BuildFlavorUtils
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.RegionUtils

/** Address-autocomplete suggestions for the trip-plan endpoints. */
interface GeocodeRepository {
    suspend fun suggest(query: String): Result<List<TripEndpoint.Geocoded>>
}

/**
 * Address suggestions for the trip-plan endpoints. Prefers Pelias (real autocomplete, with
 * `transport:public` results flagged as transit), but falls back to the on-device
 * [android.location.Geocoder] when no Pelias API key is configured — so key-free dev builds still
 * geocode. The Geocoder path has no typeahead/transit categories, so it's a degraded fallback only.
 * Runs the blocking work on the IO thread and projects onto the JVM-pure [TripEndpoint.Geocoded].
 */
class DefaultGeocodeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val regionRepository: RegionRepository,
) : GeocodeRepository {

    override suspend fun suggest(query: String): Result<List<TripEndpoint.Geocoded>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val region = regionRepository.region.value
                if (BuildFlavorUtils.isPeliasApiKeyDefined()) {
                    LocationUtils.processPeliasGeocoding(context, region, query).orEmpty()
                        .map { it.toGeocoded() }
                } else {
                    androidGeocode(query, region)
                }
            }
        }

    /** No-key fallback: forward-geocode via the platform Geocoder, biased to the region's bbox. */
    @Suppress("DEPRECATION") // sync overload; we're already off the main thread. Async API is 33+.
    private fun androidGeocode(query: String, region: ObaRegion?): List<TripEndpoint.Geocoded> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = if (region != null) {
            val span = DoubleArray(4)
            RegionUtils.getRegionSpan(region, span)
            geocoder.getFromLocationName(
                query, MAX_RESULTS,
                /* lowerLeftLat = */ span[2] - span[0] / 2,
                /* lowerLeftLon = */ span[3] - span[1] / 2,
                /* upperRightLat = */ span[2] + span[0] / 2,
                /* upperRightLon = */ span[3] + span[1] / 2,
            )
        } else {
            geocoder.getFromLocationName(query, MAX_RESULTS)
        }
        return addresses.orEmpty().map { it.toGeocoded() }
    }

    private fun CustomAddress.toGeocoded(): TripEndpoint.Geocoded = TripEndpoint.Geocoded(
        displayName = toString(),
        lat = if (isSet) latitude else null,
        lon = if (isSet) longitude else null,
        isTransit = isTransitCategory
    )

    private fun Address.toGeocoded(): TripEndpoint.Geocoded {
        val lines = (0..maxAddressLineIndex).joinToString(", ") { getAddressLine(it) }
        val name = lines.ifBlank { featureName ?: thoroughfare ?: locality.orEmpty() }
        return TripEndpoint.Geocoded(displayName = name, lat = latitude, lon = longitude)
    }

    private companion object {
        const val MAX_RESULTS = 5
    }
}
