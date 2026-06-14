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
package org.onebusaway.android.ui.tripplan

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dagger.hilt.android.AndroidEntryPoint
import org.onebusaway.android.R
import org.onebusaway.android.map.MapMode
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.map.compose.NoOpObaMapCallbacks
import org.onebusaway.android.map.compose.ObaMap
import org.onebusaway.android.util.UIUtils

/**
 * Lets the user pick a point for a trip-plan endpoint by panning the map under a fixed center
 * crosshair and confirming. Hosts the declarative [ObaMap] driven by a [MapViewModel] (stop mode, so
 * nearby stops show as you pan) and returns the map center as
 * [MapParams.CENTER_LAT]/[MapParams.CENTER_LON]. Launched with those same extras to set the initial
 * center.
 */
@AndroidEntryPoint
class TripPlanLocationPickerActivity : AppCompatActivity() {

    private var initialCenter: Location? = null

    private val mapViewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_plan_location_picker)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        UIUtils.setupActionBar(this)
        supportActionBar?.setTitle(R.string.trip_plan_pick_on_map)

        initialCenter = UIUtils.getMapCenter(intent.extras)
        setupMap(initialCenter)

        findViewById<View>(R.id.use_this_location_button).setOnClickListener { confirmSelection() }
    }

    private fun setupMap(center: Location?) {
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ObaMap(
                    renderState = mapViewModel.renderState,
                    callbacks = NoOpObaMapCallbacks,
                    modifier = Modifier.fillMaxSize(),
                    mapViewModel = mapViewModel,
                    initialLatitude = center?.latitude ?: 0.0,
                    initialLongitude = center?.longitude ?: 0.0,
                    initialZoom = INITIAL_ZOOM,
                )
            }
        }
        findViewById<FrameLayout>(R.id.map_picker_container).addView(composeView)
        // Stop mode so nearby stops load as the user pans (the old view-owning host's default mode).
        if (mapViewModel.currentMapMode == null) {
            mapViewModel.setMode(MapMode.Stop)
        }
    }

    private fun confirmSelection() {
        // The current viewport center, from the last camera idle the adapter published to the VM.
        val center = mapViewModel.camera.value?.center
        val lat = center?.latitude ?: initialCenter?.latitude
        val lon = center?.longitude ?: initialCenter?.longitude
        if (lat == null || lon == null) {
            finish()
            return
        }
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(MapParams.CENTER_LAT, lat)
                .putExtra(MapParams.CENTER_LON, lon)
        )
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val INITIAL_ZOOM = 16f

        /** Builds a launch intent centered on [center] (or the region default when null). */
        @JvmStatic
        fun newIntent(context: Context, center: Location?): Intent =
            Intent(context, TripPlanLocationPickerActivity::class.java).apply {
                if (center != null) {
                    putExtra(MapParams.CENTER_LAT, center.latitude)
                    putExtra(MapParams.CENTER_LON, center.longitude)
                }
            }
    }
}
