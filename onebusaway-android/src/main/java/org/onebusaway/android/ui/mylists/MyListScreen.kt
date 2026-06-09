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
package org.onebusaway.android.ui.mylists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.ListUiState
import org.onebusaway.android.ui.compose.components.LineBadge
import org.onebusaway.android.ui.compose.components.LoadingContent
import org.onebusaway.android.ui.compose.theme.ObaTheme

/** A labelled action in a row's overflow (⋮) menu. */
data class RowAction(val label: String, val onClick: () -> Unit)

/**
 * The body of a My-tab list (it lives inside the tab activity's toolbar, so there's no [Scaffold]):
 * a centered spinner while loading, the [itemContent] rows, or [emptyText] centered when a load
 * returns nothing. Content-provider reads don't surface errors to the user, so [ListUiState.Error]
 * also falls back to [emptyText].
 */
@Composable
fun <T> MyListContent(
    state: ListUiState<T>,
    emptyText: String,
    itemKey: (T) -> Any,
    itemContent: @Composable (T) -> Unit
) {
    // A Surface so descendants inherit onSurface as their content color (without it, text with no
    // explicit color falls back to the LocalContentColor default of black — invisible on a dark list).
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            when (state) {
                ListUiState.Loading -> LoadingContent(Modifier.align(Alignment.Center))

                is ListUiState.Success -> if (state.items.isEmpty()) {
                    EmptyText(emptyText)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(state.items, key = { _, item -> itemKey(item) }) { index, item ->
                            itemContent(item)
                            if (index < state.items.lastIndex) HorizontalDivider()
                        }
                    }
                }

                ListUiState.Error -> EmptyText(emptyText)
            }
        }
    }
}

@Composable
private fun BoxScope.EmptyText(text: String) {
    Text(
        text = text,
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * A stop row: a leading favorite star (when also starred), the bold name over its long-form
 * direction. Matching the legacy list, there's no visible overflow button — the per-item [actions]
 * are reached via a long-press context menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StopRow(item: StopListItem, onClick: () -> Unit, actions: List<RowAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (actions.isEmpty()) null else ({ expanded = true })
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FavoriteStarSlot(item.isFavorite)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                item.directionText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        RowActionsMenu(expanded, actions) { expanded = false }
    }
}

/**
 * A route row: the center-justified route-number badge on the left (its text shrinks to fit longer
 * short names), the long name beside it. Like the legacy list there's no overflow button — the
 * per-item [actions] are reached via a long-press context menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RouteRow(item: RouteListItem, onClick: () -> Unit, actions: List<RowAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (actions.isEmpty()) null else ({ expanded = true })
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LineBadge(item.shortName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                item.longName?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        RowActionsMenu(expanded, actions) { expanded = false }
    }
}

/** A fixed-width leading slot holding the favorite star (or empty), so row content stays aligned. */
@Composable
private fun FavoriteStarSlot(favorite: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (favorite) {
            Icon(
                painter = painterResource(R.drawable.ic_toggle_star),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.size(23.dp)
            )
        }
    }
}

/** The dropdown of [actions], shared by the stop and route long-press menus. */
@Composable
private fun RowActionsMenu(expanded: Boolean, actions: List<RowAction>, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                onClick = {
                    onDismiss()
                    action.onClick()
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Preview — a few route rows showing the badge: a short number, the two-line "G Line", a long name.

@Preview(showBackground = true)
@Composable
private fun RouteRowPreview() {
    ObaTheme {
        // Mirror the real host: rows live inside MyListContent's Surface, which sets the content color.
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                listOf(
                    RouteListItem("1", "G Line", "G-Line Rapid Ride", null),
                    RouteListItem("2", "12", "Interlaken Park Via 19th Ave", null),
                    RouteListItem("3", "8", "S to S Evt Frwy Station - N to Everett Station", "http://x"),
                    RouteListItem("4", "225", "Sheridan Park", null)
                ).forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider()
                    RouteRow(item, onClick = {}, actions = emptyList())
                }
            }
        }
    }
}
