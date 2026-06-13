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

import android.app.Activity
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import org.onebusaway.android.R
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.ui.mylists.ReminderItem
import org.onebusaway.android.ui.mylists.RouteListItem
import org.onebusaway.android.ui.mylists.RowAction
import org.onebusaway.android.ui.mylists.StopListItem
import org.onebusaway.android.ui.search.RouteSearchResult
import org.onebusaway.android.ui.search.StopSearchResult
import org.onebusaway.android.util.ReminderUtils
import org.onebusaway.android.util.UIUtils

/**
 * Shared navigation and row-action wiring for the My-tab list destinations (recent/starred ×
 * stops/routes, plus reminders and the search results). They're hosted as composables by both the
 * `My*` tab activities (`MyTabsScreen`) and the Compose home screen, with identical tap/long-press
 * behavior except for the remove-action label and whether the host is a launcher-shortcut picker
 * (`shortcutMode`) — so it lives here as [AppCompatActivity] extensions rather than a base class.
 * (This file is in the `ui` package, not `ui.mylists`, so it can reach the package-private [NavHelp].)
 */

private fun AppCompatActivity.stopArrivalsBuilder(stop: StopListItem) =
    ArrivalsListActivity.Builder(this, stop.id)
        .setStopName(stop.name)
        .setStopDirection(stop.rawDirection)

/** Opens a stop's arrivals, or returns it as a launcher shortcut when [shortcutMode] is set. */
internal fun AppCompatActivity.openStop(stop: StopListItem, shortcutMode: Boolean) {
    val builder = stopArrivalsBuilder(stop)
    if (shortcutMode) {
        val shortcut = UIUtils.createStopShortcut(this, stop.name, builder)
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
            HomeActivity.start(this, stop.id, stop.lat, stop.lon)
        },
        RowAction(getString(R.string.my_context_create_shortcut)) {
            UIUtils.createStopShortcut(this, stop.name, stopArrivalsBuilder(stop))
        },
        RowAction(getString(removeLabel), onRemove)
    )
}

/** Opens a route on the map, or returns it as a launcher shortcut when [shortcutMode] is set. */
internal fun AppCompatActivity.openRoute(route: RouteListItem, shortcutMode: Boolean) {
    if (shortcutMode) {
        val shortcut = UIUtils.createRouteShortcut(this, route.id, route.shortName)
        setResult(Activity.RESULT_OK, shortcut.intent)
        finish()
    } else {
        HomeActivity.start(this, route.id)
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
            HomeActivity.start(this@routeActions, route.id)
        })
        route.url?.let { url ->
            add(RowAction(getString(R.string.my_context_show_schedule)) {
                UIUtils.goToUrl(this@routeActions, url)
            })
        }
        add(RowAction(getString(R.string.my_context_create_shortcut)) {
            UIUtils.createRouteShortcut(this@routeActions, route.id, route.shortName)
        })
        add(RowAction(getString(removeLabel), onRemove))
    }
}

/** Opens the reminder editor for [reminder] (My Reminders has no shortcut mode). */
internal fun AppCompatActivity.editReminder(reminder: ReminderItem) {
    TripInfoActivity.start(this, reminder.tripId, reminder.stopId)
}

/** A reminder row's long-press actions: edit / delete (cancels the alarm) / show stop / show route. */
internal fun AppCompatActivity.reminderActions(reminder: ReminderItem): List<RowAction> = listOf(
    RowAction(getString(R.string.trip_list_context_edit)) { editReminder(reminder) },
    RowAction(getString(R.string.trip_list_context_delete)) {
        confirmDeleteReminder(this) {
            ReminderUtils.requestDeleteAlarm(
                this, ObaContract.Trips.buildUri(reminder.tripId, reminder.stopId)
            )
        }
    },
    RowAction(getString(R.string.trip_list_context_showstop)) {
        ArrivalsListActivity.start(this, reminder.stopId)
    },
    RowAction(getString(R.string.trip_list_context_showroute)) {
        RouteInfoActivity.start(this, reminder.routeId)
    }
)

/** Opens a search-result stop's arrivals, or returns it as a launcher shortcut in [shortcutMode]. */
internal fun AppCompatActivity.openStopSearchResult(stop: StopSearchResult, shortcutMode: Boolean) {
    val builder = ArrivalsListActivity.Builder(this, stop.id)
        .setStopName(stop.serverName)
        .setStopDirection(stop.direction)
    if (shortcutMode) {
        val shortcut = UIUtils.createStopShortcut(this, stop.serverName, builder)
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
        val shortcut = UIUtils.createRouteShortcut(this, route.id, route.shortName)
        setResult(Activity.RESULT_OK, shortcut.intent)
        finish()
    } else {
        RouteInfoActivity.start(this, route.id)
    }
}
