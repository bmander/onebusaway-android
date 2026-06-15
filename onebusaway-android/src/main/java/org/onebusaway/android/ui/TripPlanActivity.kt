/*
 * Copyright (C) 2016-2017 Cambridge Systematics, Inc., University of South Florida,
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
package org.onebusaway.android.ui

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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.directions.util.ConversionUtils
import org.onebusaway.android.directions.util.CustomAddress
import org.onebusaway.android.directions.util.OTPConstants
import org.onebusaway.android.directions.util.TripRequestBuilder
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.tripplan.AdvancedSettings
import org.onebusaway.android.ui.tripplan.PlaceItem
import org.onebusaway.android.ui.tripplan.PlanResult
import org.onebusaway.android.ui.tripplan.TripPlanLocationPickerActivity
import org.onebusaway.android.ui.tripplan.TripPlanRoute
import org.onebusaway.android.ui.tripplan.TripPlanViewModel
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.UIUtils
import org.opentripplanner.api.model.Itinerary

/**
 * Compose + MVVM trip planning. The Activity is a thin host for [TripPlanRoute] (form +
 * results bottom sheet); state lives in [TripPlanViewModel]. The Activity owns the Android-heavy
 * pieces: the date/time pickers, the contacts picker, current-location reads, the advanced-options
 * dialog, the error dialog, and rehydrating from a RealtimeService trip-update notification.
 */
@AndroidEntryPoint
class TripPlanActivity : AppCompatActivity() {

    private val viewModel: TripPlanViewModel by viewModels()

    @Inject
    lateinit var regionRepository: RegionRepository

    private lateinit var firebaseAnalytics: FirebaseAnalytics

    /** Which endpoint a pending contacts pick should populate. */
    private var contactsTarget: ((PlaceItem) -> Unit)? = null

