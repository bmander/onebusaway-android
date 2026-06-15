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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.routeinfo.RouteInfoRoute
import org.onebusaway.android.ui.routeinfo.RouteInfoViewModel

/**
 * Shows a route's metadata and its stops grouped by direction.
 *
 * Compose + MVVM screen: the Activity is a thin host for [RouteInfoRoute]; all state lives in
 * [RouteInfoViewModel]. The route id arrives via the intent data URI (see [makeIntent]), which
 * the launcher-shortcut path and the legacy package redirect both rely on.
 */
@AndroidEntryPoint
class RouteInfoActivity : AppCompatActivity() {

    private val routeId: String by lazy { intent.data?.lastPathSegment.orEmpty() }

    private val viewModel: RouteInfoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Normalize the route id from the data URI into an extra so the view model can read it from
        // SavedStateHandle (which is seeded from intent extras, not the data URI). Must run before the
        // view model is first accessed in setContent.
        intent.putExtra(EXTRA_ROUTE_ID, routeId)
        setContent {
            ObaTheme {
                RouteInfoRoute(
                    viewModel = viewModel,
                    onBack = { NavHelp.goHome(this, false) },
                    onShowRouteOnMap = { HomeActivity.start(this, routeId) },
                    onStopClick = { stop ->
                        ArrivalsListActivity.Builder(this, stop.id)
                            .setStopName(stop.name)
                            .setStopDirection(stop.direction)
                            .setUpMode(NavHelp.UP_MODE_BACK)
                            .start()
                    },
                    onStopShowOnMap = { stop ->
                        HomeActivity.start(this, stop.id, stop.latitude, stop.longitude)
                    }
                )
            }
        }
    }

    companion object {

        /** SavedStateHandle key the host normalizes the data-URI route id into, read by the view model. */
        const val EXTRA_ROUTE_ID = ".RouteId"

        @JvmStatic
        fun start(context: Context, routeId: String) {
            context.startActivity(makeIntent(context, routeId))
        }

        @JvmStatic
        fun makeIntent(context: Context, routeId: String): Intent =
            Intent(context, RouteInfoActivity::class.java).apply {
                data = Uri.withAppendedPath(ObaContract.Routes.CONTENT_URI, routeId)
            }
    }
}
