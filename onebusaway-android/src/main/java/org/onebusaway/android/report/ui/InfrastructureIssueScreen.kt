/*
 * Copyright (C) 2014-2015 University of South Florida (sjbarbeau@gmail.com),
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
package org.onebusaway.android.report.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import edu.usf.cutr.open311client.Open311
import edu.usf.cutr.open311client.models.Service
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.io.elements.ObaStopElement
import org.onebusaway.android.map.MapMode
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.map.compose.ObaMap
import org.onebusaway.android.map.compose.ObaMapCallbacks
import org.onebusaway.android.report.ui.dialog.ReportSuccessDialog
import org.onebusaway.android.ui.HomeActivity
import org.onebusaway.android.ui.compose.components.ObaTopAppBar
import org.onebusaway.android.ui.compose.findActivity
import org.onebusaway.android.ui.report.infrastructure.DefaultGeocodeAddressRepository
import org.onebusaway.android.ui.report.infrastructure.DefaultIssueType
import org.onebusaway.android.ui.report.infrastructure.DefaultServiceListRepository
import org.onebusaway.android.ui.report.infrastructure.GeoPoint
import org.onebusaway.android.ui.report.infrastructure.InfrastructureControls
import org.onebusaway.android.ui.report.infrastructure.InfrastructureIssueEvent
import org.onebusaway.android.ui.report.infrastructure.InfrastructureIssueViewModel
import org.onebusaway.android.ui.report.infrastructure.IssueLocation
import org.onebusaway.android.ui.report.infrastructure.ReportTarget
import org.onebusaway.android.ui.report.open311.Open311ProblemFragment
import org.onebusaway.android.ui.report.problem.ProblemReportFragment

/** Host-intent extras carrying the opaque trip context for the InfrastructureIssue destination. */
const val EXTRA_TRIP_INFO = ".tripInfo"
const val EXTRA_AGENCY_NAME = ".agencyName"
const val EXTRA_BLOCK_ID = ".blockId"

/**
 * The infrastructure-issue (stop/trip problem) NavHost destination (Campaign C; former
 * [InfrastructureIssueActivity]). It replaces the `infrastructure_issue.xml` layout with a Compose
 * [Scaffold]: the declarative [ObaMap] (entry-scoped [MapViewModel], stop mode), the
 * [InfrastructureControls], and a [FragmentContainerView] (id `R.id.ri_report_stop_problem`) the
 * report form fragments are swapped into — exactly the View ids the fragments expect.
 *
 * The [InfrastructureIssueViewModel] is built once (back-stack-entry-scoped) from the nav-arg
 * `selectedService` plus the host intent extras (lat/lon, stop id/name/code, the opaque
 * `ObaArrivalInfo`, agency name, block id), reproducing the former Activity's hand-built factory. A
 * [DisposableEffect] publishes that VM to the host [HomeActivity] (which implements
 * [ReportProblemFragmentCallback], [SimpleArrivalsPickerFragment.Callback] and
 * [InfrastructureIssueHost]) so the fragments — hosted on the host activity's `supportFragmentManager`
 * — reach it through `getActivity()`, and clears it on dispose.
 */
