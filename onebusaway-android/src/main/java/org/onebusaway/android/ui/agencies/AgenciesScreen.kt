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
package org.onebusaway.android.ui.agencies

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.components.ErrorContent
import org.onebusaway.android.ui.compose.components.LoadingContent
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.util.UIUtils

/**
 * Stateful entry point for the supported agencies screen: collects the ViewModel's state and
 * wires UI events back to it.
 */
@Composable
fun AgenciesRoute(viewModel: AgenciesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AgenciesScreen(
        state = state,
        onRetry = viewModel::load,
        onAgencyClick = { agency ->
            agency.url?.let { UIUtils.goToUrl(context, it) }
        },
        onBack = onBack
    )
}

/** Stateless screen content, fully driven by [AgenciesUiState] — previewable and testable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgenciesScreen(
    state: AgenciesUiState,
    onRetry: () -> Unit,
    onAgencyClick: (AgencyItem) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agencies_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up)
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
                AgenciesUiState.Loading -> LoadingContent(
                    Modifier.align(Alignment.Center)
                )

                is AgenciesUiState.Success -> if (state.agencies.isEmpty()) {
                    Text(
                        text = stringResource(R.string.agencies_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                    )
                } else {
                    AgencyList(state.agencies, onAgencyClick)
                }

                AgenciesUiState.Error -> ErrorContent(
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun AgencyList(agencies: List<AgencyItem>, onAgencyClick: (AgencyItem) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(agencies, key = { it.id }) { agency ->
            AgencyRow(agency, onAgencyClick)
        }
    }
}

@Composable
private fun AgencyRow(agency: AgencyItem, onClick: (AgencyItem) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = agency.url != null) { onClick(agency) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_maps_directions_bus),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(agency.name, style = MaterialTheme.typography.bodyLarge)
            if (agency.url != null) {
                Text(
                    text = agency.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AgenciesScreenSuccessPreview() {
    ObaTheme {
        AgenciesScreen(
            state = AgenciesUiState.Success(
                listOf(
                    AgencyItem("1", "King County Metro", "https://kingcounty.gov/metro"),
                    AgencyItem("40", "Sound Transit", "https://soundtransit.org"),
                    AgencyItem("97", "No-website Transit", null)
                )
            ),
            onRetry = {}, onAgencyClick = {}, onBack = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AgenciesScreenLoadingPreview() {
    ObaTheme {
        AgenciesScreen(state = AgenciesUiState.Loading, onRetry = {}, onAgencyClick = {}, onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AgenciesScreenErrorPreview() {
    ObaTheme {
        AgenciesScreen(state = AgenciesUiState.Error, onRetry = {}, onAgencyClick = {}, onBack = {})
    }
}
