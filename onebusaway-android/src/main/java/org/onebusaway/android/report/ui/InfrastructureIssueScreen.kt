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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
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
import edu.usf.cutr.open311client.constants.Open311Constants
import android.content.Context
import android.widget.Toast
import com.google.firebase.analytics.FirebaseAnalytics
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.io.elements.ObaArrivalInfo
import org.onebusaway.android.ui.ArrivalInfo
import org.onebusaway.android.ui.HomeActivity
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
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
import org.onebusaway.android.ui.report.problem.DefaultProblemReportRepository
import org.onebusaway.android.ui.report.problem.ProblemCodes
import org.onebusaway.android.ui.report.problem.ProblemKind
import org.onebusaway.android.ui.report.problem.ProblemParams
import org.onebusaway.android.ui.report.problem.ProblemReportRoute
import org.onebusaway.android.ui.report.problem.ProblemReportViewModel
import org.onebusaway.android.ui.report.problem.SubmitState
import org.onebusaway.android.util.UIUtils

/** Host-intent extras carrying the opaque trip context for the InfrastructureIssue destination. */
const val EXTRA_TRIP_INFO = ".tripInfo"
const val EXTRA_AGENCY_NAME = ".agencyName"
const val EXTRA_BLOCK_ID = ".blockId"

/**
 * The infrastructure-issue (stop/trip problem) NavHost destination (Campaign C; former
 * [InfrastructureIssueActivity]). It replaces the `infrastructure_issue.xml` layout with a Compose
 * [Scaffold]: the declarative [ObaMap] (entry-scoped [MapViewModel], stop mode), the
 * [InfrastructureControls], the inline stop/trip form + arrivals picker (Tier 1, P3a), and a
 * [FragmentContainerView] (id `R.id.ri_report_stop_problem`) the remaining Open311 form fragment is
 * swapped into (P3b inlines it too).
 *
 * The [InfrastructureIssueViewModel] is built once (back-stack-entry-scoped) from the nav-arg
 * `selectedService` plus the host intent extras (lat/lon, stop id/name/code, the opaque
 * `ObaArrivalInfo`, agency name, block id), reproducing the former Activity's hand-built factory. A
 * [DisposableEffect] publishes that VM to the host [HomeActivity] (which implements
 * [ReportProblemFragmentCallback] and [InfrastructureIssueHost]) so the Open311 fragment — hosted on the
 * host activity's `supportFragmentManager` — reaches it through `getActivity()`, and clears it on dispose.
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

    // The "report submitted" dialog (Tier 1: was ReportSuccessDialog, a DialogFragment).
    var showSuccess by remember { mutableStateOf(false) }

    // The active inline form's "send" action, hoisted so it can live in the app bar (Tier 1, P3a). Set
    // by the stop/trip form while shown, null otherwise (so the send icon only appears for those forms).
    var formSubmit by remember { mutableStateOf<(() -> Unit)?>(null) }

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

                InfrastructureIssueEvent.ReportSent -> showSuccess = true
            }
        }
    }

    // Back: if a form/picker is showing, drop the spinner back to the hint (and clear the form);
    // otherwise pop the whole destination. Mirrors the former onBackPressed + form back-stack.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // A form/picker is showing whenever the target isn't the hint (all forms are inline now bar Open311,
    // which is also driven by the target). Back drops the spinner back to the hint.
    BackHandler(enabled = state.target != ReportTarget.None) {
        viewModel.onResetToHint()
    }

    val reportProgressVisible by activity.reportProgressVisible.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ObaTopAppBar(
                title = stringResource(R.string.rt_infrastructure_problem_title),
                onBack = { navController.popBackStack() },
                actions = {
                    // The stop/trip form's "send" (Tier 1: was the form fragment's MenuProvider item).
                    formSubmit?.let { submit ->
                        IconButton(onClick = submit) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_social_send_now),
                                contentDescription = stringResource(R.string.report_problem_send),
                            )
                        }
                    }
                },
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

                // The stop/trip problem form + the arrivals picker render inline (Tier 1, P3a); each
                // sets/clears the app-bar send via [formSubmit]. Open311 + None render nothing here and
                // use the FragmentContainerView below (P3b retires that).
                when (val target = state.target) {
                    is ReportTarget.StopProblem ->
                        StopTripProblemForm(target.stop, null, viewModel) { formSubmit = it }

                    is ReportTarget.TripProblem ->
                        if (target.arrival == null) {
                            ArrivalsPickerInline(target.stop, activity, viewModel)
                        } else {
                            StopTripProblemForm(target.stop, target.arrival, viewModel) { formSubmit = it }
                        }

                    ReportTarget.None, is ReportTarget.Open311 -> Unit
                }

                // The container the Open311 form fragment is swapped into (P3b inlines it too); the id
                // matches what Open311ProblemFragment.show(...) replaces into.
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

    // OK (or back) leaves the whole report flow back to home/map, matching ReportSuccessDialog's
    // closeSuperActivity (finishInfrastructureIssue == popInfrastructureIssue). Not cancelable outside.
    if (showSuccess) {
        AlertDialog(
            onDismissRequest = {
                showSuccess = false
                activity.popInfrastructureIssue?.invoke()
            },
            properties = DialogProperties(dismissOnClickOutside = false),
            text = { Text(Open311Constants.M_REPORT_SUCCESS) },
            confirmButton = {
                TextButton(onClick = {
                    showSuccess = false
                    activity.popInfrastructureIssue?.invoke()
                }) { Text("OK") }
            },
        )
    }
}

// --- Ported imperative glue (was InfrastructureIssueActivity) -----------------------------------

private const val NO_MARKER = -1

// The former map FrameLayout was 200dp tall (infrastructure_issue.xml).
private const val MAP_HEIGHT = 200

/**
 * Swaps the Open311 form fragment in/out for the chosen target. The stop/trip form + arrivals picker
 * are now inline Compose (Tier 1, P3a); only Open311 remains a fragment (P3b), so any non-Open311 target
 * clears it.
 */
