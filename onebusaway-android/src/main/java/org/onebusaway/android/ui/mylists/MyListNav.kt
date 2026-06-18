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
package org.onebusaway.android.ui.mylists

import org.onebusaway.android.ui.tripinfo.confirmDeleteReminder
import org.onebusaway.android.ui.tripinfo.TripInfoLauncher
import org.onebusaway.android.ui.routeinfo.RouteInfoLauncher
import org.onebusaway.android.ui.nav.NavHelp
import org.onebusaway.android.ui.arrivals.ArrivalsListLauncher
import org.onebusaway.android.ui.HomeActivity
import android.app.Activity
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import org.onebusaway.android.R
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.ui.common.Shortcuts
import org.onebusaway.android.ui.search.RouteSearchResult
import org.onebusaway.android.ui.search.StopSearchResult
import org.onebusaway.android.util.ExternalIntents
import org.onebusaway.android.util.ReminderUtils

/**
 * Shared navigation and row-action wiring for the My-tab list destinations (recent/starred ×
 * stops/routes, plus reminders and the search results). They're hosted as composables by both the
 * `My*` tab activities (`MyTabsScreen`) and the Compose home screen, with identical tap/long-press
 * behavior except for the remove-action label and whether the host is a launcher-shortcut picker
 * (`shortcutMode`) — so it lives here as [AppCompatActivity] extensions rather than a base class.
 * (This file is in the `ui` package, not `ui.mylists`, so it can reach the package-private [NavHelp].)
 */

private fun AppCompatActivity.stopArrivalsBuilder(stop: StopListItem) =
    ArrivalsListLauncher.Builder(this, stop.id)
        .setStopName(stop.name)
        .setStopDirection(stop.rawDirection)

/** Opens a stop's arrivals, or returns it as a launcher shortcut when [shortcutMode] is set. */
internal fun AppCompatActivity.openStop(stop: StopListItem, shortcutMode: Boolean) {
    val builder = stopArrivalsBuilder(stop)
    if (shortcutMode) {
        val shortcut = Shortcuts.createStopShortcut(this, stop.name, builder)
        setResult(Activity.RESULT_OK, shortcut.intent)
        finish()
    } else {
        builder.setUpMode(NavHelp.UP_MODE_BACK)
        builder.start()
    }
}

/** A stop row's long-press actions (empty in [shortcutMode]); [removeLabel] is the only per-list delta. */
internal fun AppCompatActivity.stopActions(
    stop: StopListItem,
    @StringRes removeLabel: Int,
    shortcutMode: Boolean,
    onRemove: () -> Unit
): List<RowAction> {
    if (shortcutMode) return emptyList()
    return listOf(
        RowAction(getString(R.string.my_context_showonmap)) {
            (this as HomeActivity).focusStopOnMap(stop.id, stop.lat, stop.lon)
        },
        RowAction(getString(R.string.my_context_create_shortcut)) {
            Shortcuts.createStopShortcut(this, stop.name, stopArrivalsBuilder(stop))
        },
        RowAction(getString(removeLabel), onRemove)
    )
}

/** Opens a route on the map, or returns it as a launcher shortcut when [shortcutMode] is set. */
internal fun AppCompatActivity.openRoute(route: RouteListItem, shortcutMode: Boolean) {
    if (shortcutMode) {
        val shortcut = Shortcuts.createRouteShortcut(this, route.id, route.shortName)
        setResult(Activity.RESULT_OK, shortcut.intent)
        finish()
    } else {
        (this as HomeActivity).showRouteOnMap(route.id)
    }
}

/** A route row's long-press actions (empty in [shortcutMode]); [removeLabel] is the only per-list delta. */
internal fun AppCompatActivity.routeActions(
    route: RouteListItem,
    @StringRes removeLabel: Int,
    shortcutMode: Boolean,
    onRemove: () -> Unit
): List<RowAction> {
    if (shortcutMode) return emptyList()
    return buildList {
        add(RowAction(getString(R.string.my_context_showonmap)) {
            (this@routeActions as HomeActivity).showRouteOnMap(route.id)
        })
        route.url?.let { url ->
            add(RowAction(getString(R.string.my_context_show_schedule)) {
                ExternalIntents.goToUrl(this@routeActions, url)
            })
        }
        add(RowAction(getString(R.string.my_context_create_shortcut)) {
            Shortcuts.createRouteShortcut(this@routeActions, route.id, route.shortName)
        })
        add(RowAction(getString(removeLabel), onRemove))
    }
}

/** Opens the reminder editor for [reminder] (My Reminders has no shortcut mode). */
internal fun AppCompatActivity.editReminder(reminder: ReminderItem) {
    TripInfoLauncher.start(this, reminder.tripId, reminder.stopId)
}

/**
 * A reminder row's long-press actions: edit / delete (cancels the alarm) / show stop / show route.
 *
 * [onShowRoute]/[onShowStop] default to launching [RouteInfoLauncher]/[ArrivalsListLauncher] (the
 * standalone My-Reminders host has no NavHost), but the home overlay overrides them to navigate to
 * the in-app RouteInfo / Arrivals destinations.
 */
internal fun AppCompatActivity.reminderActions(
    reminder: ReminderItem,
    onShowRoute: (routeId: String) -> Unit = { RouteInfoLauncher.start(this, it) },
    onShowStop: (stopId: String) -> Unit = { ArrivalsListLauncher.start(this, it) },
): List<RowAction> = listOf(
    RowAction(getString(R.string.trip_list_context_edit)) { editReminder(reminder) },
    RowAction(getString(R.string.trip_list_context_delete)) {
        confirmDeleteReminder(this) {
            ReminderUtils.requestDeleteAlarm(
                this, ObaContract.Trips.buildUri(reminder.tripId, reminder.stopId)
            )
        }
    },
    RowAction(getString(R.string.trip_list_context_showstop)) {
        onShowStop(reminder.stopId)
    },
    RowAction(getString(R.string.trip_list_context_showroute)) {
        onShowRoute(reminder.routeId)
    }
)

/** Opens a search-result stop's arrivals, or returns it as a launcher shortcut in [shortcutMode]. */
internal fun AppCompatActivity.openStopSearchResult(stop: StopSearchResult, shortcutMode: Boolean) {
    val builder = ArrivalsListLauncher.Builder(this, stop.id)
        .setStopName(stop.serverName)
        .setStopDirection(stop.direction)
    if (shortcutMode) {
        val shortcut = Shortcuts.createStopShortcut(this, stop.serverName, builder)
        setResult(Activity.RESULT_OK, shortcut.intent)
        finish()
    } else {
        builder.setUpMode(NavHelp.UP_MODE_BACK)
        builder.start()
    }
}

/** Opens a search-result route, or returns it as a launcher shortcut in [shortcutMode]. */
internal fun AppCompatActivity.openRouteSearchResult(route: RouteSearchResult, shortcutMode: Boolean) {
    if (shortcutMode) {
        val shortcut = Shortcuts.createRouteShortcut(this, route.id, route.shortName)
        setResult(Activity.RESULT_OK, shortcut.intent)
        finish()
    } else {
        RouteInfoLauncher.start(this, route.id)
    }
}
