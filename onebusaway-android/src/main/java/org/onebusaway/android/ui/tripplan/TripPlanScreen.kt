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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.firebase.analytics.FirebaseAnalytics
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.app.di.LocationEntryPoint
import org.onebusaway.android.app.di.RegionEntryPoint
import org.onebusaway.android.directions.util.ConversionUtils
import org.onebusaway.android.directions.util.CustomAddress
import org.onebusaway.android.directions.util.OTPConstants
import org.onebusaway.android.directions.util.TripRequestBuilder
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.ui.TripModes
import org.onebusaway.android.ui.compose.findActivity
import org.onebusaway.android.ui.nav.NavRoutes
import org.onebusaway.android.ui.tripresults.TripResultsFragment
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.UIUtils
import org.opentripplanner.api.model.Itinerary

private const val RESULTS_TAG = "TripResults"

/**
 * The trip-plan NavHost destination (Campaign C). Ports the Android glue that the former
 * [org.onebusaway.android.ui.TripPlanActivity] owned — the date/time pickers, the contacts picker,
 * current-location reads, the advanced-options dialog, the error dialog + analytics, and rehydrating
 * from a RealtimeService trip-update notification — into Compose, wiring it to [TripPlanRoute].
 * State lives in the Hilt [TripPlanViewModel].
 */
@Composable
fun TripPlanDestination(navController: NavHostController, onBack: () -> Unit) {
    val viewModel = hiltViewModel<TripPlanViewModel>()
    val activity = LocalContext.current.findActivity()

    // Built once for the lifetime of this destination (analytics + region email for "report problem").
    val firebaseAnalytics = remember { FirebaseAnalytics.getInstance(activity) }
    // HomeActivity doesn't inject RegionRepository; reach the shared singleton via the EntryPoint.
    val regionRepository = remember { RegionEntryPoint.get(activity) }

    // -- Contacts pick: a launcher + the endpoint a pending pick should populate. A contacts pick
    // doesn't dispose this composable, so a plain remember (not rememberSaveable) suffices.
    var contactsTarget by remember { mutableStateOf<((PlaceItem) -> Unit)?>(null) }
    val contactsLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri != null) {
            formattedAddress(activity, uri)?.let { address ->
                contactsTarget?.invoke(PlaceItem(displayName = address))
            }
        }
        contactsTarget = null
    }
    val launchContacts: ((PlaceItem) -> Unit) -> Unit = { target ->
        contactsTarget = target
        contactsLauncher.launch(
            Intent(Intent.ACTION_PICK)
                .setType(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_TYPE)
        )
    }

    // -- Map pick: instead of an ActivityResult launcher, navigate to the picker destination and read
    // the chosen point back from this entry's SavedStateHandle. The pending endpoint ("from"/"to")
    // MUST be rememberSaveable: navigating to the picker disposes this composable, and a lambda can't
    // be saved across that — so we save which endpoint to populate, not the setter.
    var mapPickTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val launchMapPicker: (String, PlaceItem?) -> Unit = { endpoint, initial ->
        val center = if (initial?.hasCoordinates == true) {
            LocationUtils.makeLocation(initial.lat!!, initial.lon!!)
        } else {
            LocationUtils.getSearchCenter(activity.applicationContext)
        }
        mapPickTarget = endpoint
        navController.navigate(
            NavRoutes.tripPlanPickLocation(center?.latitude, center?.longitude)
        )
    }
    // When the picker hands a result back to this entry's SavedStateHandle, build the PlaceItem and
    // dispatch it to the saved endpoint, then clear the keys + the target.
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    LaunchedEffect(savedStateHandle, mapPickTarget) {
        val handle = savedStateHandle ?: return@LaunchedEffect
        val lat = handle.get<Double>(NavRoutes.RESULT_PICK_LAT)
        val lon = handle.get<Double>(NavRoutes.RESULT_PICK_LON)
        if (lat != null && lon != null) {
            val place = PlaceItem(
                displayName = activity.getString(R.string.trip_plan_map_location),
                lat = lat,
                lon = lon
            )
            when (mapPickTarget) {
                "from" -> viewModel.setFrom(place)
                "to" -> viewModel.setTo(place)
            }
            handle.remove<Double>(NavRoutes.RESULT_PICK_LAT)
            handle.remove<Double>(NavRoutes.RESULT_PICK_LON)
            mapPickTarget = null
        }
    }

    // -- Plan errors → dialog; plan submit → analytics. Collected while this destination is composed.
    LaunchedEffect(viewModel) {
        viewModel.planState.collect { state ->
            when (state) {
                is PlanResult.Loading -> reportPlanAnalytics(activity, firebaseAnalytics)
                is PlanResult.Error -> {
                    showFeedbackDialog(activity, firebaseAnalytics, regionRepository, state.message)
                    viewModel.clearPlanResult()
                }
                else -> {}
            }
        }
    }

    // -- Notification re-entry: when the app is reopened (cold/from background) from a RealtimeService
    // trip-update notification, onNewIntent stages the TRIP_PLAN route and this destination composes
    // fresh; read the restore extras off the host intent once. (A notification arriving while already
    // on this destination — singleTop, no recomposition — won't re-restore; acceptable rare edge.)
    LaunchedEffect(Unit) {
        maybeRestoreFromIntent(viewModel, activity.intent)?.let { activity.setIntent(it) }
    }

    TripPlanRoute(
        viewModel = viewModel,
        fragmentManager = activity.supportFragmentManager,
        onBack = onBack,
        onPickDate = { pickDate(activity, viewModel) },
        onPickTime = { pickTime(activity, viewModel) },
        onFromCurrentLocation = { setCurrentLocation(activity, viewModel::setFrom) },
        onToCurrentLocation = { setCurrentLocation(activity, viewModel::setTo) },
        onFromContacts = { launchContacts(viewModel::setFrom) },
        onToContacts = { launchContacts(viewModel::setTo) },
        onFromPickOnMap = { launchMapPicker("from", viewModel.formState.value.from) },
        onToPickOnMap = { launchMapPicker("to", viewModel.formState.value.to) },
        onAdvancedSettings = { showAdvancedSettings(activity, viewModel) },
        onReportProblem = { reportProblem(activity, firebaseAnalytics, regionRepository) }
    )
}

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