private fun applyTarget(
    activity: HomeActivity,
    viewModel: InfrastructureIssueViewModel,
    target: ReportTarget,
) {
    when (target) {
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

        ReportTarget.None, is ReportTarget.StopProblem, is ReportTarget.TripProblem ->
            clearReportingFragments(activity.supportFragmentManager)
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

/** Removes the Open311 form fragment if present (the only remaining report fragment; P3b inlines it). */
internal fun clearReportingFragments(
    fragmentManager: androidx.fragment.app.FragmentManager,
) {
    if (fragmentManager.isStateSaved) return
    fragmentManager.findFragmentByTag(Open311ProblemFragment.TAG)?.let {
        fragmentManager.beginTransaction().remove(it).commit()
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

// --- Inline report forms (Tier 1, P3a: were ProblemReportFragment / SimpleArrivalsPickerFragment) -----

/**
 * The stop/trip problem form, rendered inline. Builds its [ProblemReportViewModel] from the
 * [stop]/[arrival] (port of the fragment's createViewModel), reports a successful submission to the
 * destination's [issueViewModel] (→ the success dialog), and hoists its "send" action via [onSubmit] so
 * the app bar can trigger it (validate + analytics + submit-with-location — port of onSendClicked).
 */
@Composable
private fun StopTripProblemForm(
    stop: ObaStop,
    arrival: ObaArrivalInfo?,
    issueViewModel: InfrastructureIssueViewModel,
    onSubmit: ((() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity() as HomeActivity
    val vm: ProblemReportViewModel = viewModel(
        key = "problem:${arrival?.tripId ?: stop.id}",
        factory = viewModelFactory {
            initializer { createProblemReportViewModel(context, stop, arrival) }
        },
    )

    LaunchedEffect(vm) {
        vm.submitState.collect { state ->
            when (state) {
                SubmitState.Sent -> {
                    issueViewModel.onReportSent()
                    vm.onSubmitResultHandled()
                }

                SubmitState.Error -> {
                    Toast.makeText(context, R.string.report_problem_error, Toast.LENGTH_LONG).show()
                    vm.onSubmitResultHandled()
                }

                else -> Unit
            }
        }
    }

    // Publish the send action to the app bar while shown; clear it on leave.
    DisposableEffect(vm) {
        onSubmit {
            val form = vm.formState.value
            if (!form.canSubmit) {
                Toast.makeText(
                    context, R.string.report_problem_invalid_argument, Toast.LENGTH_LONG
                ).show()
            } else {
                reportProblemAnalytics(context, form.kind)
                vm.submit(activity.locationRepository.lastKnownLocation())
            }
        }
        onDispose { onSubmit(null) }
    }

    ProblemReportRoute(vm)
}

/** The arrivals picker, rendered inline; a tap re-drives the VM target (→ trip form or Open311). */
@Composable
private fun ArrivalsPickerInline(
    stop: ObaStop,
    activity: HomeActivity,
    issueViewModel: InfrastructureIssueViewModel,
) {
    val arrivalsViewModel: ArrivalsViewModel = viewModel(
        key = "picker:${stop.id}",
        factory = viewModelFactory {
            initializer {
                activity.arrivalsViewModelFactory.create(stop.id, ignorePersistedFilter = true)
            }
        },
    )
    SimpleArrivalsPicker(arrivalsViewModel) { arrival ->
        issueViewModel.onArrivalSelected(arrival.info)
    }
}

/** Port of ProblemReportFragment.createViewModel — stop or trip params + codes + repository. */
private fun createProblemReportViewModel(
    context: Context,
    stop: ObaStop,
    arrival: ObaArrivalInfo?,
): ProblemReportViewModel {
    val repository = DefaultProblemReportRepository(context.applicationContext)
    return if (arrival != null) {
        ProblemReportViewModel(
            params = ProblemParams.Trip(
                tripId = arrival.tripId,
                stopId = arrival.stopId,
                vehicleId = arrival.vehicleId,
                serviceDate = arrival.serviceDate,
            ),
            codes = ProblemCodes.trip(
                context.resources.getStringArray(R.array.report_trip_problem_code_bus).toList()
            ),
            headsign = UIUtils.formatDisplayText(arrival.headsign),
            repository = repository,
        )
    } else {
        ProblemReportViewModel(
            params = ProblemParams.Stop(stop.id),
            codes = ProblemCodes.stop(
                context.resources.getStringArray(R.array.report_stop_problem_code).toList()
            ),
            headsign = null,
            repository = repository,
        )
    }
}

/** Port of ProblemReportFragment.reportAnalytics — the stop/trip problem Plausible event. */
private fun reportProblemAnalytics(context: Context, kind: ProblemKind) {
    val isTrip = kind == ProblemKind.TRIP
    ObaAnalytics.reportUiEvent(
        FirebaseAnalytics.getInstance(context),
        Application.get().plausibleInstance,
        if (isTrip) {
            PlausibleAnalytics.REPORT_VEHICLE_PROBLEM_EVENT_URL
        } else {
            PlausibleAnalytics.REPORT_STOP_PROBLEM_EVENT_URL
        },
        context.getString(R.string.analytics_problem),
        context.getString(
            if (isTrip) {
                R.string.analytics_label_report_trip_problem
            } else {
                R.string.analytics_label_report_stop_problem
            }
        ),
    )
}
