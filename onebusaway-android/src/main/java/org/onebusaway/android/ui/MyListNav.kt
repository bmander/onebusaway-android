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
import androidx.fragment.app.Fragment
import org.onebusaway.android.R
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.ui.mylists.ReminderItem
import org.onebusaway.android.ui.mylists.RouteListItem
import org.onebusaway.android.ui.mylists.RowAction
import org.onebusaway.android.ui.mylists.StopListItem
import org.onebusaway.android.util.ReminderUtils
import org.onebusaway.android.util.UIUtils

/**
 * Shared navigation and row-action wiring for the My-tab list destinations (recent/starred ×
 * stops/routes, plus reminders). They're hosted by both the legacy `My*` tab activities (as
 * fragments) and the Compose home screen (as composable destinations), with identical tap/long-press
 * behavior except for the remove-action label and whether the host is a launcher-shortcut picker, so
 * it lives here as [AppCompatActivity] extensions rather than a base class. (This file is in the `ui`
 * package, not `ui.mylists`, so it can reach the package-private [MyTabActivityBase] and [NavHelp].)
 */

/** True when the host activity launched this fragment as a launcher-shortcut picker. */
internal fun Fragment.isInShortcutMode(): Boolean =
    (activity as? MyTabActivityBase)?.isShortcutMode == true

/** The host as an [AppCompatActivity] — every My-tab list fragment runs in one. */
internal fun Fragment.requireListHost(): AppCompatActivity = requireActivity() as AppCompatActivity

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