// -- Trip-plan platform glue, ported verbatim from the former TripPlanActivity ---------------------
//
// These were the Activity's private helpers; as a NavHost destination there is no per-screen Activity,
// so they live here as file-private functions taking the host AppCompatActivity (its supportFragment-
// Manager / contentResolver / window). Behavior is preserved exactly.

// -- Date / time pickers (platform), feeding the ViewModel ------------------------------------

private fun pickDate(activity: androidx.appcompat.app.AppCompatActivity, viewModel: TripPlanViewModel) {
    val current = viewModel.formState.value.dateTimeMillis
    val picker = MaterialDatePicker.Builder.datePicker()
        .setTitleText(R.string.trip_plan_date)
        .setSelection(current)
        .build()
    picker.addOnPositiveButtonClickListener { selection ->
        // Read the selection in UTC, exactly as the user saw it (matches the legacy form).
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = selection }
        val calendar = Calendar.getInstance().apply {
            timeInMillis = current
            set(Calendar.YEAR, utc.get(Calendar.YEAR))
            set(Calendar.MONTH, utc.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
        }
        viewModel.setDateTime(calendar.timeInMillis)
    }
    picker.show(activity.supportFragmentManager, "DATE_PICKER")
}

private fun pickTime(activity: androidx.appcompat.app.AppCompatActivity, viewModel: TripPlanViewModel) {
    val current = viewModel.formState.value.dateTimeMillis
    val calendar = Calendar.getInstance().apply { timeInMillis = current }
    val timeFormat =
        if (DateFormat.is24HourFormat(activity)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
    val picker = MaterialTimePicker.Builder()
        .setTimeFormat(timeFormat)
        .setHour(calendar.get(Calendar.HOUR_OF_DAY))
        .setMinute(calendar.get(Calendar.MINUTE))
        .setTitleText(R.string.trip_plan_time)
        .setTheme(R.style.ThemeOverlay_App_TimePicker)
        .build()
    picker.addOnPositiveButtonClickListener {
        calendar.set(Calendar.HOUR_OF_DAY, picker.hour)
        calendar.set(Calendar.MINUTE, picker.minute)
        viewModel.setDateTime(calendar.timeInMillis)
    }
    picker.show(activity.supportFragmentManager, "TIME_PICKER")
}

// -- Current location + contacts (platform) --------------------------------------------------

private fun setCurrentLocation(
    activity: androidx.appcompat.app.AppCompatActivity,
    target: (PlaceItem) -> Unit
) {
    val location = LocationEntryPoint.get(activity.applicationContext).lastKnownLocation()
    if (location == null) {
        Toast.makeText(activity, activity.getString(R.string.no_location_permission), Toast.LENGTH_SHORT)
            .show()
    }
    target(
        PlaceItem(
            displayName = activity.getString(R.string.tripplanner_current_location),
            lat = location?.latitude,
            lon = location?.longitude,
            isCurrentLocation = true
        )
    )
}

private fun formattedAddress(
    activity: androidx.appcompat.app.AppCompatActivity,
    uri: Uri
): String? {
    val projection = arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
    activity.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS
            )
            return cursor.getString(index)?.replace("\n", ", ")
        }
    }
    return null
}

