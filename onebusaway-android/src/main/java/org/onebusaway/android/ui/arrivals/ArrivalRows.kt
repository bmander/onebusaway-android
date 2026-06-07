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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.Status
import org.onebusaway.android.ui.ArrivalInfo
import org.onebusaway.util.comparators.AlphanumComparator

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

/** A single flat arrival row (Style A): route badge, headsign, status, and ETA. */
@Composable
fun ArrivalRowStyleA(arrival: ArrivalInfo, modifier: Modifier = Modifier) {
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

/** A card grouping all upcoming arrivals for one route + headsign (Style B). */
@Composable
fun ArrivalCardStyleB(group: List<ArrivalInfo>, modifier: Modifier = Modifier) {
    val first = group.first()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(16.dp)) {
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

@Composable
private fun StatusText(arrival: ArrivalInfo) {
    Text(
        text = arrival.statusText.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = colorResource(arrival.color)
    )
}

@Composable
private fun EtaBlock(arrival: ArrivalInfo) {
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
