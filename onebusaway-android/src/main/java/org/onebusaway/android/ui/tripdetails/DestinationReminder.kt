/*
 * Copyright (C) 2012-2026 Paul Watts (paulcwatts@gmail.com), University of South Florida,
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
package org.onebusaway.android.ui.tripdetails

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Build
import android.provider.Settings
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.analytics.FirebaseAnalytics
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.nav.NavigationService
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.travelbehavior.TravelBehaviorManager
import org.onebusaway.android.ui.TripDetailsActivity
import org.onebusaway.android.ui.compose.findActivity
import org.onebusaway.android.util.DBUtil
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.PermissionUtils.NOTIFICATION_PERMISSION_REQUEST

/**
 * The destination-reminder flow (set a reminder to alight at a chosen stop), as a reusable Compose
 * action shared by [TripDetailsActivity] and the trip-details NavHost destination. Ported faithfully
 * from the legacy `TripDetailsActivity` methods, but using `ActivityResultContracts` for the
 * location-settings resolution (instead of `startActivityForResult`/`onActivityResult`) and a
 * [DisposableEffect] for the trip-end receiver, so it works in a NavHost destination that has no
 * Activity result/lifecycle callbacks of its own.
 *
 * Returns an `(stopIndex) -> Unit` to wire to `TripDetailsRoute.onSetDestinationReminder`.
 */
