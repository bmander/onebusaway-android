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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.Status
import org.onebusaway.android.ui.ArrivalInfo
import org.onebusaway.util.comparators.AlphanumComparator

/**
 * The per-arrival menu actions (legacy `showListItemMenu`). Implemented by the host activity,
 * which has the Context/FragmentManager needed to launch the targets. The route-filter toggle is
 * a ViewModel action; the rest are navigation/dialogs.
 */
class ArrivalRowCallbacks(
    val onRouteFavorite: (ArrivalActions) -> Unit,
    val onShowVehiclesOnMap: (ArrivalInfo) -> Unit,
    val onShowTripStatus: (ArrivalInfo) -> Unit,
    val onSetReminder: (ArrivalInfo) -> Unit,
    val onShowOnlyRoute: (String) -> Unit,
    val onShowRouteSchedule: (String) -> Unit,
    val onReportArrivalProblem: (ArrivalActions) -> Unit
)

/**
 * Groups arrivals into per-(route, headsign) lists for the card style, matching the legacy
 * ArrivalsListAdapterStyleB: sort by route then headsign (alphanumeric), then collect runs.
 */
fun groupForStyleB(arrivals: List<ArrivalInfo>): List<List<ArrivalInfo>> {
    val comparator = AlphanumComparator()
    val sorted = arrivals.sortedWith { a, b ->
        val byRoute = comparator.compare(a.info.routeId, b.info.routeId)
        if (byRoute != 0) byRoute else comparator.compare(a.info.headsign.orEmpty(), b.info.headsign.orEmpty())
    }
    val groups = mutableListOf<MutableList<ArrivalInfo>>()
    for (arrival in sorted) {
        val current = groups.lastOrNull()
        if (current != null &&
            current[0].info.routeId == arrival.info.routeId &&
            current[0].info.headsign.orEmpty() == arrival.info.headsign.orEmpty()
        ) {
            current.add(arrival)
        } else {
            groups.add(mutableListOf(arrival))
        }
    }
    return groups
}

/** The visual content of a flat arrival row: route badge, headsign, status, and ETA. Shared by
 *  the interactive Style A row and the report-flow picker (which wraps it with its own click). */
@Composable
internal fun ArrivalRowContent(arrival: ArrivalInfo, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = arrival.info.shortName.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(64.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = arrival.info.headsign.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = canceledDecoration(arrival)
            )
            StatusText(arrival)
        }
        EtaBlock(arrival)
    }
}

/** A single flat arrival row (Style A): the row content plus a tap-to-open per-arrival menu. */
@Composable
fun ArrivalRowStyleA(
    arrival: ArrivalInfo,
    actions: ArrivalActions?,
    filterActive: Boolean,
    callbacks: ArrivalRowCallbacks,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ArrivalRowContent(arrival, modifier.clickable { expanded = true })
        ArrivalActionsMenu(expanded, { expanded = false }, arrival, actions, filterActive, callbacks)
    }
}

/** A card grouping all upcoming arrivals for one route + headsign (Style B). */
@Composable
fun ArrivalCardStyleB(
    group: List<ArrivalInfo>,
    actions: ArrivalActions?,
    filterActive: Boolean,
    callbacks: ArrivalRowCallbacks,
    modifier: Modifier = Modifier
) {
    val first = group.first()
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = first.info.shortName.orEmpty(),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = first.info.headsign.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = canceledDecoration(first)
                    )
                }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_navigation_more_vert),
                            contentDescription = stringResource(R.string.stop_info_item_options_title)
                        )
                    }
                    ArrivalActionsMenu(expanded, { expanded = false }, first, actions, filterActive, callbacks)
                }
            }
            group.forEachIndexed { index, arrival ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (index == 0) 8.dp else 0.dp)
                        // Subsequent arrivals are dimmed, matching the legacy card
                        .alpha(if (index == 0) 1f else 0.55f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusText(arrival)
                    EtaBlock(arrival)
                }
            }
        }
    }
}

/** The dropdown of per-arrival actions, gated like the legacy `UIUtils.buildTripOptions` (minus occupancy). */
@Composable
internal fun ArrivalActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    arrival: ArrivalInfo,
    actions: ArrivalActions?,
    filterActive: Boolean,
    callbacks: ArrivalRowCallbacks
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (actions != null) {
            val favLabel = if (actions.isRouteFavorite) {
                R.string.bus_options_menu_remove_star
            } else {
                R.string.bus_options_menu_add_star
            }
            MenuRow(favLabel) { onDismiss(); callbacks.onRouteFavorite(actions) }
        }
        MenuRow(R.string.bus_options_menu_show_vehicles_on_map) {
            onDismiss(); callbacks.onShowVehiclesOnMap(arrival)
        }
        MenuRow(R.string.bus_options_menu_show_trip_details) {
            onDismiss(); callbacks.onShowTripStatus(arrival)
        }
        MenuRow(R.string.bus_options_menu_set_reminder) {
            onDismiss(); callbacks.onSetReminder(arrival)
        }
        val filterLabel = if (filterActive) {
            R.string.bus_options_menu_show_all_routes
        } else {
            R.string.bus_options_menu_show_only_this_route
        }
        MenuRow(filterLabel) { onDismiss(); callbacks.onShowOnlyRoute(arrival.info.routeId) }
        val url = actions?.scheduleUrl
        if (!url.isNullOrBlank()) {
            MenuRow(R.string.bus_options_menu_show_route_schedule) {
                onDismiss(); callbacks.onShowRouteSchedule(url)
            }
        }
        if (actions != null) {
            MenuRow(R.string.bus_options_menu_report_trip_problem) {
                onDismiss(); callbacks.onReportArrivalProblem(actions)
            }
        }
    }
}

/** A dropdown item that just shows a string resource; shared by the per-arrival and overflow menus. */
@Composable
internal fun MenuRow(textRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(textRes)) }, onClick = onClick)
}

@Composable
internal fun StatusText(arrival: ArrivalInfo) {
    Text(
        text = arrival.statusText.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = colorResource(arrival.color)
    )
}

@Composable
internal fun EtaBlock(arrival: ArrivalInfo) {
    val eta = arrival.eta
    Row(verticalAlignment = Alignment.Bottom) {
        if (eta == 0L) {
            Text(stringResource(R.string.stop_info_eta_now), style = MaterialTheme.typography.titleLarge)
        } else {
            Text(
                text = eta.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = " " + stringResource(R.string.minutes_abbreviation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun canceledDecoration(arrival: ArrivalInfo): TextDecoration =
    if (arrival.status == Status.CANCELED) TextDecoration.LineThrough else TextDecoration.None
