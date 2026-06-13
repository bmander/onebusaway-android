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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import edu.usf.cutr.open311client.Open311
import edu.usf.cutr.open311client.models.Service
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.ObaArrivalInfo
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.io.elements.ObaStopElement
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.map.ObaMapFragment
import org.onebusaway.android.report.ui.dialog.ReportSuccessDialog
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.report.infrastructure.DefaultGeocodeAddressRepository
import org.onebusaway.android.ui.report.infrastructure.DefaultServiceListRepository
import org.onebusaway.android.ui.report.infrastructure.GeoPoint
import org.onebusaway.android.ui.report.infrastructure.InfrastructureControls
import org.onebusaway.android.ui.report.infrastructure.InfrastructureIssueEvent
import org.onebusaway.android.ui.report.infrastructure.InfrastructureIssueViewModel
import org.onebusaway.android.ui.report.infrastructure.IssueLocation
import org.onebusaway.android.ui.report.infrastructure.DefaultIssueType
import org.onebusaway.android.ui.report.infrastructure.ReportTarget
import org.onebusaway.android.ui.report.infrastructure.ServiceListItem
import org.onebusaway.android.ui.report.open311.Open311IssueContext
import org.onebusaway.android.ui.report.open311.Open311ProblemFragment
import org.onebusaway.android.ui.report.problem.ProblemReportFragment
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.UIUtils

/**
 * Hosts the native map plus the Compose address/service controls and the report forms. Replaces
 * the AsyncTask + IssueLocationHelper container with [InfrastructureIssueViewModel] driving the UI;
 * the map focus listener feeds the ViewModel and the ViewModel's [ReportTarget] drives the form
 * fragment transactions. Kept in this package so the hard-coded casts (ReportSuccessDialog, the
 * report fragments) and the manifest entry stay valid.
 */
