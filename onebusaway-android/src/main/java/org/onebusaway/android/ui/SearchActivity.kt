/*
 * Copyright (C) 2010-2015 Paul Watts (paulcwatts@gmail.com), University of South Florida
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

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.analytics.FirebaseAnalytics
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.searchresults.DefaultSearchResultsRepository
import org.onebusaway.android.ui.searchresults.SearchResultItem
import org.onebusaway.android.ui.searchresults.SearchResultsRoute
import org.onebusaway.android.ui.searchresults.SearchResultsViewModel
import org.onebusaway.android.util.DBUtil

/**
 * Shows the combined route + stop results for a search query.
 *
 * Compose + MVVM screen, and the app's `default_searchable` target: the system delivers the
 * query via an ACTION_SEARCH intent (re-delivered to this singleTop instance via [onNewIntent]
 * for a fresh search). State lives in [SearchResultsViewModel].
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var firebaseAnalytics: FirebaseAnalytics

    private val viewModel: SearchResultsViewModel by viewModels {
        viewModelFactory {
            initializer {
                SearchResultsViewModel(DefaultSearchResultsRepository(applicationContext))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)
        setContent {
            ObaTheme {
                SearchResultsRoute(
                    viewModel = viewModel,
                    onBack = { NavHelp.goHome(this, false) },
                    onRouteListStops = { route ->
                        registerRoute(route)
                        RouteInfoActivity.start(this, route.id)
                    },
                    onRouteShowOnMap = { route ->
                        registerRoute(route)
                        HomeActivity.start(this, route.id)
                    },
                    onStopArrivals = { stop -> ArrivalsListActivity.start(this, stop.id) },
                    onStopShowOnMap = { stop ->
                        HomeActivity.start(this, stop.id, stop.latitude, stop.longitude)
                    }
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (Intent.ACTION_SEARCH == intent.action) {
            val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
            ObaAnalytics.reportSearchEvent(
                Application.get().plausibleInstance, firebaseAnalytics, query
            )
            viewModel.search(query)
        }
    }

    /** Registers the route in recents/search, matching the legacy on-tap behavior. */
    private fun registerRoute(route: SearchResultItem.Route) {
        DBUtil.addRouteToDB(this, route.id, route.shortName, route.longName, route.url)
    }
}
