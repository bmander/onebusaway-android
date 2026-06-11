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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onebusaway.android.R
import org.onebusaway.android.ui.ArrivalInfo
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.util.ArrivalInfoUtils

/**
 * The arrivals content for HomeActivity's map slide-up panel. Unlike the standalone screen, the
 * drawer is laid out top-to-bottom as: the arrivals (a compact 2-row peek when [collapsed], the full
 * scrollable list when expanded), then the stop header pinned at the bottom with the expand/collapse
 * chevron — matching the legacy ArrivalsListHeader. The hosting BottomSheetScaffold supplies the drag
 * handle above this content.
 *
 * The peek height is driven by the host: this composable reports the preferred-arrival count +
 * filter state via [onPreferredHeight] so the host can size the collapsed panel. Polling, callbacks,
 * the per-arrival menu, and the expanded list are shared with the standalone screen.
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

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // The collapsed peek — up to two preferred arrivals — stays exactly as-is when the
            // panel is pulled up (the legacy ArrivalsListHeader behavior).
            if (content == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                previewArrivals.forEachIndexed { index, arrival ->
                    if (index > 0) PeekDivider()
                    PeekRow(
                        arrival = arrival,
                        actions = content.actions[arrival.info.tripId],
                        filterActive = filtering,
                        callbacks = rowCallbacks
                    )
                }
            }
            ArrivalsPanelHeader(
                title = content?.header?.name?.takeIf { it.isNotEmpty() } ?: initialTitle,
                direction = content?.header?.direction,
                isFavorite = content?.header?.isFavorite == true,
                showActions = content != null,
                hasAlerts = content?.alerts?.isNotEmpty() == true,
                filtering = filtering,
                collapsed = collapsed,
                onToggleExpand = onToggleExpand,
                onToggleFavorite = viewModel::toggleFavorite
            )
            // The full standalone-style list sits below the peek + header and is revealed as the
            // panel slides up. It's always composed (not gated on `collapsed`, which only flips at
            // the settle points), so the reveal tracks the drag; when collapsed the panel height
            // clips it away below the header.
            if (content != null) {
                ArrivalsList(
                    content = content,
                    rowCallbacks = rowCallbacks,
                    handler = handler,
                    onLoadMore = viewModel::loadMore,
                    onShowAllRoutes = viewModel::showAllRoutes,
                    onShowHiddenAlerts = viewModel::showHiddenAlerts,
                    modifier = Modifier.weight(1f),
                    listState = listState,
                    // The drawer header already shows the direction as a "(N)" tag.
                    showDirection = false
                )
            }
        }
    }
}

/** A row separator that's a touch thicker and inset from both edges (spans ~90% of the width). */
@Composable
private fun ColumnScope.PeekDivider() {
    HorizontalDivider(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .fillMaxWidth(0.9f),
        thickness = 2.dp
    )
}


/** Adapts an [ArrivalInfo] onto [PeekRowVisual], wiring the favorite star and per-arrival menu. */
@Composable
private fun PeekRow(
    arrival: ArrivalInfo,
    actions: ArrivalActions?,
    filterActive: Boolean,
    callbacks: ArrivalRowCallbacks
) {
    var expanded by remember { mutableStateOf(false) }
    PeekRowVisual(
        shortName = arrival.info.shortName.orEmpty(),
        headsign = arrival.info.headsign.orEmpty(),
        eta = arrival.eta,
        etaColor = colorResource(arrival.color),
        predicted = arrival.predicted,
        isFavorite = actions?.isRouteFavorite == true,
        onFavorite = { actions?.let { callbacks.onRouteFavorite(it) } },
        onMore = { expanded = true },
        menu = {
            ArrivalActionsMenu(expanded, { expanded = false }, arrival, actions, filterActive, callbacks)
        }
    )
}

/**
 * A single drawer peek row, driven by primitives so it's previewable: a full-height favorite star,
 * the route short name and destination in line, a white-on-lateness ETA pill, and a full-size
 * overflow menu — matching the legacy ArrivalsListHeader eta rows.
 */
