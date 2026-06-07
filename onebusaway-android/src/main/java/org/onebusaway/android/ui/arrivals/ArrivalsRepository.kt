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

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.io.request.ObaArrivalInfoRequest
import org.onebusaway.android.io.request.ObaArrivalInfoResponse
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.ui.ArrivalInfo
import org.onebusaway.android.util.ArrivalInfoUtils
import org.onebusaway.android.util.BuildFlavorUtils
import org.onebusaway.android.util.UIUtils

/** A loaded snapshot of a stop's arrivals plus the header data. */
data class ArrivalsData(
    val arrivals: List<ArrivalInfo>,
    val header: StopHeader,
    /** The effective time window after the loader's empty-result expansion. */
    val minutesAfter: Int,
    val style: Int,
    val isStale: Boolean
)

/** Loads real-time arrivals for a stop. */
interface ArrivalsRepository {

    suspend fun getArrivals(
        stopId: String,
        minutesAfter: Int,
        routeFilter: Set<String>
    ): Result<ArrivalsData>

    /** Marks (or unmarks) the stop as a favorite in the provider. */
    suspend fun setStopFavorite(stopId: String, favorite: Boolean)
}

/**
 * Default implementation wrapping the blocking arrivals-and-departures request. Ports
 * ArrivalsListLoader's behavior: widen the time window until arrivals are found, and fall back
 * to the last good response when a refresh fails. Builds the existing [ArrivalInfo] display
 * model on the IO thread (its constructor reads the favorites ContentProvider). All Android
 * statics are quarantined here so [ArrivalsViewModel] stays JVM-testable.
 */
class DefaultArrivalsRepository(private val context: Context) : ArrivalsRepository {

    private var lastGood: ObaArrivalInfoResponse? = null

    private var lastGoodMinutesAfter: Int = MINUTES_AFTER_DEFAULT

    override suspend fun getArrivals(
        stopId: String,
        minutesAfter: Int,
        routeFilter: Set<String>
    ): Result<ArrivalsData> = withContext(Dispatchers.IO) {
        var minutes = minutesAfter
        var response: ObaArrivalInfoResponse
        var empty: Boolean
        do {
            response = ObaArrivalInfoRequest.newRequest(context, stopId, minutes).call()
            empty = response.arrivalInfo.isNullOrEmpty()
            if (empty) {
                minutes += MINUTES_AFTER_INCREMENT
            }
        } while (empty && minutes <= MINUTES_AFTER_MAX)

        when {
            response.code == ObaApi.OBA_OK -> {
                lastGood = response
                lastGoodMinutesAfter = minutes
                Result.success(toData(stopId, response, minutes, routeFilter, isStale = false))
            }
            // Refresh failed but we have prior data — keep showing it (legacy stale fallback)
            lastGood != null ->
                Result.success(toData(stopId, lastGood!!, lastGoodMinutesAfter, routeFilter, isStale = true))

            else -> Result.failure(IOException(UIUtils.getStopErrorString(context, response.code)))
        }
    }

    private fun toData(
        stopId: String,
        response: ObaArrivalInfoResponse,
        minutesAfter: Int,
        routeFilter: Set<String>,
        isStale: Boolean
    ): ArrivalsData {
        val style = BuildFlavorUtils.getArrivalInfoStyleFromPreferences()
        // Style B includes the arrival/departure word in the status label; Style A does not
        val includeArrivalDepartureLabel = style == BuildFlavorUtils.ARRIVAL_INFO_STYLE_B
        val arrivals = ArrivalInfoUtils.convertObaArrivalInfo(
            context,
            response.arrivalInfo ?: emptyArray(),
            ArrayList(routeFilter),
            System.currentTimeMillis(),
            includeArrivalDepartureLabel
        )
        val stop = response.stop
        val header = StopHeader(
            stopId = stopId,
            name = UIUtils.formatDisplayText(stop?.name).orEmpty(),
            direction = stop?.direction,
            isFavorite = ObaContract.Stops.isFavorite(context, stopId),
            routeCount = stop?.routeIds?.size ?: 0
        )
        return ArrivalsData(arrivals, header, minutesAfter, style, isStale)
    }

    override suspend fun setStopFavorite(stopId: String, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId)
            ObaContract.Stops.markAsFavorite(context, uri, favorite)
        }
    }

    companion object {

        const val MINUTES_AFTER_DEFAULT = 65

        const val MINUTES_AFTER_INCREMENT = 60

        const val MINUTES_AFTER_MAX = 1440
    }
}