    private val contactsLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        formattedAddress(uri)?.let { address ->
            contactsTarget?.invoke(PlaceItem(displayName = address))
        }
        contactsTarget = null
    }

    /** Which endpoint a pending map pick should populate. */
    private var mapPickTarget: ((PlaceItem) -> Unit)? = null

    private val mapPickLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val lat = data.getDoubleExtra(MapParams.CENTER_LAT, Double.NaN)
            val lon = data.getDoubleExtra(MapParams.CENTER_LON, Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) {
                mapPickTarget?.invoke(
                    PlaceItem(displayName = getString(R.string.trip_plan_map_location), lat = lat, lon = lon)
                )
            }
        }
        mapPickTarget = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)
        maybeRestoreFromIntent(intent)
        setContent {
            ObaTheme {
                TripPlanRoute(
                    viewModel = viewModel,
                    fragmentManager = supportFragmentManager,
                    onBack = { finish() },
                    onPickDate = ::pickDate,
                    onPickTime = ::pickTime,
                    onFromCurrentLocation = { setCurrentLocation(viewModel::setFrom) },
                    onToCurrentLocation = { setCurrentLocation(viewModel::setTo) },
                    onFromContacts = { launchContacts(viewModel::setFrom) },
                    onToContacts = { launchContacts(viewModel::setTo) },
                    onFromPickOnMap = { launchMapPicker(viewModel::setFrom, viewModel.formState.value.from) },
                    onToPickOnMap = { launchMapPicker(viewModel::setTo, viewModel.formState.value.to) },
                    onAdvancedSettings = ::showAdvancedSettings,
                    onReportProblem = ::reportProblem
                )
            }
        }
        observePlanState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeRestoreFromIntent(intent)
    }

    /** Surfaces plan errors as a dialog and reports analytics on submit. */
    private fun observePlanState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.planState.collect { state ->
                    when (state) {
                        is PlanResult.Loading -> reportPlanAnalytics()
                        is PlanResult.Error -> {
                            showFeedbackDialog(state.message)
                            viewModel.clearPlanResult()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    // -- Date / time pickers (platform), feeding the ViewModel ------------------------------------

    private fun pickDate() {
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
        picker.show(supportFragmentManager, "DATE_PICKER")
    }

    private fun pickTime() {
        val current = viewModel.formState.value.dateTimeMillis
        val calendar = Calendar.getInstance().apply { timeInMillis = current }
        val timeFormat = if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
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
        picker.show(supportFragmentManager, "TIME_PICKER")
    }

    // -- Current location + contacts (platform) --------------------------------------------------

    private fun setCurrentLocation(target: (PlaceItem) -> Unit) {
        val location = Application.getLastKnownLocation(applicationContext)
        if (location == null) {
            Toast.makeText(this, getString(R.string.no_location_permission), Toast.LENGTH_SHORT).show()
        }
        target(
            PlaceItem(
                displayName = getString(R.string.tripplanner_current_location),
                lat = location?.latitude,
                lon = location?.longitude,
                isCurrentLocation = true
            )
        )
    }

    private fun launchContacts(target: (PlaceItem) -> Unit) {
        contactsTarget = target
        val intent = Intent(Intent.ACTION_PICK)
            .setType(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_TYPE)
        contactsLauncher.launch(intent)
    }

    /** Opens the map picker centered on the endpoint's current point (or the device location). */
    private fun launchMapPicker(target: (PlaceItem) -> Unit, initial: PlaceItem?) {
        mapPickTarget = target
        val center = if (initial?.hasCoordinates == true) {
            LocationUtils.makeLocation(initial.lat!!, initial.lon!!)
        } else {
            LocationUtils.getSearchCenter(applicationContext)
        }
        mapPickLauncher.launch(TripPlanLocationPickerActivity.newIntent(this, center))
    }

    private fun formattedAddress(uri: Uri): String? {
        val projection = arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                return cursor.getString(index)?.replace("\n", ", ")
            }
        }
        return null
    }

    // -- Advanced options dialog (ported from the legacy form) -----------------------------------

    private fun showAdvancedSettings() {
        val current = viewModel.formState.value
        val imperial = !PreferenceUtils.getUnitsAreMetricFromPreferences(this)
        val dialog = MaterialAlertDialogBuilder(this)
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

        val options = resources.getStringArray(R.array.transit_mode_array).toMutableList()
        if (!Application.isBikeshareEnabled()) {
            options.remove(getString(R.string.transit_mode_bikeshare))
            options.remove(getString(R.string.transit_mode_transit_and_bikeshare))
        }
        travelBy.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
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
                ?.setText(getString(R.string.feet_abbreviation))
        }

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val modeResources = resources.obtainTypedArray(R.array.transit_mode_array)
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

            val settings = AdvancedSettings(modeId, maxWalkMeters, minimizeTransfers.isChecked, wheelchair.isChecked)
            viewModel.applyAdvancedSettings(settings)

            PreferenceUtils.saveInt(getString(R.string.preference_key_trip_plan_travel_by), modeId)
            PreferenceUtils.saveDouble(
                getString(R.string.preference_key_trip_plan_maximum_walking_distance),
                maxWalkMeters ?: Double.MAX_VALUE
            )
            PreferenceUtils.saveBoolean(getString(R.string.preference_key_trip_plan_minimize_transfers), minimizeTransfers.isChecked)
            PreferenceUtils.saveBoolean(getString(R.string.preference_key_trip_plan_avoid_stairs), wheelchair.isChecked)
            dialog.dismiss()
        }
    }

    // -- Errors, reporting, analytics, notification re-entry --------------------------------------

    private fun showFeedbackDialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tripplanner_error_dialog_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.report_problem_report) { _, _ -> reportProblem() }
            .show()
    }

    private fun reportProblem() {
        val email = regionRepository.region.value?.otpContactEmail
        if (email.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.tripplanner_no_contact), Toast.LENGTH_SHORT).show()
            return
        }
        val location = Application.getLastKnownLocation(applicationContext)
        val locationString = location?.let { LocationUtils.printLocationDetails(it) }
        UIUtils.sendEmail(this, email, locationString, null, true)
        ObaAnalytics.reportUiEvent(
            firebaseAnalytics, Application.get().plausibleInstance,
            PlausibleAnalytics.REPORT_TRIP_PLANNER_EVENT_URL,
            getString(R.string.analytics_label_app_feedback_otp), null
        )
    }

    private fun reportPlanAnalytics() {
        ObaAnalytics.reportUiEvent(
            firebaseAnalytics, Application.get().plausibleInstance,
            PlausibleAnalytics.REPORT_TRIP_PLANNER_EVENT_URL,
            getString(R.string.analytics_label_trip_plan), null
        )
    }

    /** Rehydrates the form + results when reopened from a RealtimeService notification. */
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun maybeRestoreFromIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        if (extras.getSerializable(OTPConstants.INTENT_SOURCE) == null) return
        val itineraries = (extras.getSerializable(OTPConstants.ITINERARIES) as? ArrayList<Itinerary>).orEmpty()
        if (itineraries.isEmpty()) return

        val builder = TripRequestBuilder.initFromBundleSimple(extras)
        viewModel.restoreFrom(
            from = builder.from?.toPlaceItem(),
            to = builder.to?.toPlaceItem(),
            dateTimeMillis = builder.dateTime?.time ?: System.currentTimeMillis(),
            arriving = builder.arriveBy,
            itineraries = itineraries
        )
        setIntent(Intent())
    }

    private fun CustomAddress.toPlaceItem(): PlaceItem = PlaceItem(
        displayName = toString(),
        lat = if (isSet) latitude else null,
        lon = if (isSet) longitude else null
    )

    companion object {
        @JvmStatic
        fun start(context: android.content.Context) {
            context.startActivity(Intent(context, TripPlanActivity::class.java))
        }
    }
}
