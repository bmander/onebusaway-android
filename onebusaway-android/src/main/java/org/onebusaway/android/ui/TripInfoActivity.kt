/*
 * Copyright (C) 2011-2026 Paul Watts (paulcwatts@gmail.com), University of South Florida,
 * Open Transit Software Foundation
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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.tripinfo.TripInfoArgs
import org.onebusaway.android.ui.tripinfo.TripInfoEvent
import org.onebusaway.android.ui.tripinfo.TripInfoRoute
import org.onebusaway.android.ui.tripinfo.TripInfoViewModel
import org.onebusaway.android.util.PermissionUtils.NOTIFICATION_PERMISSION_REQUEST
import org.onebusaway.android.util.ReminderUtils

/**
 * Creates or edits a trip arrival reminder.
 *
 * Compose + MVVM screen: the Activity is a thin host for [TripInfoRoute]; the merged reminder data
 * and form state live in [TripInfoViewModel]. The trip/stop ids ride in the intent's
 * [ObaContract.Trips] data URI (both launch paths), and the arrivals "set reminder" action passes
 * the trip context as extras so a brand-new reminder needs no DB round-trip.
 */
@AndroidEntryPoint
class TripInfoActivity : AppCompatActivity() {

    private val args: TripInfoArgs by lazy {
        val segments = intent.data!!.pathSegments
        TripInfoArgs(
            tripId = segments[1],
            stopId = segments[2],
            routeId = intent.getStringExtra(ROUTE_ID),
            routeName = intent.getStringExtra(ROUTE_NAME),
            stopName = intent.getStringExtra(STOP_NAME),
            headsign = intent.getStringExtra(HEADSIGN),
            departTime = intent.getLongExtra(DEPARTURE_TIME, 0),
            stopSequence = intent.getIntExtra(STOP_SEQUENCE, 0),
            serviceDate = intent.getLongExtra(SERVICE_DATE, 0),
            vehicleId = intent.getStringExtra(VEHICLE_ID)
        )
    }

    private val viewModel: TripInfoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Normalize the trip/stop ids from the data URI into extras so the view model can read the full
        // TripInfoArgs from SavedStateHandle (seeded from extras, not the data URI). Must run before the
        // view model is first accessed below.
        intent.putExtra(TRIP_ID, args.tripId)
        intent.putExtra(STOP_ID, args.stopId)
        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    TripInfoEvent.Saved -> {
                        Toast.makeText(this@TripInfoActivity, R.string.trip_info_saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }

                    TripInfoEvent.SaveFailed -> Toast.makeText(
                        this@TripInfoActivity, R.string.failed_to_set_reminder, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        setContent {
            ObaTheme {
                TripInfoRoute(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onSave = { saveReminder() },
                    onDelete = { confirmDelete() },
                    onShowRoute = { viewModel.routeId()?.let { RouteInfoActivity.start(this, it) } },
                    onShowStop = {
                        ArrivalsListActivity.Builder(this, args.stopId)
                            .setStopName(viewModel.stopName())
                            .start()
                    }
                )
            }
        }
    }

    private fun saveReminder() {
        // Reminders arrive as push notifications — make sure we're allowed to post them.
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST
        )
        viewModel.save()
    }

    private fun confirmDelete() {
        confirmDeleteReminder(this) {
            ReminderUtils.requestDeleteAlarm(this, args.tripUri)
            finish()
        }
    }

    companion object {

        // SavedStateHandle keys for the view model. TRIP_ID/STOP_ID are normalized from the data URI
        // in onCreate; the rest ride as intent extras from the "create reminder" launch path.
        const val TRIP_ID = ".TripId"
        const val STOP_ID = ".StopId"
        const val ROUTE_ID = ".RouteId"
        const val ROUTE_NAME = ".RouteName"
        const val STOP_NAME = ".StopName"
        const val HEADSIGN = ".Headsign"
        const val DEPARTURE_TIME = ".Depart"
        const val STOP_SEQUENCE = ".StopSequence"
        const val SERVICE_DATE = ".ServiceDate"
        const val VEHICLE_ID = ".VehicleID"

        /** Opens an existing reminder for editing — the data is read from the Trips table. */
        fun start(context: Context, tripId: String, stopId: String) {
            context.startActivity(intentFor(context, tripId, stopId))
        }

        /** Creates a reminder for an upcoming arrival, with the trip context passed as extras. */
        fun start(
            context: Context,
            tripId: String,
            stopId: String,
            routeId: String?,
            routeName: String?,
            stopName: String?,
            departureTime: Long,
            headsign: String?,
            stopSequence: Int,
            serviceDate: Long,
            vehicleId: String?
        ) {
            val intent = intentFor(context, tripId, stopId)
                .putExtra(ROUTE_ID, routeId)
                .putExtra(ROUTE_NAME, routeName)
                .putExtra(STOP_NAME, stopName)
                .putExtra(DEPARTURE_TIME, departureTime)
                .putExtra(HEADSIGN, headsign)
                .putExtra(STOP_SEQUENCE, stopSequence)
                .putExtra(SERVICE_DATE, serviceDate)
                .putExtra(VEHICLE_ID, vehicleId)
            context.startActivity(intent)
        }

        private fun intentFor(context: Context, tripId: String, stopId: String): Intent =
            Intent(context, TripInfoActivity::class.java)
                .setData(ObaContract.Trips.buildUri(tripId, stopId))
    }
}

/**
 * The "this reminder will be deleted" confirmation dialog, shared by this editor's delete action
 * and the My Reminders list's long-press delete.
 */
internal fun confirmDeleteReminder(context: Context, onConfirm: () -> Unit) {
    MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_App_DeleteDialog)
        .setTitle(R.string.trip_info_delete)
        .setMessage(R.string.trip_info_delete_trip)
        .setIcon(R.drawable.baseline_delete_forever_48)
        .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
