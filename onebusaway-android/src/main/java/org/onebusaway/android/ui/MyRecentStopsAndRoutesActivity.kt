/*
 * Copyright (C) 2010-2015 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com)
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
import androidx.core.content.pm.ShortcutManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.onebusaway.android.R
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.mylists.MyTab
import org.onebusaway.android.ui.mylists.MyTabs
import org.onebusaway.android.ui.mylists.MyTabsScreen
import org.onebusaway.android.ui.mylists.RecentRoutesRepository
import org.onebusaway.android.ui.mylists.RecentStopsRepository
import org.onebusaway.android.ui.mylists.RouteListDestination
import org.onebusaway.android.ui.mylists.StopListDestination
import org.onebusaway.android.ui.mylists.TabAction
import org.onebusaway.android.ui.mylists.confirmClear
import org.onebusaway.android.ui.mylists.hostListVm
import org.onebusaway.android.util.UIUtils

/**
 * The "Recent" screen launched from Home's toolbar overflow — Recent Stops / Recent Routes tabs,
 * hosted in Compose ([MyTabsScreen]). Unlike [MyStopsActivity]/[MyRoutesActivity], a launcher
 * `CREATE_SHORTCUT` request pins a shortcut to *this activity itself* (not a stop/route picker) and
 * finishes immediately.
 */
@AndroidEntryPoint
class MyRecentStopsAndRoutesActivity : AppCompatActivity() {

    @Inject
    lateinit var prefsRepository: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            val shortcut = UIUtils.makeShortcutInfo(
                this,
                getString(R.string.my_recent_title),
                Intent(this, MyRecentStopsAndRoutesActivity::class.java),
                R.drawable.ic_history
            )
            ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
            setResult(RESULT_OK, shortcut.intent)
            finish()
            return
        }

        val recentStops = hostListVm("recents.stops") { RecentStopsRepository(applicationContext) }
        val recentRoutes = hostListVm("recents.routes") { RecentRoutesRepository(applicationContext) }

        val lastTabKey = "RecentRoutesStopsActivity.LastTab"
        val persistedTab = prefsRepository.getString(lastTabKey, null)

        setContent {
            ObaTheme {
                MyTabsScreen(
                    titleRes = R.string.my_recent_title,
                    initialTag = intent.data?.let { MyTabs.defaultTabFromUri(it) },
                    persistedTag = persistedTab,
                    onPersistTag = { prefsRepository.setString(lastTabKey, it) },
                    onBack = { NavHelp.goHome(this, false) },
                    tabs = listOf(
                        MyTab(
                            tag = MyTabs.RECENT_STOPS,
                            titleRes = R.string.my_recent_stops,
                            iconRes = R.drawable.ic_menu_stop,
                            clear = TabAction(R.string.my_option_clear_recent_stops) {
                                confirmClear(
                                    R.string.my_option_clear_recent_stops_title,
                                    R.string.my_option_clear_recent_stops_confirm
                                ) { recentStops.clearAll() }
                            }
                        ) {
                            StopListDestination(
                                recentStops,
                                emptyText = R.string.my_no_recent_stops,
                                onClick = {
                                    this@MyRecentStopsAndRoutesActivity.openStop(
                                        it,
                                        shortcutMode = false
                                    )
                                },
                                actions = {
                                    this@MyRecentStopsAndRoutesActivity.stopActions(
                                        it,
                                        R.string.my_context_remove_recent,
                                        shortcutMode = false
                                    ) { recentStops.remove(it.id) }
                                }
                            )
                        },
                        MyTab(
                            tag = MyTabs.RECENT_ROUTES,
                            titleRes = R.string.my_recent_routes,
                            iconRes = R.drawable.ic_bus,
                            clear = TabAction(R.string.my_option_clear_recent_routes) {
                                confirmClear(
                                    R.string.my_option_clear_recent_routes_title,
                                    R.string.my_option_clear_recent_routes_confirm
                                ) { recentRoutes.clearAll() }
                            }
                        ) {
                            RouteListDestination(
                                recentRoutes,
                                emptyText = R.string.my_no_recent_routes,
                                onClick = {
                                    this@MyRecentStopsAndRoutesActivity.openRoute(
                                        it,
                                        shortcutMode = false
                                    )
                                },
                                actions = {
                                    this@MyRecentStopsAndRoutesActivity.routeActions(
                                        it,
                                        R.string.my_context_remove_recent,
                                        shortcutMode = false
                                    ) { recentRoutes.remove(it.id) }
                                }
                            )
                        }
                    )
                )
            }
        }
    }
}
