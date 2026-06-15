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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.tripdetails.TripDetailsRoute
import org.onebusaway.android.ui.tripdetails.TripDetailsViewModel
import org.onebusaway.android.ui.tripdetails.rememberDestinationReminderAction

/**
 * Shows a trip's stops along the vertical transit line, with the vehicle's live position.
 *
 * Compose + MVVM screen: the Activity is a thin host for [TripDetailsRoute]; trip state lives in
 * [TripDetailsViewModel] and the destination-reminder flow in [rememberDestinationReminderAction]
 * (shared with the trip-details NavHost destination). The Activity keeps the [Builder] launch API
 * (used by the arrivals "show trip details" action, the map vehicle tap, and NavigationServiceProvider).
 */
@AndroidEntryPoint
class TripDetailsActivity : AppCompatActivity() {

    private val tripId: String by lazy {
        intent.getStringExtra(TRIP_ID) ?: throw IllegalStateException("TripId should not be null")
    }
    private val stopId: String? by lazy { intent.getStringExtra(STOP_ID) }

    @Inject
    lateinit var prefsRepository: PreferencesRepository

    private val viewModel: TripDetailsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    onSetDestinationReminder = rememberDestinationReminderAction(
                        viewModel = viewModel,
                        prefsRepository = prefsRepository,
                        tripId = tripId,
                        stopId = stopId,
                    )
                )
            }
        }
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

        /** Broadcast action the [org.onebusaway.android.nav.NavigationService] sends when destroyed. */
        const val ACTION_SERVICE_DESTROYED = "NavigationServiceDestroyed"

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
