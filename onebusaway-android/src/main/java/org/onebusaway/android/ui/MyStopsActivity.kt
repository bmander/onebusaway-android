/*
 * Copyright (C) 2010 Paul Watts (paulcwatts@gmail.com)
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
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.onebusaway.android.R
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.mylists.MyTab
import org.onebusaway.android.ui.mylists.MyTabs
import org.onebusaway.android.ui.mylists.MyTabsScreen
import org.onebusaway.android.ui.mylists.RecentStopsRepository
import org.onebusaway.android.ui.mylists.StarredStopsRepository
import org.onebusaway.android.ui.mylists.StopListDestination
import org.onebusaway.android.ui.mylists.StopSearchDestination
import org.onebusaway.android.ui.mylists.TabAction
import org.onebusaway.android.ui.mylists.chooseSortOrder
import org.onebusaway.android.ui.mylists.confirmClear
import org.onebusaway.android.ui.mylists.hostListVm
import org.onebusaway.android.ui.mylists.hostSearchVm
import org.onebusaway.android.ui.search.DefaultStopSearchRepository
import org.onebusaway.android.util.PreferenceUtils

/**
 * The stops "My" screen — Recent / Starred / Search stop tabs, hosted in Compose ([MyTabsScreen]).
 * Doubles as a launcher `CREATE_SHORTCUT` picker (in that mode a row tap returns a shortcut intent)
 * and a `tab://` deep-link target (the `MyStarred`/`MyRecentStops` shortcut shells link here).
 */
@AndroidEntryPoint
class MyStopsActivity : AppCompatActivity() {

    @Inject
    lateinit var prefsRepository: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shortcutMode = intent.action == Intent.ACTION_CREATE_SHORTCUT
        val initialTag = intent.data?.let { MyTabs.defaultTabFromUri(it) }

        val recent = hostListVm("stops.recent") { RecentStopsRepository(applicationContext) }
        val starred = hostListVm("stops.starred") { StarredStopsRepository(applicationContext) }
        val search = hostSearchVm("stops.search") {
            DefaultStopSearchRepository(applicationContext)::search
        }

        val lastTabKey = "MyStopsActivity.LastTab"
        val persistedTab = prefsRepository.getString(lastTabKey, null)

        setContent {
            ObaTheme {
                MyTabsScreen(
                    titleRes = R.string.my_recent_stops,
                    initialTag = initialTag,
                    persistedTag = persistedTab,
                    onPersistTag = { prefsRepository.setString(lastTabKey, it) },
                    onBack = { NavHelp.goHome(this, false) },
                    tabs = listOf(
                        MyTab(
                            tag = MyTabs.RECENT_STOPS,
                            titleRes = R.string.my_recent_title,
                            iconRes = R.drawable.ic_tab_recent_unselected,
                            clear = TabAction(R.string.my_option_clear_recent_stops) {
                                confirmClear(
                                    R.string.my_option_clear_recent_stops_title,
                                    R.string.my_option_clear_recent_stops_confirm
                                ) { recent.clearAll() }
                            }
                        ) {
                            StopListDestination(
                                recent,
                                emptyText = R.string.my_no_recent_stops,
                                onClick = {
                                    this@MyStopsActivity.openStop(it, shortcutMode)
                                },
                                actions = {
                                    this@MyStopsActivity.stopActions(
                                        it,
                                        R.string.my_context_remove_recent,
                                        shortcutMode
                                    ) { recent.remove(it.id) }
                                }
                            )
                        },
                        MyTab(
                            tag = MyTabs.STARRED_STOPS,
                            titleRes = R.string.my_starred_title,
                            iconRes = R.drawable.ic_tab_starred_unselected,
                            onSort = {
                                chooseSortOrder(
                                    PreferenceUtils.getStopSortOrderFromPreferences(),
                                    R.array.sort_stops
                                ) { starred.setSort(it) }
                            },
                            clear = TabAction(R.string.my_option_clear_starred_stops) {
                                confirmClear(
                                    R.string.my_option_clear_starred_stops_title,
                                    R.string.my_option_clear_starred_stops_confirm
                                ) { starred.clearAll() }
                            }
                        ) {
                            StopListDestination(
                                starred,
                                emptyText = R.string.my_no_starred_stops,
                                onClick = {
                                    this@MyStopsActivity.openStop(it, shortcutMode)
                                },
                                actions = {
                                    this@MyStopsActivity.stopActions(
                                        it,
                                        R.string.my_context_remove_star,
                                        shortcutMode
                                    ) { starred.remove(it.id) }
                                }
                            )
                        },
                        MyTab(
                            tag = MyTabs.SEARCH,
                            titleRes = R.string.my_search_title,
                            iconRes = R.drawable.ic_tab_search_unselected
                        ) {
                            StopSearchDestination(search, shortcutMode = shortcutMode)
                        }
                    )
                )
            }
        }
    }
}
