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
import androidx.compose.ui.platform.LocalContext
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.findActivity
import org.onebusaway.android.ui.mylists.editReminder
import org.onebusaway.android.ui.mylists.MyListViewModel
import org.onebusaway.android.ui.mylists.ReminderItem
import org.onebusaway.android.ui.mylists.ReminderListDestination
import org.onebusaway.android.ui.mylists.RouteListDestination
import org.onebusaway.android.ui.mylists.RouteListItem
import org.onebusaway.android.ui.mylists.StopListDestination
import org.onebusaway.android.ui.mylists.StopListItem
import org.onebusaway.android.ui.mylists.openRoute
import org.onebusaway.android.ui.mylists.openStop
import org.onebusaway.android.ui.mylists.reminderActions
import org.onebusaway.android.ui.mylists.routeActions
import org.onebusaway.android.ui.mylists.stopActions

/**
 * The three home list views (starred stops/routes, reminders) that [HomeScreen] draws over the map.
 * They're thin bindings to the shared [StopListDestination]/[RouteListDestination]/
 * [ReminderListDestination] (also used by the `My*` tab activities), wired with Home's strings and
 * `shortcutMode = false` (the home screen is never a launcher-shortcut picker). Their backing
 * [MyListViewModel]s are owned by [HomeActivity] (so its options menu can sort/clear them) and handed
 * in via [HomeListViewModels].
 */
class HomeListViewModels(
    val starredStops: MyListViewModel<StopListItem>,
    val starredRoutes: MyListViewModel<RouteListItem>,
    val reminders: MyListViewModel<ReminderItem>,
)

@Composable
internal fun StarredStopsDestination(
    viewModel: MyListViewModel<StopListItem>,
    onShowArrivals: (stopId: String, stopName: String?) -> Unit,
) {
    val host = LocalContext.current.findActivity()
    StopListDestination(
        viewModel,
        emptyText = R.string.my_no_starred_stops,
        onClick = { onShowArrivals(it.id, it.name) },
        actions = {
            host.stopActions(it, R.string.my_context_remove_star, shortcutMode = false) {
                viewModel.remove(it.id)
            }
        }
    )
}

@Composable
internal fun StarredRoutesDestination(viewModel: MyListViewModel<RouteListItem>) {
    val host = LocalContext.current.findActivity()
    RouteListDestination(
        viewModel,
        emptyText = R.string.my_no_starred_routes,
        onClick = { host.openRoute(it, shortcutMode = false) },
        actions = {
            host.routeActions(it, R.string.my_context_remove_star, shortcutMode = false) {
                viewModel.remove(it.id)
            }
        }
    )
}

@Composable
internal fun RemindersDestination(
    viewModel: MyListViewModel<ReminderItem>,
    onShowRoute: (routeId: String) -> Unit,
    onShowStop: (stopId: String) -> Unit,
) {
    val host = LocalContext.current.findActivity()
    ReminderListDestination(
        viewModel,
        emptyText = R.string.trip_list_notrips,
        onClick = { host.editReminder(it) },
        actions = { host.reminderActions(it, onShowRoute = onShowRoute, onShowStop = onShowStop) }
    )
}