class InfrastructureIssueActivity : BaseReportActivity(),
    ObaMapFragment.OnFocusChangedListener,
    ReportProblemFragmentCallback,
    SimpleArrivalsPickerFragment.Callback {

    private lateinit var mapFragment: ObaMapFragment

    /** The single manual marker the host reconciles from the ViewModel's markerLocation. */
    private var manualMarkerId = NO_MARKER

    private val viewModel: InfrastructureIssueViewModel by viewModels {
        viewModelFactory { initializer { createViewModel() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.infrastructure_issue)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        UIUtils.setupActionBar(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setUpProgressBar()

        setupMapFragment(savedInstanceState)

        findViewById<ComposeView>(R.id.ri_compose_view).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ObaTheme {
                    val state by viewModel.uiState.collectAsState()
                    InfrastructureControls(
                        state = state,
                        onAddressSearch = viewModel::onAddressSearch,
                        onServiceSelected = viewModel::onServiceSelected
                    )
                }
            }
        }

        observeViewModel()
    }

    private fun setupMapFragment(savedInstanceState: Bundle?) {
        val existing = supportFragmentManager.findFragmentByTag(ObaMapFragment.TAG) as? ObaMapFragment
        mapFragment = existing ?: ObaMapFragment.newInstance().also { fragment ->
            fragment.asFragment().arguments = savedInstanceState
            supportFragmentManager.beginTransaction()
                .add(R.id.ri_frame_map_view, fragment.asFragment(), ObaMapFragment.TAG)
                .commit()
        }
        mapFragment.setOnFocusChangeListener(this)

        val location = viewModel.uiState.value.location
        mapFragment.setMapCenter(LocationUtils.makeLocation(location.latitude, location.longitude), true, false)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.map { it.target }.distinctUntilChanged().collect(::applyTarget)
                }
                launch {
                    viewModel.uiState.map { it.markerLocation }.distinctUntilChanged()
                        .collect(::reconcileMarker)
                }
                launch {
                    viewModel.uiState.map { it.loadingServices }.distinctUntilChanged()
                        .collect { showProgress(it) }
                }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    private fun applyTarget(target: ReportTarget) {
        when (target) {
            ReportTarget.None -> clearReportingFragments()
            is ReportTarget.StopProblem ->
                ProblemReportFragment.showStop(this, target.stop, R.id.ri_report_stop_problem)

            is ReportTarget.TripProblem -> {
                val arrival = target.arrival
                if (arrival == null) {
                    SimpleArrivalsPickerFragment.show(this, R.id.ri_report_stop_problem, target.stop, this)
                } else {
                    ProblemReportFragment.showTrip(this, arrival, R.id.ri_report_stop_problem)
                }
            }

            is ReportTarget.Open311 -> {
                val (_, agencyName, blockId) = viewModel.tripContext()
                Open311ProblemFragment.show(
                    this,
                    R.id.ri_report_stop_problem,
                    viewModel.open311 as Open311,
                    target.category.raw as Service,
                    target.arrival,
                    agencyName,
                    blockId
                )
            }
        }
    }

    private fun reconcileMarker(location: IssueLocation?) {
        if (manualMarkerId != NO_MARKER) {
            mapFragment.removeMarker(manualMarkerId)
            manualMarkerId = NO_MARKER
        }
        if (location != null) {
            manualMarkerId = mapFragment.addMarker(
                LocationUtils.makeLocation(location.latitude, location.longitude), null
            )
        }
    }

    private fun handleEvent(event: InfrastructureIssueEvent) {
        when (event) {
            is InfrastructureIssueEvent.RecenterMap ->
                mapFragment.setMapCenter(LocationUtils.makeLocation(event.latitude, event.longitude), true, true)

            InfrastructureIssueEvent.AddressNotFound ->
                Toast.makeText(this, R.string.ri_address_not_found, Toast.LENGTH_LONG).show()

            InfrastructureIssueEvent.ReportSent ->
                ReportSuccessDialog().show(supportFragmentManager, ReportSuccessDialog.TAG)
        }
    }

    private fun clearReportingFragments() {
        listOf(
            ProblemReportFragment.STOP_TAG,
            ProblemReportFragment.TRIP_TAG,
            Open311ProblemFragment.TAG,
            SimpleArrivalsPickerFragment.TAG
        ).forEach { tag ->
            supportFragmentManager.findFragmentByTag(tag)?.let {
                supportFragmentManager.beginTransaction().remove(it).commit()
            }
        }
        findViewById<LinearLayout>(R.id.ri_report_stop_problem).removeAllViews()
    }

    // --- Map + form callbacks -------------------------------------------------------------------

    override fun onFocusChanged(stop: ObaStop?, routes: HashMap<String, ObaRoute>?, location: Location?) {
        val point = location ?: return
        viewModel.onMapFocusChanged(stop, point.latitude, point.longitude)
    }

    override fun onFocusChanged(bikeRentalStation: org.opentripplanner.routing.bike_rental.BikeRentalStation?) {
        // Bike rental stations aren't reported here.
    }

    override fun onReportSent() {
        viewModel.onReportSent()
    }

    override fun onArrivalItemClicked(arrival: ObaArrivalInfo, agencyId: String?, blockId: String?) {
        supportFragmentManager.findFragmentByTag(SimpleArrivalsPickerFragment.TAG)?.let {
            supportFragmentManager.beginTransaction().remove(it).commit()
        }
        viewModel.onArrivalSelected(arrival)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        // A form closed via back: drop the spinner selection back to the hint.
        viewModel.onResetToHint()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishActivityWithResult()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** The current issue location/address/stop, for the hosted Open311 form. */
    fun currentIssueContext(): Open311IssueContext {
        val state = viewModel.uiState.value
        return Open311IssueContext(
            latitude = state.location.latitude,
            longitude = state.location.longitude,
            address = state.address.takeIf { it.isNotEmpty() },
            obaStop = state.location.stop
        )
    }

    /** Closes the whole report stack after a successful submission. */
    fun finishActivityWithResult() {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(BaseReportActivity.CLOSE_REQUEST, true)
        )
        finish()
    }

    private fun createViewModel(): InfrastructureIssueViewModel {
        val source = intent
        val latitude = source.getDoubleExtra(MapParams.CENTER_LAT, 0.0)
        val longitude = source.getDoubleExtra(MapParams.CENTER_LON, 0.0)

        val initialStop: ObaStop? = source.getStringExtra(MapParams.STOP_ID)?.let { stopId ->
            ObaStopElement(
                stopId, latitude, longitude,
                source.getStringExtra(MapParams.STOP_NAME),
                source.getStringExtra(MapParams.STOP_CODE)
            )
        }

        @Suppress("DEPRECATION")
        val arrival = source.getSerializableExtra(TRIP_INFO) as? ObaArrivalInfo

        val defaultIssueType = when (source.getStringExtra(SELECTED_SERVICE)) {
            getString(R.string.ri_selected_service_stop) -> DefaultIssueType.STOP
            getString(R.string.ri_selected_service_trip) -> DefaultIssueType.TRIP
            else -> DefaultIssueType.NONE
        }

        return InfrastructureIssueViewModel(
            serviceListRepository = DefaultServiceListRepository(applicationContext),
            geocodeRepository = DefaultGeocodeAddressRepository(applicationContext),
            initialLocation = GeoPoint(latitude, longitude),
            initialStop = initialStop,
            defaultIssueType = defaultIssueType,
            arrivalInfo = arrival,
            agencyName = source.getStringExtra(AGENCY_NAME),
            blockId = source.getStringExtra(BLOCK_ID)
        )
    }

    companion object {
        private const val NO_MARKER = -1
        private const val REQUEST_CODE = 0

        private const val SELECTED_SERVICE = ".selectedService"
        private const val TRIP_INFO = ".tripInfo"
        private const val AGENCY_NAME = ".agencyName"
        private const val BLOCK_ID = ".blockId"

        @JvmStatic
        fun start(activity: Activity, intent: Intent) {
            activity.startActivityForResult(makeIntent(activity, intent), REQUEST_CODE)
        }

        @JvmStatic
        @JvmOverloads
        fun startWithService(
            activity: Activity,
            intent: Intent,
            serviceKeyword: String,
            arrivalInfo: ObaArrivalInfo? = null,
            agencyName: String? = null,
            blockId: String? = null
        ) {
            val target = makeIntent(activity, intent).apply {
                putExtra(SELECTED_SERVICE, serviceKeyword)
                arrivalInfo?.let { putExtra(TRIP_INFO, it) }
                putExtra(AGENCY_NAME, agencyName)
                putExtra(BLOCK_ID, blockId)
            }
            activity.startActivityForResult(target, REQUEST_CODE)
        }

        @JvmStatic
        fun makeIntent(context: Context, source: Intent): Intent =
            Intent(context, InfrastructureIssueActivity::class.java).apply {
                putExtra(MapParams.STOP_ID, source.getStringExtra(MapParams.STOP_ID))
                putExtra(MapParams.STOP_NAME, source.getStringExtra(MapParams.STOP_NAME))
                putExtra(MapParams.STOP_CODE, source.getStringExtra(MapParams.STOP_CODE))
                putExtra(MapParams.CENTER_LAT, source.getDoubleExtra(MapParams.CENTER_LAT, 0.0))
                putExtra(MapParams.CENTER_LON, source.getDoubleExtra(MapParams.CENTER_LON, 0.0))
            }
    }
}
