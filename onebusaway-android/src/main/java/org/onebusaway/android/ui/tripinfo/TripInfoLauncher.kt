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
package org.onebusaway.android.ui.tripinfo

import org.onebusaway.android.ui.HomeActivity
import android.content.Context
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.onebusaway.android.R
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.ui.nav.NavRoutes

/**
 * Launches the trip-reminder editor (create or edit a trip arrival reminder).
 *
 * Campaign C: the editor is a NavHost destination hosted by [HomeActivity]; this is no longer an
 * Activity but a launcher facade. The edit path carries only the trip/stop ids (in the
 * [ObaContract.Trips] data URI); the arrivals "set reminder" path also passes the full trip context
 * as extras so a brand-new reminder needs no DB round-trip. Both build an explicit [HomeActivity]
 * intent that HomeActivity's translator turns into the [NavRoutes.TRIP_INFO] route. (Non-exported,
 * launched only in-app, so no activity-alias is needed.)
 */
object TripInfoLauncher {

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
            .putExtra(NavRoutes.ARG_ROUTE_ID, routeId)
            .putExtra(NavRoutes.ARG_ROUTE_NAME, routeName)
            .putExtra(NavRoutes.ARG_STOP_NAME, stopName)
            .putExtra(NavRoutes.ARG_DEPART_TIME, departureTime)
            .putExtra(NavRoutes.ARG_HEADSIGN, headsign)
            .putExtra(NavRoutes.ARG_STOP_SEQUENCE, stopSequence)
            .putExtra(NavRoutes.ARG_SERVICE_DATE, serviceDate)
            .putExtra(NavRoutes.ARG_VEHICLE_ID, vehicleId)
        context.startActivity(intent)
    }

    private fun intentFor(context: Context, tripId: String, stopId: String): Intent =
        Intent(context, HomeActivity::class.java)
            .setData(ObaContract.Trips.buildUri(tripId, stopId))
}

/**
 * The "this reminder will be deleted" confirmation dialog, shared by the reminder editor's delete
 * action and the My Reminders list's long-press delete.
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
