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
package org.onebusaway.android.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.findActivity
import org.onebusaway.android.ui.editReminder
import org.onebusaway.android.ui.mylists.MyListContent
import org.onebusaway.android.ui.mylists.MyListViewModel
import org.onebusaway.android.ui.mylists.ReminderItem
import org.onebusaway.android.ui.mylists.ReminderRow
import org.onebusaway.android.ui.mylists.RouteListItem
import org.onebusaway.android.ui.mylists.RouteRow
import org.onebusaway.android.ui.mylists.StopListItem
import org.onebusaway.android.ui.mylists.StopRow
import org.onebusaway.android.ui.openRoute
import org.onebusaway.android.ui.openStop
import org.onebusaway.android.ui.reminderActions
import org.onebusaway.android.ui.routeActions
import org.onebusaway.android.ui.stopActions

/**
 * The three home list views that used to be content fragments ([MyStarredStopsFragment] etc.),
 * rendered directly over the map by [HomeScreen]. Each mirrors its fragment's `onCreateView` body —
 * the same [MyListContent] + row + shared [openStop]/[stopActions]/… wiring, only with the host
 * resolved via [findActivity] and `shortcutMode = false` (the home screen is never a shortcut picker).
 * The backing [MyListViewModel]s are owned by [HomeActivity] (so its options menu can sort/clear
 * them) and handed in via [HomeListViewModels].
 */
class HomeListViewModels(
    val starredStops: MyListViewModel<StopListItem>,
    val starredRoutes: MyListViewModel<RouteListItem>,
    val reminders: MyListViewModel<ReminderItem>,
)

@Composable
internal fun StarredStopsDestination(viewModel: MyListViewModel<StopListItem>) {
    val host = LocalContext.current.findActivity()
    val state by viewModel.state.collectAsStateWithLifecycle()
    MyListContent(state, emptyText = stringResource(R.string.my_no_starred_stops), itemKey = { it.id }) { stop ->
        StopRow(
            stop,
            onClick = { host.openStop(stop, shortcutMode = false) },
            actions = host.stopActions(stop, R.string.my_context_remove_star, shortcutMode = false) {
                viewModel.remove(stop.id)
            }
        )
    }
}

@Composable
internal fun StarredRoutesDestination(viewModel: MyListViewModel<RouteListItem>) {
    val host = LocalContext.current.findActivity()
    val state by viewModel.state.collectAsStateWithLifecycle()
    MyListContent(state, emptyText = stringResource(R.string.my_no_starred_routes), itemKey = { it.id }) { route ->
        RouteRow(
            route,
            onClick = { host.openRoute(route, shortcutMode = false) },
            actions = host.routeActions(route, R.string.my_context_remove_star, shortcutMode = false) {
                viewModel.remove(route.id)
            }
        )
    }
}

@Composable
internal fun RemindersDestination(viewModel: MyListViewModel<ReminderItem>) {
    val host = LocalContext.current.findActivity()
    val state by viewModel.state.collectAsStateWithLifecycle()
    MyListContent(
        state,
        emptyText = stringResource(R.string.trip_list_notrips),
        itemKey = { "${it.tripId}:${it.stopId}" }
    ) { reminder ->
        ReminderRow(
            reminder,
            onClick = { host.editReminder(reminder) },
            actions = host.reminderActions(reminder)
        )
    }
}