@Composable
internal fun rememberDestinationReminderAction(
    viewModel: TripDetailsViewModel,
    prefsRepository: PreferencesRepository,
    tripId: String,
    stopId: String?,
): (stopIndex: Int) -> Unit {
    val context = LocalContext.current
    val activity = context.findActivity()

    // Saved when we must wait for the user to enable location settings; started on the OK result.
    var pendingServiceIntent by remember { mutableStateOf<Intent?>(null) }

    fun startNavigationService(serviceIntent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.applicationContext.startForegroundService(serviceIntent)
        } else {
            context.applicationContext.startService(serviceIntent)
        }
    }

    // Replaces startResolutionForResult(...) + onActivityResult(REQUEST_ENABLE_LOCATION).
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingServiceIntent?.let { startNavigationService(it) }
        }
    }

    fun askUserToTurnLocationOn() {
        @Suppress("DEPRECATION")
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        val request = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build()
        LocationServices.getSettingsClient(context).checkLocationSettings(request)
            .addOnCompleteListener { task ->
                try {
                    task.getResult(ApiException::class.java)
                } catch (e: ApiException) {
                    if (e.statusCode == LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                        try {
                            val resolution = (e as ResolvableApiException).resolution
                            locationSettingsLauncher.launch(
                                IntentSenderRequest.Builder(resolution.intentSender).build()
                            )
                        } catch (ignored: IntentSender.SendIntentException) {
                        } catch (ignored: ClassCastException) {
                        }
                    }
                }
            }
    }

    /** Builds the NavigationService intent for the destination at [position]; flags the stop. */
    fun setUpNavigationService(position: Int): Intent? {
        val response = viewModel.lastResponse() ?: return null
        val stopTimes = response.schedule?.stopTimes ?: return null
        if (position < 1 || position >= stopTimes.size) return null
        val destStop = response.refs.getStop(stopTimes[position].stopId)
        val lastStop = response.refs.getStop(stopTimes[position - 1].stopId)
        DBUtil.addToDB(lastStop)
        DBUtil.addToDB(destStop)
        val serviceIntent = Intent(context, NavigationService::class.java).apply {
            putExtra(NavigationService.DESTINATION_ID, destStop.id)
            putExtra(NavigationService.BEFORE_STOP_ID, lastStop.id)
            putExtra(NavigationService.TRIP_ID, tripId)
        }
        viewModel.setDestinationId(destStop.id)
        pendingServiceIntent = serviceIntent
        return serviceIntent
    }

    fun dialogForLocationModeChanges() {
        val view = activity.layoutInflater.inflate(R.layout.change_locationmode_dialog, null)
        view.findViewById<CheckBox>(R.id.change_locationmode_never_ask_again)
            .setOnCheckedChangeListener { _, isChecked ->
                prefsRepository.setBoolean(
                    R.string.preference_key_never_show_change_location_mode_dialog, isChecked
                )
            }
        @Suppress("DEPRECATION")
        val icon = activity.resources.getDrawable(android.R.drawable.ic_dialog_map).also {
            @Suppress("DEPRECATION")
            DrawableCompat.setTint(it, activity.resources.getColor(R.color.theme_primary))
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.main_changelocationmode_title)
            .setIcon(icon)
            .setCancelable(false)
            .setView(view)
            .setPositiveButton(R.string.rt_yes) { _, _ ->
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(R.string.rt_no) { _, _ -> }
            .show()
    }

    fun destinationReminderBetaDialog() {
        val view = activity.layoutInflater.inflate(R.layout.destination_reminder_beta_dialog, null)
        view.findViewById<CheckBox>(R.id.destination_reminder_beta_never_show_again)
            .setOnCheckedChangeListener { _, isChecked ->
                prefsRepository.setBoolean(
                    R.string.preference_key_never_show_destination_reminder_beta_dialog, isChecked
                )
            }
        @Suppress("DEPRECATION")
        val icon = activity.resources.getDrawable(android.R.drawable.ic_dialog_alert).also {
            @Suppress("DEPRECATION")
            DrawableCompat.setTint(it, activity.resources.getColor(R.color.theme_primary))
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.destination_reminder_beta_title)
            .setIcon(icon)
            .setCancelable(false)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ -> }
            .show()
    }

    fun onDestinationReminderConfirmed(position: Int) {
        if (!LocationUtils.isLocationEnabled(context)) {
            // Still build the pending service intent so the location-settings result can start it.
            if (setUpNavigationService(position) == null) return
            askUserToTurnLocationOn()
            return
        }
        if (!prefsRepository.getBoolean(R.string.preference_key_never_show_change_location_mode_dialog, false) &&
            LocationUtils.getLocationMode(context) != Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
        ) {
            dialogForLocationModeChanges()
        }
        if (!prefsRepository.getBoolean(R.string.preference_key_never_show_destination_reminder_beta_dialog, false)) {
            destinationReminderBetaDialog()
        }
        ObaAnalytics.reportUiEvent(
            FirebaseAnalytics.getInstance(context), Application.get().plausibleInstance,
            PlausibleAnalytics.REPORT_DESTINATION_REMINDER_EVENT_URL,
            context.getString(R.string.analytics_label_destination_reminder),
            context.getString(R.string.analytics_label_destination_reminder_variant_started)
        )
        ActivityCompat.requestPermissions(
            activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST
        )
        val serviceIntent = setUpNavigationService(position) ?: return
        startNavigationService(serviceIntent)
        Toast.makeText(
            context, context.getString(R.string.destination_reminder_title), Toast.LENGTH_LONG
        ).show()
        val currentTime = viewModel.lastResponse()?.currentTime ?: System.currentTimeMillis()
        TravelBehaviorManager.saveDestinationReminders(
            stopId, serviceIntent.getStringExtra(NavigationService.DESTINATION_ID),
            tripId, viewModel.routeId(), currentTime
        )
    }

    fun confirmDestinationReminder(position: Int) {
        MaterialAlertDialogBuilder(context)
            .setMessage(R.string.destination_reminder_dialog_msg)
            .setTitle(R.string.destination_reminder_dialog_title)
            .setPositiveButton(R.string.destination_reminder_confirm) { dialog, _ ->
                onDestinationReminderConfirmed(position)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.destination_reminder_cancel) { _, _ -> }
            .show()
    }

    // Clears the destination flag when the NavigationService is destroyed (trip cancelled/ended).
    // Registered for the lifetime of the hosting composable (replaces the Activity's lazy register +
    // onDestroy unregister).
    DisposableEffect(activity, viewModel) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == TripDetailsActivity.ACTION_SERVICE_DESTROYED) {
                    viewModel.setDestinationId(null)
                }
            }
        }
        val filter = IntentFilter(TripDetailsActivity.ACTION_SERVICE_DESTROYED)
        ContextCompat.registerReceiver(
            activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { activity.unregisterReceiver(receiver) } }
    }

    return { stopIndex -> confirmDestinationReminder(stopIndex) }
}
