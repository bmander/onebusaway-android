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
package org.onebusaway.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.onebusaway.android.R
import org.onebusaway.android.report.ui.InfrastructureIssueActivity
import org.onebusaway.android.ui.arrivals.ArrivalActionHandler
import org.onebusaway.android.ui.arrivals.ArrivalActions
import org.onebusaway.android.ui.arrivals.ArrivalsIntents
import org.onebusaway.android.ui.arrivals.ArrivalsUiState
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.util.DBUtil
import org.onebusaway.android.util.ReminderUtils
import org.onebusaway.android.util.UIUtils

/**
 * Builds the [ArrivalActionHandler] shared by the standalone arrivals activity and the map panel.
 * The only behavioral difference is [onShowRouteOnMap]: the standalone launches HomeActivity in
 * route mode, while the panel drives the existing map. Everything else (favorite/alert dialogs,
 * navigation, report flow) is identical, so it lives here once. Kept in the `ui` package so it can
 * see the package-private [SituationDialogFragment.Listener] and the navigation activities.
 */
fun createArrivalActionHandler(
    activity: AppCompatActivity,
    viewModel: ArrivalsViewModel,
    currentContent: () -> ArrivalsUiState.Content?,
    onShowRouteOnMap: (routeId: String) -> Unit
): ArrivalActionHandler = object : ArrivalActionHandler {

    override fun onRouteFavorite(actions: ArrivalActions) {
        val dialog = RouteFavoriteDialogFragment.Builder(actions.routeId, actions.headsign)
            .setRouteShortName(actions.routeShortName)
            .setRouteLongName(actions.routeLongName)
            .setRouteUrl(actions.scheduleUrl)
            .setStopId(actions.stopId)
            .setFavorite(!actions.isRouteFavorite)
            .build()
        dialog.setCallback { saved -> if (saved) viewModel.manualRefresh() }
        dialog.show(activity.supportFragmentManager, RouteFavoriteDialogFragment.TAG)
    }

    override fun onShowVehiclesOnMap(arrival: ArrivalInfo) {
        DBUtil.addRouteToDB(activity, arrival)
        onShowRouteOnMap(arrival.info.routeId)
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
        val info = content.arrivals.firstOrNull { it.info.tripId == actions.tripId }?.info ?: return
        InfrastructureIssueActivity.startWithService(
            activity,
            stopReportIntent(activity, content),
            activity.getString(R.string.ri_selected_service_trip),
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
        dialog.show(activity.supportFragmentManager, SituationDialogFragment.TAG)
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
            stopReportIntent(activity, content),
            activity.getString(R.string.ri_selected_service_stop)
        )
    }
}

/** The HomeActivity-style intent the report flow expects (stop focused on the map). */
private fun stopReportIntent(
    activity: AppCompatActivity,
    content: ArrivalsUiState.Content
): Intent = ArrivalsIntents.makeHomeIntent(
    activity,
    content.header.stopId,
    content.header.name,
    content.stopCode,
    content.stopLat,
    content.stopLon
)
