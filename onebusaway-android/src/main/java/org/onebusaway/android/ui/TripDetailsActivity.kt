/*
 * Copyright (C) 2012-2026 Paul Watts (paulcwatts@gmail.com), University of South Florida,
 * Benjamin Du (bendu@me.com), Open Transit Software Foundation
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
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import org.onebusaway.android.travelbehavior.TravelBehaviorManager
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.tripdetails.DefaultTripDetailsRepository
import org.onebusaway.android.ui.tripdetails.TripDetailsRoute
import org.onebusaway.android.ui.tripdetails.TripDetailsViewModel
import org.onebusaway.android.util.DBUtil
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.PermissionUtils.NOTIFICATION_PERMISSION_REQUEST
import org.onebusaway.android.util.PreferenceUtils

/**
 * Shows a trip's stops along the vertical transit line, with the vehicle's live position.
 *
 * Compose + MVVM screen: the Activity is a thin host for [TripDetailsRoute]; trip state lives in
 * [TripDetailsViewModel]. The Activity keeps the [Builder] launch API (used by the arrivals
 * "show trip details" action and by NavigationServiceProvider for destination reminders) and owns
 * the Android-heavy destination-reminder flow (location/permission resolution + NavigationService).
 */
class TripDetailsActivity : AppCompatActivity() {

    private val tripId: String by lazy {
        intent.getStringExtra(TRIP_ID) ?: throw IllegalStateException("TripId should not be null")
    }
    private val stopId: String? by lazy { intent.getStringExtra(STOP_ID) }
    private val scrollMode: String? by lazy { intent.getStringExtra(SCROLL_MODE) }
    private val initialDestinationId: String? by lazy { intent.getStringExtra(DEST_ID) }

    private val viewModel: TripDetailsViewModel by viewModels {
        viewModelFactory {
            initializer {
                TripDetailsViewModel(
                    tripId, stopId, scrollMode,
                    DefaultTripDetailsRepository(applicationContext), initialDestinationId
                )
            }
        }
    }

    private lateinit var firebaseAnalytics: FirebaseAnalytics

    /** The service intent saved when we have to wait for the user to enable location settings. */
    private var pendingServiceIntent: Intent? = null

