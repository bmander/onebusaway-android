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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.onebusaway.android.R
import org.onebusaway.android.util.BuildFlavorUtils
import org.onebusaway.android.util.UIUtils
import org.onebusaway.android.ui.compose.components.LoadingContent

/** Refresh interval matching the legacy ArrivalsListFragment (fixed 60s, not the server value). */
private const val REFRESH_PERIOD_MS = 60_000L

/**
 * Stateful entry point. The polling loop lives here so it follows the activity lifecycle:
 * polling runs only while RESUMED (cancelled on pause, like the legacy Handler), and refreshes
 * immediately on resume if the 60s window already elapsed.
 *
 * @param initialTitle stop name from the launching intent, shown until the first load lands
 */
@Composable
fun ArrivalsRoute(
    viewModel: ArrivalsViewModel,
    initialTitle: String,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Refresh immediately if the 60s timer would already have fired (legacy onResume)
            val sinceLast = System.currentTimeMillis() - viewModel.lastResponseTimeMs
            delay((REFRESH_PERIOD_MS - sinceLast).coerceIn(0L, REFRESH_PERIOD_MS))
            while (isActive) {
                viewModel.refresh()
                delay(REFRESH_PERIOD_MS)
            }
        }
    }
    ArrivalsScreen(
        state = state,
        initialTitle = initialTitle,
        onBack = onBack,
        onRefresh = viewModel::manualRefresh,
        onToggleFavorite = viewModel::toggleFavorite,
        onLoadMore = viewModel::loadMore
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrivalsScreen(
    state: ArrivalsUiState,
    initialTitle: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLoadMore: () -> Unit
) {
    val content = state as? ArrivalsUiState.Content
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(content?.header?.name?.takeIf { it.isNotEmpty() } ?: initialTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up)
                        )
                    }
                },
                actions = {
                    if (content != null) {
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                painter = painterResource(
                                    if (content.header.isFavorite) {
                                        R.drawable.ic_toggle_star
                                    } else {
                                        R.drawable.ic_toggle_star_outline
                                    }
                                ),
                                contentDescription = stringResource(R.string.stop_info_favorite),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_navigation_refresh),
                            contentDescription = stringResource(R.string.region_option_refresh),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (state) {
                ArrivalsUiState.Loading -> LoadingContent(Modifier.align(Alignment.Center))

                is ArrivalsUiState.Content -> ArrivalsList(state, onLoadMore)

                is ArrivalsUiState.Error -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                )
            }
        }
    }
}

@Composable
private fun ArrivalsList(content: ArrivalsUiState.Content, onLoadMore: () -> Unit) {
    val useCards = content.style == BuildFlavorUtils.ARRIVAL_INFO_STYLE_B &&
        UIUtils.canSupportArrivalInfoStyleB()
    // Sorting/grouping is non-trivial; keep it off the recomposition path
    val groups = remember(content.arrivals, useCards) {
        if (useCards) groupForStyleB(content.arrivals) else emptyList()
    }
    LazyColumn(Modifier.fillMaxSize()) {
        content.header.direction?.let { direction ->
            item(key = "direction") { DirectionLine(direction) }
        }
        if (content.arrivals.isEmpty()) {
            item(key = "empty") { EmptyArrivals(content.minutesAfter) }
        } else if (useCards) {
            items(groups, key = { it.first().info.run { "$routeId:$headsign" } }) { group ->
                ArrivalCardStyleB(group)
            }
        } else {
            items(content.arrivals, key = { it.info.tripId }) { arrival ->
                ArrivalRowStyleA(arrival)
            }
        }
        item(key = "load_more") {
            TextButton(
                onClick = onLoadMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.stop_info_load_more_arrivals))
            }
        }
    }
}

@Composable
private fun DirectionLine(direction: String) {
    val directionText = stringResource(UIUtils.getStopDirectionText(direction))
    if (directionText.isNotEmpty()) {
        Text(
            text = directionText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun EmptyArrivals(minutesAfter: Int) {
    val context = LocalContext.current
    Text(
        text = UIUtils.getNoArrivalsMessage(context, minutesAfter, false, false),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
    )
}
