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
package org.onebusaway.android.ui.regions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.components.ErrorContent
import org.onebusaway.android.ui.compose.components.LoadingContent
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.RegionUtils

/**
 * Stateful entry point for the region picker screen: collects the ViewModel's state and wires
 * UI events back to it. Region selection is synchronous and terminal (the host navigates
 * away), so its result is delivered through the plain [onRegionSelected] callback.
 */
@Composable
fun RegionsRoute(
    viewModel: RegionsViewModel,
    onBack: () -> Unit,
    onRegionSelected: (autoSelectDisabled: Boolean) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RegionsScreen(
        state = state,
        onRetry = { viewModel.load() },
        onRefresh = { viewModel.load(refresh = true) },
        onRegionClick = { region -> onRegionSelected(viewModel.selectRegion(region)) },
        onBack = onBack
    )
}

/** Stateless screen content, fully driven by [RegionsUiState] — previewable and testable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionsScreen(
    state: RegionsUiState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onRegionClick: (RegionItem) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preferences_region_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up)
                        )
                    }
                },
                actions = {
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
                RegionsUiState.Loading -> LoadingContent(
                    Modifier.align(Alignment.Center)
                )

                is RegionsUiState.Success -> RegionList(state.regions, onRegionClick)

                RegionsUiState.Error -> ErrorContent(
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun RegionList(regions: List<RegionItem>, onRegionClick: (RegionItem) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(regions, key = { it.id }) { region ->
            RegionRow(region, onRegionClick)
        }
    }
}

@Composable
private fun RegionRow(region: RegionItem, onClick: (RegionItem) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(region) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // alpha(0f) (rather than omitting the icon) keeps rows aligned, like the legacy
        // layout's INVISIBLE check mark
        Icon(
            painter = painterResource(R.drawable.ic_checkmark_holo_light),
            contentDescription = if (region.isCurrent) {
                stringResource(R.string.checkmark_description)
            } else {
                null
            },
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.alpha(if (region.isCurrent) 1f else 0f)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(region.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = distanceText(region.distanceMeters),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Formats a distance in meters as miles or kilometers per the user's units preference
 * (matching the legacy picker), or "unavailable" when no distance is known.
 */
@Composable
private fun distanceText(distanceMeters: Float?): String {
    if (distanceMeters == null) {
        return stringResource(R.string.region_unavailable)
    }
    val context = LocalContext.current
    val metric = remember { PreferenceUtils.getUnitsAreMetricFromPreferences(context) }
    val format = remember { NumberFormat.getInstance().apply { maximumFractionDigits = 1 } }
    return if (metric) {
        val km = distanceMeters / 1000.0
        pluralStringResource(R.plurals.distance_kilometers, km.toInt(), format.format(km))
    } else {
        val miles = distanceMeters * RegionUtils.METERS_TO_MILES
        pluralStringResource(R.plurals.distance_miles, miles.toInt(), format.format(miles))
    }
}

@Preview(showBackground = true)
@Composable
private fun RegionsScreenSuccessPreview() {
    ObaTheme {
        RegionsScreen(
            state = RegionsUiState.Success(
                listOf(
                    RegionItem(1, "Puget Sound", 1500f, isCurrent = true),
                    RegionItem(2, "Tampa Bay", 4_500_000f, isCurrent = false),
                    RegionItem(3, "No-location Region", null, isCurrent = false)
                )
            ),
            onRetry = {}, onRefresh = {}, onRegionClick = {}, onBack = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RegionsScreenLoadingPreview() {
    ObaTheme {
        RegionsScreen(
            state = RegionsUiState.Loading,
            onRetry = {}, onRefresh = {}, onRegionClick = {}, onBack = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RegionsScreenErrorPreview() {
    ObaTheme {
        RegionsScreen(
            state = RegionsUiState.Error,
            onRetry = {}, onRefresh = {}, onRegionClick = {}, onBack = {}
        )
    }
}
