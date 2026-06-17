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
package org.onebusaway.android.map.compose

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.Occupancy
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.request.ObaTripsForRouteResponse
import org.onebusaway.android.map.render.VehicleBitmaps
import org.onebusaway.android.util.ArrivalInfoUtils
import org.onebusaway.android.util.MyTextUtils
import org.onebusaway.android.util.getRouteDisplayName
import java.util.concurrent.TimeUnit

// Map info windows are rendered on a white bubble regardless of app theme, so all text uses fixed
// dark colors rather than theme-aware ones (see the project's info-window color guidance).
private val InfoPrimary = Color(0xDE000000)
private val InfoSecondary = Color(0x99000000)

/**
 * The vehicle marker info-window content (shared across map flavors): route + headsign, a
 * schedule-deviation status chip, occupancy silhouettes, the last-updated line, and the "more info"
 * chevron. Rendered as the content of the Google `MarkerInfoWindow` and of the MapLibre info-window
 * `ComposeView`.
 */
@Composable
fun VehicleInfoWindow(status: ObaTripStatus, response: ObaTripsForRouteResponse) {
    val res = LocalContext.current.resources
    val trip = response.getTrip(status.activeTripId)
    val route = response.getRoute(trip.routeId)
    val realtime = VehicleBitmaps.isLocationRealtime(status)
    val deviationMin = TimeUnit.SECONDS.toMinutes(status.scheduleDeviation)

    Column(
        modifier = Modifier
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .widthIn(max = 280.dp)
    ) {
        Text(
            text = getRouteDisplayName(route) + " " +
                stringResource(R.string.trip_info_separator) + " " +
                MyTextUtils.formatDisplayText(trip.headsign),
            color = InfoPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = if (realtime) {
                ArrivalInfoUtils.computeArrivalLabelFromDelay(res, deviationMin)
            } else {
                stringResource(R.string.stop_info_scheduled)
            }
            val chipColor = if (realtime) {
                colorResource(ArrivalInfoUtils.computeColorFromDeviation(deviationMin))
            } else {
                colorResource(R.color.stop_info_scheduled_time)
            }
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(chipColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )

            val dots = if (realtime) occupancyDots(status.occupancyStatus) else 0
            if (dots > 0) {
                Spacer(Modifier.width(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    repeat(dots) {
                        Icon(
                            painter = painterResource(R.drawable.ic_occupancy),
                            contentDescription = null,
                            tint = colorResource(R.color.stop_info_occupancy),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = lastUpdatedText(res, realtime, status),
                color = InfoSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(R.drawable.ic_navigation_chevron_right),
                contentDescription = null,
                tint = InfoSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Number of occupancy silhouettes to show (0–3) for the vehicle's occupancy level. */
private fun occupancyDots(occupancy: Occupancy?): Int = when (occupancy) {
    null, Occupancy.EMPTY -> 0
    Occupancy.MANY_SEATS_AVAILABLE -> 1
    Occupancy.FEW_SEATS_AVAILABLE, Occupancy.STANDING_ROOM_ONLY -> 2
    Occupancy.CRUSHED_STANDING_ROOM_ONLY, Occupancy.FULL, Occupancy.NOT_ACCEPTING_PASSENGERS -> 3
}

private fun lastUpdatedText(res: Resources, realtime: Boolean, status: ObaTripStatus): String {
    if (!realtime) {
        return res.getString(R.string.vehicle_last_updated_scheduled)
    }
    val now = System.currentTimeMillis()
    val last = if (status.lastLocationUpdateTime != 0L) {
        status.lastLocationUpdateTime
    } else {
        status.lastUpdateTime
    }
    val elapsedSec = TimeUnit.MILLISECONDS.toSeconds(now - last)
    return if (elapsedSec < 60) {
        res.getString(R.string.vehicle_last_updated_sec, elapsedSec)
    } else {
        res.getString(
            R.string.vehicle_last_updated_min_and_sec,
            TimeUnit.SECONDS.toMinutes(elapsedSec), elapsedSec % 60
        )
    }
}
