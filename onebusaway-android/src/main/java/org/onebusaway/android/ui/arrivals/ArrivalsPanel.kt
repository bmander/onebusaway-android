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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onebusaway.android.R
import org.onebusaway.android.ui.ArrivalInfo
import org.onebusaway.android.util.ArrivalInfoUtils
import org.onebusaway.android.util.UIUtils

/**
 * The arrivals content for HomeActivity's map slide-up panel: a compact header (stop name,
 * direction, favorite, alert/filter indicators, expand/collapse chevron) plus, when [collapsed],
 * up to two "preferred" arrivals as a glanceable preview; when expanded, the full scrollable list.
 *
 * The panel state ([collapsed]) and its peek height are driven by the host: this composable reports
 * the preferred-arrival count + filter state via [onPreferredHeight] so the host can size the peek
 * (matching the legacy 50/114/154 dp header). Polling, callbacks, and the list are shared with the
 * standalone screen.
 */
@Composable
fun ArrivalsPanel(
    viewModel: ArrivalsViewModel,
    listState: LazyListState,
    collapsed: Boolean,
    initialTitle: String,
    handler: ArrivalActionHandler,
    onToggleExpand: () -> Unit,
    onPreferredHeight: (previewCount: Int, filtering: Boolean) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ArrivalsPolling(viewModel)
    val rowCallbacks = rememberArrivalRowCallbacks(handler, viewModel)
    val content = state as? ArrivalsUiState.Content

    val previewArrivals = remember(content?.arrivals) {
        val arrivals = content?.arrivals ?: return@remember emptyList<ArrivalInfo>()
        ArrivalInfoUtils.findPreferredArrivalIndexes(ArrayList(arrivals))
            ?.mapNotNull { arrivals.getOrNull(it) }
            .orEmpty()
    }
    val filtering = (content?.filteredRouteCount ?: 0) > 0
    LaunchedEffect(previewArrivals.size, filtering) {
        onPreferredHeight(previewArrivals.size, filtering)
    }

    var showFilterDialog by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ArrivalsPanelHeader(
                title = content?.header?.name?.takeIf { it.isNotEmpty() } ?: initialTitle,
                direction = content?.header?.direction,
                isFavorite = content?.header?.isFavorite == true,
                showActions = content != null,
                hasAlerts = content?.alerts?.isNotEmpty() == true,
                filtering = filtering,
                collapsed = collapsed,
                onToggleExpand = onToggleExpand,
                onToggleFavorite = viewModel::toggleFavorite,
                onFilter = { showFilterDialog = true },
                onStopDetails = handler::onShowStopDetails,
                onReportStopProblem = handler::onReportStopProblem,
                onHideAlerts = viewModel::hideAllAlerts
            )
            when {
                content == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                collapsed -> previewArrivals.forEach { arrival ->
                    ArrivalRowStyleA(
                        arrival = arrival,
                        actions = content.actions[arrival.info.tripId],
                        filterActive = filtering,
                        callbacks = rowCallbacks
                    )
                }
                else -> {
                    HorizontalDivider()
                    ArrivalsList(
                        content = content,
                        rowCallbacks = rowCallbacks,
                        handler = handler,
                        onLoadMore = viewModel::loadMore,
                        onShowAllRoutes = viewModel::showAllRoutes,
                        onShowHiddenAlerts = viewModel::showHiddenAlerts,
                        modifier = Modifier.weight(1f),
                        listState = listState
                    )
                }
            }
        }
    }

    if (showFilterDialog && content != null) {
        RouteFilterDialog(
            options = content.routeFilterOptions,
            onDismiss = { showFilterDialog = false },
            onSave = {
                viewModel.setRouteFilter(it)
                showFilterDialog = false
            }
        )
    }
}

@Composable
private fun ArrivalsPanelHeader(
    title: String,
    direction: String?,
    isFavorite: Boolean,
    showActions: Boolean,
    hasAlerts: Boolean,
    filtering: Boolean,
    collapsed: Boolean,
    onToggleExpand: () -> Unit,
    onToggleFavorite: () -> Unit,
    onFilter: () -> Unit,
    onStopDetails: () -> Unit,
    onReportStopProblem: () -> Unit,
    onHideAlerts: () -> Unit
) {
    val chevronRotation by animateFloatAsState(if (collapsed) 0f else 180f, label = "chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            val directionText = direction?.let { stringResource(UIUtils.getStopDirectionText(it)) }
            if (!directionText.isNullOrEmpty()) {
                Text(
                    text = directionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (filtering) {
            Icon(
                painter = painterResource(R.drawable.ic_content_filter_list),
                contentDescription = stringResource(R.string.stop_info_option_filter),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (hasAlerts) {
            Icon(
                painter = painterResource(R.drawable.baseline_warning_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        }
        if (showActions) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    painter = painterResource(
                        if (isFavorite) R.drawable.ic_toggle_star else R.drawable.ic_toggle_star_outline
                    ),
                    contentDescription = stringResource(R.string.stop_info_favorite),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            OverflowMenu(
                onFilter = onFilter,
                onStopDetails = onStopDetails,
                onReportStopProblem = onReportStopProblem,
                onHideAlerts = onHideAlerts
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_navigation_expand_more),
            contentDescription = stringResource(R.string.stop_header_sliding_panel_collapsed),
            modifier = Modifier.rotate(chevronRotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
