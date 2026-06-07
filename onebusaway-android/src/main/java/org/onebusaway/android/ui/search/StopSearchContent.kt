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
package org.onebusaway.android.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onebusaway.android.R
import org.onebusaway.android.util.UIUtils

/** The stop search tab: search box + results, with a long-press menu per row. */
@Composable
fun StopSearchContent(
    viewModel: SearchViewModel<StopSearchResult>,
    shortcutMode: Boolean,
    onStopClick: (StopSearchResult) -> Unit,
    onShowOnMap: (StopSearchResult) -> Unit
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(
        query = query,
        onQueryChange = viewModel::onQueryChange,
        searchHint = stringResource(R.string.search_stop_hint),
        idleHint = stringResource(R.string.find_hint_nofavoritestops),
        state = state,
        itemKey = { it.id }
    ) { stop ->
        StopSearchRow(
            stop = stop,
            shortcutMode = shortcutMode,
            onStopClick = onStopClick,
            onShowOnMap = onShowOnMap
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StopSearchRow(
    stop: StopSearchResult,
    shortcutMode: Boolean,
    onStopClick: (StopSearchResult) -> Unit,
    onShowOnMap: (StopSearchResult) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onStopClick(stop) },
                    onLongClick = { menuExpanded = true }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (stop.isFavorite) {
                Icon(
                    painter = painterResource(R.drawable.ic_toggle_star),
                    contentDescription = stringResource(R.string.stop_info_favorite),
                    tint = colorResource(R.color.navdrawer_icon_tint)
                )
                Spacer(Modifier.width(8.dp))
            }
            Column {
                Text(stop.name, style = MaterialTheme.typography.bodyLarge)
                val direction = stringResource(UIUtils.getStopDirectionText(stop.direction))
                if (direction.isNotEmpty()) {
                    Text(
                        text = direction,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            Text(
                text = stop.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (shortcutMode) {
                                R.string.my_context_create_shortcut
                            } else {
                                R.string.my_context_get_stop_info
                            }
                        )
                    )
                },
                onClick = {
                    menuExpanded = false
                    onStopClick(stop)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.my_context_showonmap)) },
                onClick = {
                    menuExpanded = false
                    onShowOnMap(stop)
                }
            )
        }
    }
}