    private var tripEndReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)
        setContent {
            ObaTheme {
                TripDetailsRoute(
                    viewModel = viewModel,
                    onBack = { NavHelp.goUp(this) },
                    onShowOnMap = { routeId -> HomeActivity.start(this, routeId) },
                    onStopClick = { sid, name, direction ->
                        ArrivalsListActivity.Builder(this, sid)
                            .setUpMode(NavHelp.UP_MODE_BACK)
                            .setStopName(name)
                            .setStopDirection(direction)
                            .start()
                    },
                    onSetDestinationReminder = { index -> confirmDestinationReminder(index) }
                )
            }
        }
    }

    // -- Destination reminder flow (ported faithfully from the legacy TripDetailsListFragment) --

    private fun confirmDestinationReminder(position: Int) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.destination_reminder_dialog_msg)
            .setTitle(R.string.destination_reminder_dialog_title)
            .setPositiveButton(R.string.destination_reminder_confirm) { dialog, _ ->
                onDestinationReminderConfirmed(position)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.destination_reminder_cancel) { _, _ -> }
            .show()
    }

    private fun onDestinationReminderConfirmed(position: Int) {
        if (!LocationUtils.isLocationEnabled(this)) {
            // We still build the pending service intent so onActivityResult can start it.
            if (setUpNavigationService(position) == null) return
            askUserToTurnLocationOn()
            return
        }
        val prefs = Application.getPrefs()
        if (!prefs.getBoolean(getString(R.string.preference_key_never_show_change_location_mode_dialog), false) &&
            LocationUtils.getLocationMode(this) != Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
        ) {
            dialogForLocationModeChanges().show()
        }
        if (!prefs.getBoolean(getString(R.string.preference_key_never_show_destination_reminder_beta_dialog), false)) {
            destinationReminderBetaDialog().show()
        }
        ObaAnalytics.reportUiEvent(
            firebaseAnalytics, Application.get().plausibleInstance,
            PlausibleAnalytics.REPORT_DESTINATION_REMINDER_EVENT_URL,
            getString(R.string.analytics_label_destination_reminder),
            getString(R.string.analytics_label_destination_reminder_variant_started)
        )
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST
        )
        val serviceIntent = setUpNavigationService(position) ?: return
        startNavigationService(serviceIntent)
        Toast.makeText(
            Application.get(), getString(R.string.destination_reminder_title), Toast.LENGTH_LONG
        ).show()
        val currentTime = viewModel.lastResponse()?.currentTime ?: System.currentTimeMillis()
        TravelBehaviorManager.saveDestinationReminders(
            stopId, pendingServiceIntent?.getStringExtra(org.onebusaway.android.nav.NavigationService.DESTINATION_ID),
            tripId, viewModel.routeId(), currentTime
        )
    }

    /** Builds the NavigationService intent for the destination at [position]; flags the stop. */
    private fun setUpNavigationService(position: Int): Intent? {
        val response = viewModel.lastResponse() ?: return null
        val stopTimes = response.schedule?.stopTimes ?: return null
        if (position < 1 || position >= stopTimes.size) return null
        val destStop = response.refs.getStop(stopTimes[position].stopId)
        val lastStop = response.refs.getStop(stopTimes[position - 1].stopId)
        DBUtil.addToDB(lastStop)
        DBUtil.addToDB(destStop)
        val serviceIntent = Intent(this, org.onebusaway.android.nav.NavigationService::class.java).apply {
            putExtra(org.onebusaway.android.nav.NavigationService.DESTINATION_ID, destStop.id)
            putExtra(org.onebusaway.android.nav.NavigationService.BEFORE_STOP_ID, lastStop.id)
            putExtra(org.onebusaway.android.nav.NavigationService.TRIP_ID, tripId)
        }
        viewModel.setDestinationId(destStop.id)
        pendingServiceIntent = serviceIntent
        return serviceIntent
    }

    private fun askUserToTurnLocationOn() {
        @Suppress("DEPRECATION")
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        val request = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build()
        LocationServices.getSettingsClient(this).checkLocationSettings(request)
            .addOnCompleteListener { task ->
                try {
                    task.getResult(ApiException::class.java)
                } catch (e: ApiException) {
                    if (e.statusCode == LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                        try {
                            (e as ResolvableApiException)
                                .startResolutionForResult(this, REQUEST_ENABLE_LOCATION)
                        } catch (ignored: IntentSender.SendIntentException) {
                        } catch (ignored: ClassCastException) {
                        }
                    }
                }
            }
    }

    private fun dialogForLocationModeChanges(): Dialog {
        val view = layoutInflater.inflate(R.layout.change_locationmode_dialog, null)
        view.findViewById<CheckBox>(R.id.change_locationmode_never_ask_again)
            .setOnCheckedChangeListener { _, isChecked ->
                PreferenceUtils.saveBoolean(
                    getString(R.string.preference_key_never_show_change_location_mode_dialog), isChecked
                )
            }
        @Suppress("DEPRECATION")
        val icon = resources.getDrawable(android.R.drawable.ic_dialog_map).also {
            @Suppress("DEPRECATION")
            DrawableCompat.setTint(it, resources.getColor(R.color.theme_primary))
        }
        return MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_changelocationmode_title)
            .setIcon(icon)
            .setCancelable(false)
            .setView(view)
            .setPositiveButton(R.string.rt_yes) { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(R.string.rt_no) { _, _ -> }
            .create()
    }

    private fun destinationReminderBetaDialog(): Dialog {
        val view = layoutInflater.inflate(R.layout.destination_reminder_beta_dialog, null)
        view.findViewById<CheckBox>(R.id.destination_reminder_beta_never_show_again)
            .setOnCheckedChangeListener { _, isChecked ->
                PreferenceUtils.saveBoolean(
                    getString(R.string.preference_key_never_show_destination_reminder_beta_dialog), isChecked
                )
            }
        @Suppress("DEPRECATION")
        val icon = resources.getDrawable(android.R.drawable.ic_dialog_alert).also {
            @Suppress("DEPRECATION")
            DrawableCompat.setTint(it, resources.getColor(R.color.theme_primary))
        }
        return MaterialAlertDialogBuilder(this)
            .setTitle(R.string.destination_reminder_beta_title)
            .setIcon(icon)
            .setCancelable(false)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ -> }
            .create()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ENABLE_LOCATION) {
            if (resultCode == Activity.RESULT_OK) {
                pendingServiceIntent?.let { startNavigationService(it) }
            }
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun startNavigationService(serviceIntent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(serviceIntent)
        } else {
            applicationContext.startService(serviceIntent)
        }
        registerTripEndReceiver()
    }

    /** Clears the destination flag when the NavigationService is destroyed (trip cancelled/ended). */
    private fun registerTripEndReceiver() {
        if (tripEndReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_SERVICE_DESTROYED) {
                    viewModel.setDestinationId(null)
                    runCatching { unregisterReceiver(this) }
                    tripEndReceiver = null
                }
            }
        }
        tripEndReceiver = receiver
        val filter = IntentFilter(ACTION_SERVICE_DESTROYED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        tripEndReceiver?.let { runCatching { unregisterReceiver(it) } }
        tripEndReceiver = null
        super.onDestroy()
    }

    /** Fluent launcher for the trip details screen. */
    class Builder(private val context: Context, tripId: String) {

        private val intent = Intent(context, TripDetailsActivity::class.java)
            .putExtra(TRIP_ID, tripId)

        fun setStopId(stopId: String?): Builder = apply { intent.putExtra(STOP_ID, stopId) }

        fun setScrollMode(mode: String?): Builder = apply { intent.putExtra(SCROLL_MODE, mode) }

        fun setDestinationId(stopId: String?): Builder = apply { intent.putExtra(DEST_ID, stopId) }

        fun setUpMode(mode: String?): Builder = apply { intent.putExtra(NavHelp.UP_MODE, mode) }

        fun getIntent(): Intent = intent

        fun start() {
            context.startActivity(intent)
        }
    }

    companion object {

        const val TRIP_ID = ".TripId"
        const val STOP_ID = ".StopId"
        const val SCROLL_MODE = ".ScrollMode"
        const val SCROLL_MODE_VEHICLE = "vehicle"
        const val SCROLL_MODE_STOP = "stop"
        const val DEST_ID = ".DestinationId"
        const val ACTION_SERVICE_DESTROYED = "NavigationServiceDestroyed"
        const val REQUEST_ENABLE_LOCATION = 1

        @JvmStatic
        fun start(context: Context, tripId: String) {
            Builder(context, tripId).start()
        }

        @JvmStatic
        fun start(context: Context, tripId: String, mode: String) {
            Builder(context, tripId).setScrollMode(mode).start()
        }

        @JvmStatic
        fun start(context: Context, tripId: String, stopId: String, mode: String) {
            Builder(context, tripId).setStopId(stopId).setScrollMode(mode).start()
        }
    }
}
