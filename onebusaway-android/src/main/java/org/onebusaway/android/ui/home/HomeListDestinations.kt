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
import org.onebusaway.android.R
import org.onebusaway.android.ui.mylists.MyListViewModel
import org.onebusaway.android.ui.mylists.ReminderItem
import org.onebusaway.android.ui.mylists.ReminderListDestination
import org.onebusaway.android.ui.mylists.RouteListDestination
import org.onebusaway.android.ui.mylists.RouteListItem
import org.onebusaway.android.ui.mylists.StopListDestination
import org.onebusaway.android.ui.mylists.StopListItem

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
internal fun StarredStopsDestination(viewModel: MyListViewModel<StopListItem>) {
    StopListDestination(
        viewModel,
        emptyText = R.string.my_no_starred_stops,
        removeLabel = R.string.my_context_remove_star,
        shortcutMode = false
    )
}

@Composable
internal fun StarredRoutesDestination(viewModel: MyListViewModel<RouteListItem>) {
    RouteListDestination(
        viewModel,
        emptyText = R.string.my_no_starred_routes,
        removeLabel = R.string.my_context_remove_star,
        shortcutMode = false
    )
}

@Composable
internal fun RemindersDestination(viewModel: MyListViewModel<ReminderItem>) {
    ReminderListDestination(viewModel, emptyText = R.string.trip_list_notrips)
}