// -- Advanced options dialog (ported from the legacy form) -----------------------------------

private fun showAdvancedSettings(
    activity: androidx.appcompat.app.AppCompatActivity,
    viewModel: TripPlanViewModel
) {
    val current = viewModel.formState.value
    val imperial = !PreferenceUtils.getUnitsAreMetricFromPreferences(activity)
    val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.trip_plan_advanced_settings)
        .setView(R.layout.trip_plan_advanced_settings_dialog)
        .setPositiveButton(R.string.ok, null)
        .create()
    dialog.show()

    val minimizeTransfers = dialog.findViewById<CheckBox>(R.id.checkbox_minimize_transfers)!!
    val travelBy = dialog.findViewById<Spinner>(R.id.spinner_travel_by)!!
    val wheelchair = dialog.findViewById<CheckBox>(R.id.checkbox_wheelchair_acccesible)!!
    val maxWalkField = dialog.findViewById<EditText>(R.id.number_maximum_walk_distance)!!

    minimizeTransfers.isChecked = current.optimizeTransfers
    wheelchair.isChecked = current.wheelchair

    val options = activity.resources.getStringArray(R.array.transit_mode_array).toMutableList()
    if (!Application.isBikeshareEnabled()) {
        options.remove(activity.getString(R.string.transit_mode_bikeshare))
        options.remove(activity.getString(R.string.transit_mode_transit_and_bikeshare))
    }
    travelBy.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, options).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    if (current.modeId in options.indices) {
        travelBy.setSelection(TripModes.getSpinnerPositionFromSeledctedCode(current.modeId))
    }
    current.maxWalkMeters?.let { meters ->
        val shown = if (imperial) ConversionUtils.metersToFeet(meters) else meters
        maxWalkField.setText(String.format("%d", shown.toLong()))
    }
    if (imperial) {
        dialog.findViewById<TextView>(R.id.label_minimum_walk_distance)
            ?.setText(activity.getString(R.string.feet_abbreviation))
    }

    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val modeResources = activity.resources.obtainTypedArray(R.array.transit_mode_array)
        val selected = travelBy.selectedItem.toString()
        var resourceId = 0
        for (i in 0 until modeResources.length()) {
            if (selected == modeResources.getString(i)) {
                resourceId = modeResources.getResourceId(i, 0)
            }
        }
        modeResources.recycle()
        val modeId = TripModes.getTripModeCodeFromSelection(resourceId)

        val maxWalkText = maxWalkField.text.toString()
        val maxWalkMeters: Double? = if (maxWalkText.isEmpty()) {
            null
        } else {
            val value = maxWalkText.toDouble()
            if (imperial) ConversionUtils.feetToMeters(value) else value
        }

        val settings =
            AdvancedSettings(modeId, maxWalkMeters, minimizeTransfers.isChecked, wheelchair.isChecked)
        viewModel.applyAdvancedSettings(settings)

        PreferenceUtils.saveInt(activity.getString(R.string.preference_key_trip_plan_travel_by), modeId)
        PreferenceUtils.saveDouble(
            activity.getString(R.string.preference_key_trip_plan_maximum_walking_distance),
            maxWalkMeters ?: Double.MAX_VALUE
        )
        PreferenceUtils.saveBoolean(
            activity.getString(R.string.preference_key_trip_plan_minimize_transfers),
            minimizeTransfers.isChecked
        )
        PreferenceUtils.saveBoolean(
            activity.getString(R.string.preference_key_trip_plan_avoid_stairs),
            wheelchair.isChecked
        )
        dialog.dismiss()
    }
}