@Composable
private fun PeekRowVisual(
    shortName: String,
    headsign: String,
    eta: Long,
    etaColor: Color,
    predicted: Boolean,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onFavorite) {
            Icon(
                painter = painterResource(
                    if (isFavorite) R.drawable.ic_toggle_star else R.drawable.ic_toggle_star_outline
                ),
                contentDescription = stringResource(
                    if (isFavorite) R.string.bus_options_menu_remove_star
                    else R.string.bus_options_menu_add_star
                ),
                tint = colorResource(R.color.navdrawer_icon_tint),
                modifier = Modifier.size(28.dp)
            )
        }
        Text(text = shortName, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Text(
            text = headsign,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        EtaPill(eta, etaColor, predicted)
        Box {
            IconButton(onClick = onMore) {
                Icon(
                    painter = painterResource(R.drawable.ic_navigation_more_vert),
                    contentDescription = stringResource(R.string.stop_info_item_options_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            menu()
        }
    }
}

/** The prominent white-on-lateness ETA pill shown in each drawer peek row. */
@Composable
private fun EtaPill(eta: Long, color: Color, predicted: Boolean) {
    Surface(shape = RoundedCornerShape(8.dp), color = color) {
        // Fixed height + centered content so "NOW" and "21 min" pills render the same height.
        Box(
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (eta != 0L) {
                    Text(
                        text = eta.toString(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                // The trailing label ("min" / "Now") with the radiating real-time indicator at its
                // upper-right: top-aligning this inner row floats the small indicator to the label's
                // top. The Box is always present so the pill width is stable whether or not it's live.
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = if (eta == 0L) {
                            stringResource(R.string.stop_info_eta_now)
                        } else {
                            " " + stringResource(R.string.minutes_abbreviation)
                        },
                        fontSize = if (eta == 0L) 22.sp else 14.sp,
                        fontWeight = if (eta == 0L) FontWeight.Bold else FontWeight.Normal,
                        color = Color.White
                    )
                    Box(Modifier.padding(start = 2.dp).size(8.dp)) {
                        if (predicted) {
                            RealtimeIndicator(color = Color.White, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/**
 * The stop header pinned at the bottom of the panel: the favorite star and stop name (with a
 * compass-direction tag appended, e.g. "Pine St & 3rd Ave (N)") as one centered unit, any
 * filter/alert indicators plus the expand/collapse chevron right-justified. Tapping the row toggles
 * the panel. [starSize]/[chevronSize] are exposed so the icon sizing can be tuned in the preview.
 */
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
    starSize: Dp = 20.dp,
    chevronSize: Dp = 18.dp
) {
    val chevronRotation by animateFloatAsState(if (collapsed) 0f else 180f, label = "chevron")
    val name = if (!direction.isNullOrBlank()) "$title (${direction.trim()})" else title
    Box(
        Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() }
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        // Star + name as one centered unit (kept clear of the right-justified chevron).
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showActions) {
                Icon(
                    painter = painterResource(
                        if (isFavorite) R.drawable.ic_toggle_star else R.drawable.ic_toggle_star_outline
                    ),
                    contentDescription = stringResource(R.string.stop_info_favorite),
                    tint = colorResource(R.color.navdrawer_icon_tint),
                    modifier = Modifier
                        .clickable(onClick = onToggleFavorite)
                        .padding(end = 6.dp)
                        .size(starSize)
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Filter/alert indicators + chevron, right-justified.
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            Icon(
                painter = painterResource(R.drawable.ic_navigation_expand_more),
                contentDescription = stringResource(R.string.stop_header_sliding_panel_collapsed),
                modifier = Modifier
                    .rotate(chevronRotation)
                    .size(chevronSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews — the collapsed drawer and its peek row, rendered from primitives.

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun DrawerCollapsedPreview() {
    ObaTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                PeekRowVisual(
                    shortName = "12",
                    headsign = "Interlaken Park Via 19th Ave E",
                    eta = 19,
                    etaColor = colorResource(R.color.stop_info_delayed),
                    predicted = true,
                    isFavorite = false,
                    onFavorite = {},
                    onMore = {}
                )
                PeekDivider()
                PeekRowVisual(
                    shortName = "12",
                    headsign = "Interlaken Park Via 19th Ave E",
                    eta = 21,
                    etaColor = colorResource(R.color.stop_info_delayed),
                    predicted = true,
                    isFavorite = false,
                    onFavorite = {},
                    onMore = {}
                )
                ArrivalsPanelHeader(
                    title = "19th Ave E & E Republican St",
                    direction = "N",
                    isFavorite = false,
                    showActions = true,
                    hasAlerts = false,
                    filtering = false,
                    collapsed = true,
                    onToggleExpand = {},
                    onToggleFavorite = {},
                    // Tune these in the preview to dial in the star / chevron sizing
                    starSize = 20.dp,
                    chevronSize = 18.dp
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun DrawerPeekRowPreview() {
    ObaTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                PeekRowVisual("8", "Mount Baker Transit Center", 1, colorResource(R.color.stop_info_delayed), true, true, {}, {})
                PeekDivider()
                PeekRowVisual("40", "Downtown Seattle", 0, colorResource(R.color.stop_info_ontime), true, false, {}, {})
                PeekDivider()
                PeekRowVisual("550", "Bellevue Transit Center", 28, colorResource(R.color.stop_info_scheduled_time), false, false, {}, {})
            }
        }
    }
}
