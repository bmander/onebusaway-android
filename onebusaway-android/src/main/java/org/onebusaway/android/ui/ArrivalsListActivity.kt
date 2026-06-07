/*
 * Copyright (C) 2012-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com), Microsoft Corporation
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
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.report.ui.InfrastructureIssueActivity
import org.onebusaway.android.ui.arrivals.ArrivalActionHandler
import org.onebusaway.android.ui.arrivals.ArrivalActions
import org.onebusaway.android.ui.arrivals.ArrivalsRoute
import org.onebusaway.android.ui.arrivals.ArrivalsUiState
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.ui.arrivals.DefaultArrivalsRepository
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.util.DBUtil
import org.onebusaway.android.util.ReminderUtils
import org.onebusaway.android.util.ShowcaseViewUtils
import org.onebusaway.android.util.UIUtils
import java.util.HashMap

/**
 * Shows the real-time arrivals and departures for a stop.
 *
 * Compose + MVVM host: state and 60s polling live in [ArrivalsViewModel] / [ArrivalsRoute].
 * The stop id arrives via the intent data URI (preserved for the many launch sites and the
 * launcher-shortcut path). The map slide-panel in HomeActivity still uses the legacy
 * ArrivalsListFragment — this screen is the standalone path only.
 */
class ArrivalsListActivity : AppCompatActivity() {

    private val stopId: String by lazy { intent.data?.lastPathSegment.orEmpty() }

