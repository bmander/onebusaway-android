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
import org.onebusaway.android.ui.mylists.RecentRoutesRepository
import org.onebusaway.android.ui.mylists.RouteListDestination
import org.onebusaway.android.ui.mylists.RouteSearchDestination
import org.onebusaway.android.ui.mylists.TabAction
import org.onebusaway.android.ui.mylists.confirmClear
import org.onebusaway.android.ui.mylists.hostListVm
import org.onebusaway.android.ui.mylists.hostSearchVm
import org.onebusaway.android.ui.search.DefaultRouteSearchRepository

/**
 * The routes "My" screen — Recent / Search route tabs, hosted in Compose ([MyTabsScreen]). Doubles
 * as a launcher `CREATE_SHORTCUT` picker (a row tap returns a shortcut intent) and a `tab://`
 * deep-link target (the `MyRecentRoutes` shortcut shell links here).
 */
@AndroidEntryPoint
class MyRoutesActivity : AppCompatActivity() {

    @Inject
    lateinit var prefsRepository: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shortcutMode = intent.action == Intent.ACTION_CREATE_SHORTCUT
        val initialTag = intent.data?.let { MyTabs.defaultTabFromUri(it) }

        val recent = hostListVm("routes.recent") { RecentRoutesRepository(applicationContext) }
        val search = hostSearchVm("routes.search") {
            DefaultRouteSearchRepository(applicationContext)::search
        }

        val lastTabKey = "MyRoutesActivity.LastTab"
        val persistedTab = prefsRepository.getString(lastTabKey, null)

        setContent {
            ObaTheme {
                MyTabsScreen(
                    titleRes = R.string.my_recent_routes,
                    initialTag = initialTag,
                    persistedTag = persistedTab,
                    onPersistTag = { prefsRepository.setString(lastTabKey, it) },
                    onBack = { NavHelp.goHome(this, false) },
                    tabs = listOf(
                        MyTab(
                            tag = MyTabs.RECENT_ROUTES,
                            titleRes = R.string.my_recent_title,
                            iconRes = R.drawable.ic_tab_recent_unselected,
                            clear = TabAction(R.string.my_option_clear_recent_routes) {
                                confirmClear(
                                    R.string.my_option_clear_recent_routes_title,
                                    R.string.my_option_clear_recent_routes_confirm
                                ) { recent.clearAll() }
                            }
                        ) {
                            RouteListDestination(
                                recent,
                                emptyText = R.string.my_no_recent_routes,
                                onClick = {
                                    this@MyRoutesActivity.openRoute(it, shortcutMode)
                                },
                                actions = {
                                    this@MyRoutesActivity.routeActions(
                                        it,
                                        R.string.my_context_remove_recent,
                                        shortcutMode
                                    ) { recent.remove(it.id) }
                                }
                            )
                        },
                        MyTab(
                            tag = MyTabs.SEARCH,
                            titleRes = R.string.my_search_title,
                            iconRes = R.drawable.ic_tab_search_unselected
                        ) {
                            RouteSearchDestination(search, shortcutMode = shortcutMode)
                        }
                    )
                )
            }
        }
    }
}