// -- Errors, reporting, analytics, notification re-entry --------------------------------------

private fun showFeedbackDialog(
    activity: androidx.appcompat.app.AppCompatActivity,
    firebaseAnalytics: FirebaseAnalytics,
    regionRepository: org.onebusaway.android.region.RegionRepository,
    message: String
) {
    MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.tripplanner_error_dialog_title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(R.string.report_problem_report) { _, _ ->
            reportProblem(activity, firebaseAnalytics, regionRepository)
        }
        .show()
}

private fun reportProblem(
    activity: androidx.appcompat.app.AppCompatActivity,
    firebaseAnalytics: FirebaseAnalytics,
    regionRepository: org.onebusaway.android.region.RegionRepository
) {
    val email = regionRepository.region.value?.otpContactEmail
    if (email.isNullOrEmpty()) {
        Toast.makeText(activity, activity.getString(R.string.tripplanner_no_contact), Toast.LENGTH_SHORT)
            .show()
        return
    }
    val location = LocationEntryPoint.get(activity.applicationContext).lastKnownLocation()
    val locationString = location?.let { LocationUtils.printLocationDetails(it) }
    UIUtils.sendEmail(activity, email, locationString, null, true)
    ObaAnalytics.reportUiEvent(
        firebaseAnalytics, Application.get().plausibleInstance,
        PlausibleAnalytics.REPORT_TRIP_PLANNER_EVENT_URL,
        activity.getString(R.string.analytics_label_app_feedback_otp), null
    )
}

private fun reportPlanAnalytics(
    activity: androidx.appcompat.app.AppCompatActivity,
    firebaseAnalytics: FirebaseAnalytics
) {
    ObaAnalytics.reportUiEvent(
        firebaseAnalytics, Application.get().plausibleInstance,
        PlausibleAnalytics.REPORT_TRIP_PLANNER_EVENT_URL,
        activity.getString(R.string.analytics_label_trip_plan), null
    )
}

/**
 * Rehydrates the form + results when reopened from a RealtimeService notification. Returns a cleared
 * [Intent] for the caller to set on the host (so a config change doesn't re-restore), or null when
 * there was nothing to restore.
 */
@Suppress("UNCHECKED_CAST", "DEPRECATION")
private fun maybeRestoreFromIntent(viewModel: TripPlanViewModel, intent: Intent?): Intent? {
    val extras = intent?.extras ?: return null
    if (extras.getSerializable(OTPConstants.INTENT_SOURCE) == null) return null
    val itineraries =
        (extras.getSerializable(OTPConstants.ITINERARIES) as? ArrayList<Itinerary>).orEmpty()
    if (itineraries.isEmpty()) return null

    val builder = TripRequestBuilder.initFromBundleSimple(extras)
    viewModel.restoreFrom(
        from = builder.from?.toPlaceItem(),
        to = builder.to?.toPlaceItem(),
        dateTimeMillis = builder.dateTime?.time ?: System.currentTimeMillis(),
        arriving = builder.arriveBy,
        itineraries = itineraries
    )
    return Intent()
}

private fun CustomAddress.toPlaceItem(): PlaceItem = PlaceItem(
    displayName = toString(),
    lat = if (isSet) latitude else null,
    lon = if (isSet) longitude else null
)
