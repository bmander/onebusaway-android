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
package org.onebusaway.android.ui.tripplan

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.directions.util.OTPConstants
import org.onebusaway.android.ui.tripresults.TripResultsFragment
import org.opentripplanner.api.model.Itinerary

private const val RESULTS_TAG = "TripResults"

/**
 * The trip-plan container: the [TripPlanForm] is the main content; when a plan completes, the
 * results appear in a Material3 bottom sheet. Because the results screen owns the native map, it
 * stays a Fragment ([TripResultsFragment]) hosted via [FragmentContainerView]. Date/time/contacts/
 * current-location/advanced/report are platform interactions delegated to the host Activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripPlanRoute(
    viewModel: TripPlanViewModel,
    fragmentManager: FragmentManager,
    onBack: () -> Unit,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onFromCurrentLocation: () -> Unit,
    onToCurrentLocation: () -> Unit,
    onFromContacts: () -> Unit,
    onToContacts: () -> Unit,
    onFromPickOnMap: () -> Unit,
    onToPickOnMap: () -> Unit,
    onAdvancedSettings: () -> Unit,
    onReportProblem: () -> Unit
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val planState by viewModel.planState.collectAsStateWithLifecycle()

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false
        )
    )
    val hasResults = planState is PlanResult.Success
    val scope = rememberCoroutineScope()
    val sheetExpanded = scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded

    // Back (system or toolbar) collapses an expanded results sheet first, then exits — mirrors
    // the legacy sliding-panel behavior (onBackPressed collapsed the panel before finishing).
    val collapseOrBack: () -> Unit = {
        if (sheetExpanded) scope.launch { scaffoldState.bottomSheetState.partialExpand() }
        else onBack()
    }
    BackHandler(enabled = sheetExpanded) { collapseOrBack() }

    // Expand the sheet when results arrive; hide it when the form is reset to Idle.
    LaunchedEffect(planState) {
        when (planState) {
            is PlanResult.Success -> scaffoldState.bottomSheetState.expand()
            PlanResult.Idle -> scaffoldState.bottomSheetState.hide()
            else -> {}
        }
    }

    // The toolbar lives above the sheet (not in the scaffold's topBar slot) so the results sheet
    // only ever fills the area *below* the toolbar — the toolbar stays visible even when the sheet
    // is fully expanded, matching the legacy panel.
    Column(Modifier.fillMaxSize()) {
        TripPlanTopBar(onBack = collapseOrBack, onReportProblem = onReportProblem)
        BottomSheetScaffold(
            modifier = Modifier.weight(1f),
            scaffoldState = scaffoldState,
            sheetPeekHeight = if (hasResults) 220.dp else 0.dp,
            sheetContent = {
                val result = planState
                if (result is PlanResult.Success) {
                    ResultsFragmentHost(
                        itineraries = result.itineraries,
                        fragmentManager = fragmentManager,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                if (planState is PlanResult.Loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                TripPlanForm(
                    state = formState,
                    onFromQueryChange = viewModel::onFromQueryChange,
                    onToQueryChange = viewModel::onToQueryChange,
                    onSelectFrom = viewModel::setFrom,
                    onSelectTo = viewModel::setTo,
                    onFromCurrentLocation = onFromCurrentLocation,
                    onToCurrentLocation = onToCurrentLocation,
                    onFromContacts = onFromContacts,
                    onToContacts = onToContacts,
                    onFromPickOnMap = onFromPickOnMap,
                    onToPickOnMap = onToPickOnMap,
                    onSetArriving = viewModel::setArriving,
                    onPickDate = onPickDate,
                    onPickTime = onPickTime,
                    onReverse = viewModel::reverseTrip,
                    onAdvancedSettings = onAdvancedSettings
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripPlanTopBar(onBack: () -> Unit, onReportProblem: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.navigate_up)
                )
            }
        },
        actions = {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tripplanner_report_trip_problem)) },
                    onClick = {
                        menuExpanded = false
                        onReportProblem()
                    }
                )
            }
        }
    )
}

/**
 * Hosts the results [TripResultsFragment] (which owns the native map) inside Compose. The fragment
 * is (re)committed whenever the itineraries change; it reads them from its arguments, matching the
 * Phase A contract.
 */
@Composable
private fun ResultsFragmentHost(
    itineraries: List<Itinerary>,
    fragmentManager: FragmentManager,
    modifier: Modifier = Modifier
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { context ->
            FragmentContainerView(context).apply { id = R.id.trip_results_container }
        }
    )
    DisposableEffect(itineraries) {
        val fragment = TripResultsFragment().apply {
            arguments = Bundle().apply {
                putSerializable(OTPConstants.ITINERARIES, ArrayList(itineraries))
                putInt(OTPConstants.SELECTED_ITINERARY, 0)
                putBoolean(OTPConstants.SHOW_MAP, false)
            }
        }
        fragmentManager.commit(allowStateLoss = true) {
            replace(R.id.trip_results_container, fragment, RESULTS_TAG)
        }
        onDispose {
            if (!fragmentManager.isStateSaved) {
                fragmentManager.findFragmentByTag(RESULTS_TAG)?.let { existing ->
                    fragmentManager.commit(allowStateLoss = true) { remove(existing) }
                }
            }
        }
    }
}