@Composable
fun InfrastructureIssueDestination(
    navController: NavController,
    selectedService: String?,
) {
    val activity = LocalContext.current.findActivity() as HomeActivity
    val fragmentManager = activity.supportFragmentManager

    // Entry-scoped map view model (distinct from HomeActivity's own; this is a separate back-stack
    // entry). Stop mode so nearby stops load + are tappable, matching the former view-owning host.
    val mapViewModel = hiltViewModel<MapViewModel>()
    LaunchedEffect(Unit) {
        if (mapViewModel.currentMapMode == null) mapViewModel.setMode(MapMode.Stop)
    }

    // Build the InfrastructureIssueViewModel once, scoped to this back-stack entry (so its
    // viewModelScope is cancelled when the destination leaves). Reads selectedService from the
    // nav-arg and the rest of the context from the host intent — the former Activity's createViewModel.
    val viewModel: InfrastructureIssueViewModel = viewModel(
        factory = viewModelFactory {
            initializer { createInfrastructureIssueViewModel(activity, selectedService) }
        }
    )

    // The single manual marker reconciled from the ViewModel's markerLocation (an effect-held id, so
    // it survives recomposition but not the destination leaving — which is correct).
    val markerId = remember { intArrayOf(NO_MARKER) }

    // Publish this VM to the host activity so the form fragments (hosted on the activity's
    // supportFragmentManager, reached via getActivity()) can deliver their callbacks to it; clear on
    // leave, and clear any lingering report fragments so re-entry doesn't double-add.
    DisposableEffect(viewModel) {
        activity.infrastructureIssueViewModel = viewModel
        // The success dialog's OK (ReportSuccessDialog) leaves the *whole* report flow through this —
        // back to the home/map, matching the former finishActivityWithResult (which closed the entire
        // report stack, not just this screen). Plain back / the up arrow pop only this destination.
        activity.popInfrastructureIssue = {
            navController.popBackStack(
                org.onebusaway.android.ui.nav.NavRoutes.HOME, inclusive = false
            )
        }
        onDispose {
            activity.infrastructureIssueViewModel = null
            activity.popInfrastructureIssue = null
            activity.hideReportProgress()
            clearReportingFragments(fragmentManager)
        }
    }

    // Map taps drive the report location: a stop tap reports that stop, an empty-map tap reports the
    // tapped point (manual pin). Both update the map's render focus + recenter via the map VM.
    val mapCallbacks = remember(viewModel, mapViewModel) {
        object : ObaMapCallbacks {
            override fun onStopClick(stop: ObaStop) {
                mapViewModel.onStopTapped(stop)
                val loc = stop.location
                viewModel.onMapFocusChanged(stop, loc.latitude, loc.longitude)
            }

            override fun onMapClick(point: org.onebusaway.android.map.render.GeoPoint?) {
                mapViewModel.onMapTapped()
                point?.let { viewModel.onMapFocusChanged(null, it.latitude, it.longitude) }
            }

            override fun onBikeClick(station: org.opentripplanner.routing.bike_rental.BikeRentalStation) {}

            override fun onVehicleInfoWindowClick(status: org.onebusaway.android.io.elements.ObaTripStatus) {}

            override fun onBikeInfoWindowClick(station: org.opentripplanner.routing.bike_rental.BikeRentalStation) {}
        }
    }

    // Form routing (ported applyTarget): swap the form fragment into the container as the target
    // changes. On dispose is handled by the DisposableEffect above.
    LaunchedEffect(viewModel) {
        viewModel.uiState.map { it.target }.distinctUntilChanged().collect { target ->
            applyTarget(activity, viewModel, target)
        }
    }

    // Reconcile the single manual marker.
    LaunchedEffect(viewModel) {
        viewModel.uiState.map { it.markerLocation }.distinctUntilChanged().collect { location ->
            reconcileMarker(mapViewModel, markerId, location)
        }
    }

    // Loading-services progress drives the same overlay the Open311 form's showProgress uses.
    LaunchedEffect(viewModel) {
        viewModel.uiState.map { it.loadingServices }.distinctUntilChanged().collect { loading ->
            activity.showReportProgress(loading)
        }
    }

    // One-shot events: recenter the map, toast a failed geocode, or — on a successful submission —
    // show the success dialog then pop back to the chooser/home.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is InfrastructureIssueEvent.RecenterMap ->
                    mapViewModel.centerOn(event.latitude, event.longitude, animate = true)

                InfrastructureIssueEvent.AddressNotFound ->
                    android.widget.Toast.makeText(
                        activity, R.string.ri_address_not_found, android.widget.Toast.LENGTH_LONG
                    ).show()

                InfrastructureIssueEvent.ReportSent ->
                    if (!fragmentManager.isStateSaved) {
                        ReportSuccessDialog().show(fragmentManager, ReportSuccessDialog.TAG)
                    }
            }
        }
    }

    // Back: if a form/picker is showing, drop the spinner back to the hint (and clear the form);
    // otherwise pop the whole destination. Mirrors the former onBackPressed + form back-stack.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val formShowing = state.target != ReportTarget.None ||
        fragmentManager.findFragmentByTag(SimpleArrivalsPickerFragment.TAG) != null
    BackHandler(enabled = formShowing) {
        viewModel.onResetToHint()
    }

    val reportProgressVisible by activity.reportProgressVisible.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ObaTopAppBar(
                title = stringResource(R.string.rt_infrastructure_problem_title),
                onBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Replaces the XML CustomScrollView: a vertical scroll holding the fixed-height map, the
            // controls, and the form container (each wrap_content), matching the original arrangement.
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                ObaMap(
                    renderState = mapViewModel.renderState,
                    callbacks = mapCallbacks,
                    mapViewModel = mapViewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MAP_HEIGHT.dp),
                    initialLatitude = state.location.latitude,
                    initialLongitude = state.location.longitude,
                    initialZoom = MapParams.DEFAULT_ZOOM.toFloat(),
                )

                InfrastructureControls(
                    state = state,
                    onAddressSearch = viewModel::onAddressSearch,
                    onServiceSelected = viewModel::onServiceSelected,
                )

                // The container the stop/trip/Open311/arrival-picker fragments are swapped into;
                // the id matches what the fragments' show(...) helpers replace into.
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx ->
                        FragmentContainerView(ctx).apply { id = R.id.ri_report_stop_problem }
                    }
                )
            }

            if (reportProgressVisible) {
                CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(16.dp))
            }
        }
    }
}

