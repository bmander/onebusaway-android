/*
 * Copyright (C) 2012-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com), Microsoft Corporation
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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.ui.arrivals.ArrivalsRoute
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.ui.arrivals.DefaultArrivalsRepository
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.util.ShowcaseViewUtils
import org.onebusaway.android.util.UIUtils
import java.util.HashMap

/**
 * Shows the real-time arrivals and departures for a stop.
 *
 * Compose + MVVM host: state and 60s polling live in [ArrivalsViewModel] / [ArrivalsRoute].
 * The stop id arrives via the intent data URI (preserved for the many launch sites and the
 * launcher-shortcut path). The map slide-panel in HomeActivity still uses the legacy
 * ArrivalsListFragment — this screen is the standalone path only.
 */
class ArrivalsListActivity : AppCompatActivity() {

    private val stopId: String by lazy { intent.data?.lastPathSegment.orEmpty() }

    private val viewModel: ArrivalsViewModel by viewModels {
        viewModelFactory {
            initializer {
                ArrivalsViewModel(stopId, DefaultArrivalsRepository(applicationContext))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hosted in an XML wrapper (not the setContent extension) so the root has the
        // R.id.fragment_arrivals_list that the shared SituationDialogFragment anchors to.
        setContentView(R.layout.activity_arrivals_compose)
        val initialTitle = intent.getStringExtra(ArrivalsListFragment.STOP_NAME).orEmpty()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            ObaTheme {
                ArrivalsRoute(
                    viewModel = viewModel,
                    initialTitle = initialTitle,
                    onBack = { NavHelp.goUp(this) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new stop arrived (singleTop); rebuild with a fresh ViewModel bound to it
        setIntent(intent)
        recreate()
    }

    override fun onPause() {
        ShowcaseViewUtils.hideShowcaseView()
        super.onPause()
    }

    /** Retained for the legacy ArrivalsListFragment, which no longer hosts here (returns null). */
    fun getArrivalsListFragment(): ArrivalsListFragment? = null

    class Builder {

        private val context: Context

        /** The built intent; Java callers see this as getIntent(). */
        val intent: Intent

        constructor(context: Context, stopId: String) {
            this.context = context
            intent = Intent(context, ArrivalsListActivity::class.java)
            intent.data = Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId)
        }

        /**
         * @param stop the [ObaStop] to be shown
         * @param routes route display names that serve this stop, keyed by route id
         */
        constructor(context: Context, stop: ObaStop, routes: HashMap<String, ObaRoute>) {
            this.context = context
            intent = Intent(context, ArrivalsListActivity::class.java)
            intent.data = Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stop.id)
            setStopName(stop.name)
            setStopDirection(stop.direction)
            setStopRoutes(UIUtils.serializeRouteDisplayNames(stop, routes))
        }

        fun setStopName(stopName: String?): Builder {
            intent.putExtra(ArrivalsListFragment.STOP_NAME, stopName)
            return this
        }

        fun setStopDirection(stopDir: String?): Builder {
            intent.putExtra(ArrivalsListFragment.STOP_DIRECTION, stopDir)
            return this
        }

        fun setStopRoutes(routes: String?): Builder {
            intent.putExtra(ArrivalsListFragment.STOP_ROUTES, routes)
            return this
        }

        fun setUpMode(mode: String?): Builder {
            intent.putExtra(NavHelp.UP_MODE, mode)
            return this
        }

        fun start() {
            context.startActivity(intent)
        }
    }

    companion object {

        @JvmStatic
        fun start(context: Context, stopId: String) {
            Builder(context, stopId).start()
        }

        @JvmStatic
        fun start(context: Context, stop: ObaStop, routes: HashMap<String, ObaRoute>) {
            Builder(context, stop, routes).start()
        }
    }
}