    private val viewModel: ArrivalsViewModel by viewModels {
        viewModelFactory {
            initializer {
                ArrivalsViewModel(stopId, DefaultArrivalsRepository(applicationContext))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hosted in an XML wrapper (not the setContent extension) so the root has the
        // R.id.fragment_arrivals_list that the shared SituationDialogFragment anchors to.
        setContentView(R.layout.activity_arrivals_compose)
        val initialTitle = intent.getStringExtra(ArrivalsListFragment.STOP_NAME).orEmpty()
        val handler = createActionHandler()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            ObaTheme {
                ArrivalsRoute(
                    viewModel = viewModel,
                    initialTitle = initialTitle,
                    handler = handler,
                    onBack = { NavHelp.goUp(this) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new stop arrived (singleTop); rebuild with a fresh ViewModel bound to it
        setIntent(intent)
        recreate()
    }

    override fun onPause() {
        ShowcaseViewUtils.hideShowcaseView()
        super.onPause()
    }

    private fun currentContent(): ArrivalsUiState.Content? =
        viewModel.state.value as? ArrivalsUiState.Content

    /**
     * The per-arrival and stop-level navigation/dialog actions. They live here (not in the
     * ViewModel) because they need the Activity Context, FragmentManager, and the response-derived
     * data carried in the current [ArrivalsUiState.Content]. Each is a faithful port of the legacy
     * ArrivalsListFragment menu handlers.
     */
    private fun createActionHandler(): ArrivalActionHandler {
        val activity = this
        return object : ArrivalActionHandler {
            override fun onRouteFavorite(actions: ArrivalActions) {
                val dialog = RouteFavoriteDialogFragment.Builder(actions.routeId, actions.headsign)
                    .setRouteShortName(actions.routeShortName)
                    .setRouteLongName(actions.routeLongName)
                    .setRouteUrl(actions.scheduleUrl)
                    .setStopId(actions.stopId)
                    .setFavorite(!actions.isRouteFavorite)
                    .build()
                dialog.setCallback { saved -> if (saved) viewModel.manualRefresh() }
                dialog.show(supportFragmentManager, RouteFavoriteDialogFragment.TAG)
            }

            override fun onShowVehiclesOnMap(arrival: ArrivalInfo) {
                DBUtil.addRouteToDB(activity, arrival)
                HomeActivity.start(activity, arrival.info.routeId)
            }

            override fun onShowTripStatus(arrival: ArrivalInfo) {
                DBUtil.addRouteToDB(activity, arrival)
                TripDetailsActivity.Builder(activity, arrival.info.tripId)
                    .setStopId(arrival.info.stopId)
                    .setScrollMode(TripDetailsListFragment.SCROLL_MODE_STOP)
                    .setUpMode(NavHelp.UP_MODE_BACK)
                    .start()
            }

            override fun onSetReminder(arrival: ArrivalInfo) {
                if (!ReminderUtils.shouldShowReminders()) {
                    Toast.makeText(activity, R.string.reminder_not_enabled, Toast.LENGTH_SHORT).show()
                    return
                }
                val info = arrival.info
                TripInfoActivity.start(
                    activity,
                    info.tripId,
                    info.stopId,
                    info.routeId,
                    info.shortName,
                    currentContent()?.header?.name.orEmpty(),
                    ReminderUtils.getReminderDepartureTime(info),
                    info.headsign,
                    info.stopSequence,
                    info.serviceDate,
                    info.vehicleId
                )
            }

            override fun onShowRouteSchedule(scheduleUrl: String) {
                UIUtils.goToUrl(activity, scheduleUrl)
            }

            override fun onReportArrivalProblem(actions: ArrivalActions) {
                val content = currentContent() ?: return
                val info = content.arrivals.firstOrNull { it.info.tripId == actions.tripId }?.info
                    ?: return
                InfrastructureIssueActivity.startWithService(
                    activity,
                    stopReportIntent(content),
                    getString(R.string.ri_selected_service_trip),
                    info,
                    actions.agencyName,
                    actions.blockId
                )
            }

            override fun onShowAlert(alertId: String) {
                val situation = viewModel.situation(alertId) ?: return
                val dialog = SituationDialogFragment.newInstance(situation)
                dialog.setListener(object : SituationDialogFragment.Listener {
                    override fun onDismiss(isAlertHidden: Boolean) {
                        if (isAlertHidden) viewModel.manualRefresh()
                    }

                    override fun onUndo() {
                        viewModel.manualRefresh()
                    }
                })
                dialog.show(supportFragmentManager, SituationDialogFragment.TAG)
            }

            override fun onShowStopDetails() {
                val content = currentContent() ?: return
                val text = UIUtils.createStopDetailsDialogText(
                    activity,
                    content.header.name,
                    content.stopUserName,
                    content.stopCode,
                    content.header.direction,
                    content.routeFilterOptions.map { it.displayName }
                )
                UIUtils.buildAlertDialog(activity, text.first, text.second).show()
            }

            override fun onReportStopProblem() {
                val content = currentContent() ?: return
                InfrastructureIssueActivity.startWithService(
                    activity,
                    stopReportIntent(content),
                    getString(R.string.ri_selected_service_stop)
                )
            }
        }
    }

    /** The HomeActivity-style intent the report flow expects (stop focused on the map). */
    private fun stopReportIntent(content: ArrivalsUiState.Content): Intent =
        ArrivalsListFragment.makeIntent(
            this,
            content.header.stopId,
            content.header.name,
            content.stopCode,
            content.stopLat,
            content.stopLon
        )

    /** Retained for the legacy ArrivalsListFragment, which no longer hosts here (returns null). */
    fun getArrivalsListFragment(): ArrivalsListFragment? = null

    class Builder {

        private val context: Context

        /** The built intent; Java callers see this as getIntent(). */
        val intent: Intent

        constructor(context: Context, stopId: String) {
            this.context = context
            intent = Intent(context, ArrivalsListActivity::class.java)
            intent.data = Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId)
        }

        /**
         * @param stop the [ObaStop] to be shown
         * @param routes route display names that serve this stop, keyed by route id
         */
        constructor(context: Context, stop: ObaStop, routes: HashMap<String, ObaRoute>) {
            this.context = context
            intent = Intent(context, ArrivalsListActivity::class.java)
            intent.data = Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stop.id)
            setStopName(stop.name)
            setStopDirection(stop.direction)
            setStopRoutes(UIUtils.serializeRouteDisplayNames(stop, routes))
        }

        fun setStopName(stopName: String?): Builder {
            intent.putExtra(ArrivalsListFragment.STOP_NAME, stopName)
            return this
        }

        fun setStopDirection(stopDir: String?): Builder {
            intent.putExtra(ArrivalsListFragment.STOP_DIRECTION, stopDir)
            return this
        }

        fun setStopRoutes(routes: String?): Builder {
            intent.putExtra(ArrivalsListFragment.STOP_ROUTES, routes)
            return this
        }

        fun setUpMode(mode: String?): Builder {
            intent.putExtra(NavHelp.UP_MODE, mode)
            return this
        }

        fun start() {
            context.startActivity(intent)
        }
    }

    companion object {

        @JvmStatic
        fun start(context: Context, stopId: String) {
            Builder(context, stopId).start()
        }

        @JvmStatic
        fun start(context: Context, stop: ObaStop, routes: HashMap<String, ObaRoute>) {
            Builder(context, stop, routes).start()
        }
    }
}
