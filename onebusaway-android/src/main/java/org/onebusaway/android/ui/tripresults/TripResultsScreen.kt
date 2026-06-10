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
package org.onebusaway.android.ui.tripresults

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.components.LoadingContent
import org.onebusaway.android.ui.compose.theme.ObaTheme

/**
 * The results header: the (1–3) itinerary option cards plus the list/map tab row. Rendered in its
 * own ComposeView above the directions/map frame so the native map can occupy the frame while these
 * stay visible. Empty until the first [TripResultsUiState.Success].
 */
@Composable
fun TripResultsHeader(
    state: TripResultsUiState,
    onSelectOption: (Int) -> Unit,
    onTabSelected: (showMap: Boolean) -> Unit
) {
    val success = state as? TripResultsUiState.Success ?: return
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            success.options.forEachIndexed { index, option ->
                OptionCard(
                    option = option,
                    selected = index == success.selectedIndex,
                    onClick = { onSelectOption(index) }
                )
            }
        }
        val selectedTab = if (success.showMap) 1 else 0
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { onTabSelected(false) },
                text = { Text(stringResource(R.string.trip_plan_list_view)) },
                icon = { Icon(painterResource(R.drawable.ic_list), contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { onTabSelected(true) },
                text = { Text(stringResource(R.string.trip_plan_map_view)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrivals_styleb_action_map),
                        contentDescription = null
                    )
                }
            )
        }
    }
}

@Composable
private fun RowScope.OptionCard(
    option: ItineraryOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = colorResource(
        if (selected) R.color.trip_plan_card_background_selected else R.color.trip_plan_card_background
    )
    val textColor = colorResource(
        if (selected) R.color.trip_plan_header_text_selected else R.color.trip_plan_header_text
    )
    Surface(
        color = background,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                text = option.title,
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(option.durationText, style = MaterialTheme.typography.bodyMedium, color = textColor)
            Text(option.intervalText, style = MaterialTheme.typography.bodyMedium, color = textColor)
        }
    }
}

/**
 * The directions list (or the loading/error state). Rendered in the frame the native map shares; the
 * host hides this and shows the map when the map tab is selected.
 */
@Composable
fun TripResultsList(state: TripResultsUiState) {
    Box(
        Modifier
            .fillMaxSize()
            .background(colorResource(R.color.md_theme_surfaceContainer))
    ) {
        when (state) {
            TripResultsUiState.Loading -> LoadingContent(Modifier.align(Alignment.Center))

            is TripResultsUiState.Error -> Text(
                text = state.message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
            )

            is TripResultsUiState.Success -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(state.directions) { _, item ->
                    DirectionRow(item)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DirectionRow(item: DirectionItem) {
    var expanded by remember { mutableStateOf(false) }
    val hasSubItems = item.subItems.isNotEmpty()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasSubItems) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            DirectionIcon(item.iconRes)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (item.isTransit) FontWeight.Medium else FontWeight.Normal
                )
                item.placeAndHeadsign?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item.agency?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item.extra?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (hasSubItems) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (expanded) {
            item.subItems.forEach { sub -> SubDirectionRow(sub) }
        }
    }
}

@Composable
private fun SubDirectionRow(item: DirectionItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DirectionIcon(item.iconRes)
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/** A 24dp step icon (gray-tinted, matching the legacy adapter), or blank space to keep alignment. */
@Composable
private fun DirectionIcon(iconRes: Int) {
    if (iconRes != DirectionItem.NO_ICON) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = colorResource(R.color.trip_option_icon_tint),
            modifier = Modifier.size(24.dp)
        )
    } else {
        Spacer(Modifier.size(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun TripResultsPreview() {
    ObaTheme {
        val state = TripResultsUiState.Success(
            options = listOf(
                ItineraryOption("Route 8", "32 min", "3:45p - 4:17p"),
                ItineraryOption("Route 48", "41 min", "3:50p - 4:31p")
            ),
            selectedIndex = 0,
            directions = listOf(
                DirectionItem(NO_ICON_PREVIEW, "1. Walk to Pine St & 3rd Ave"),
                DirectionItem(
                    NO_ICON_PREVIEW,
                    "2. Route 8 3:52p",
                    placeAndHeadsign = "Toward Rainier Beach",
                    agency = "Metro Transit",
                    isTransit = true,
                    subItems = listOf(DirectionItem(NO_ICON_PREVIEW, "Capitol Hill Station"))
                )
            ),
            showMap = false
        )
        Column {
            TripResultsHeader(state, onSelectOption = {}, onTabSelected = {})
            TripResultsList(state)
        }
    }
}

private const val NO_ICON_PREVIEW = -1