// --- Ported imperative glue (was InfrastructureIssueActivity) -----------------------------------

private const val NO_MARKER = -1

// The former map FrameLayout was 200dp tall (infrastructure_issue.xml).
private const val MAP_HEIGHT = 200

/** Port of InfrastructureIssueActivity.applyTarget — swap the form fragment for the chosen target. */
private fun applyTarget(
    activity: HomeActivity,
    viewModel: InfrastructureIssueViewModel,
    target: ReportTarget,
) {
    when (target) {
        ReportTarget.None -> clearReportingFragments(activity.supportFragmentManager)
        is ReportTarget.StopProblem ->
            ProblemReportFragment.showStop(activity, target.stop, R.id.ri_report_stop_problem)

        is ReportTarget.TripProblem -> {
            val arrival = target.arrival
            if (arrival == null) {
                SimpleArrivalsPickerFragment.show(
                    activity, R.id.ri_report_stop_problem, target.stop, activity
                )
            } else {
                ProblemReportFragment.showTrip(activity, arrival, R.id.ri_report_stop_problem)
            }
        }

        is ReportTarget.Open311 -> {
            val (_, agencyName, blockId) = viewModel.tripContext()
            Open311ProblemFragment.show(
                activity,
                R.id.ri_report_stop_problem,
                viewModel.open311 as Open311,
                target.category.raw as Service,
                target.arrival,
                agencyName,
                blockId,
            )
        }
    }
}

private fun reconcileMarker(
    mapViewModel: MapViewModel,
    markerId: IntArray,
    location: IssueLocation?,
) {
    if (markerId[0] != NO_MARKER) {
        mapViewModel.removeMarker(markerId[0])
        markerId[0] = NO_MARKER
    }
    if (location != null) {
        markerId[0] = mapViewModel.addMarker(location.latitude, location.longitude, null)
    }
}

/** Port of InfrastructureIssueActivity.clearReportingFragments. */
internal fun clearReportingFragments(
    fragmentManager: androidx.fragment.app.FragmentManager,
) {
    if (fragmentManager.isStateSaved) return
    listOf(
        ProblemReportFragment.STOP_TAG,
        ProblemReportFragment.TRIP_TAG,
        Open311ProblemFragment.TAG,
        SimpleArrivalsPickerFragment.TAG,
    ).forEach { tag ->
        fragmentManager.findFragmentByTag(tag)?.let {
            fragmentManager.beginTransaction().remove(it).commit()
        }
    }
}

/**
 * Builds the [InfrastructureIssueViewModel] from the nav-arg [selectedService] and the host intent
 * extras (port of InfrastructureIssueActivity.createViewModel). The stop/location context + the opaque
 * `ObaArrivalInfo` (TRIP_INFO) + agency/block ids ride on the host activity intent, exactly as the
 * former Activity read them.
 */
private fun createInfrastructureIssueViewModel(
    activity: HomeActivity,
    selectedService: String?,
): InfrastructureIssueViewModel {
    val source = activity.intent
    val latitude = source.getDoubleExtra(MapParams.CENTER_LAT, 0.0)
    val longitude = source.getDoubleExtra(MapParams.CENTER_LON, 0.0)

    val initialStop: ObaStop? = source.getStringExtra(MapParams.STOP_ID)?.let { stopId ->
        ObaStopElement(
            stopId, latitude, longitude,
            source.getStringExtra(MapParams.STOP_NAME),
            source.getStringExtra(MapParams.STOP_CODE),
        )
    }

    @Suppress("DEPRECATION")
    val arrival = source.getSerializableExtra(EXTRA_TRIP_INFO) as? org.onebusaway.android.io.elements.ObaArrivalInfo

    val defaultIssueType = when (selectedService) {
        activity.getString(R.string.ri_selected_service_stop) -> DefaultIssueType.STOP
        activity.getString(R.string.ri_selected_service_trip) -> DefaultIssueType.TRIP
        else -> DefaultIssueType.NONE
    }

    return InfrastructureIssueViewModel(
        serviceListRepository = DefaultServiceListRepository(activity.applicationContext),
        geocodeRepository = DefaultGeocodeAddressRepository(activity.applicationContext),
        initialLocation = GeoPoint(latitude, longitude),
        initialStop = initialStop,
        defaultIssueType = defaultIssueType,
        arrivalInfo = arrival,
        agencyName = source.getStringExtra(EXTRA_AGENCY_NAME),
        blockId = source.getStringExtra(EXTRA_BLOCK_ID),
    )
}
