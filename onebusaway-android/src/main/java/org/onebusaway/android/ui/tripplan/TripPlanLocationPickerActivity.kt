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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import org.onebusaway.android.R
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.map.ObaMapFragment
import org.onebusaway.android.util.UIUtils

/**
 * Lets the user pick a point for a trip-plan endpoint by panning the map under a fixed center
 * crosshair and confirming. Hosts the flavor-agnostic [ObaMapFragment] (a plain Activity, not
 * fragment-in-Compose) and returns the map center as [MapParams.CENTER_LAT]/[MapParams.CENTER_LON].
 * Launched with those same extras to set the initial center.
 */
class TripPlanLocationPickerActivity : AppCompatActivity() {

    private var mapFragment: ObaMapFragment? = null
    private var initialCenter: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_plan_location_picker)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        UIUtils.setupActionBar(this)
        supportActionBar?.setTitle(R.string.trip_plan_pick_on_map)

        initialCenter = UIUtils.getMapCenter(intent.extras)
        setupMapFragment(initialCenter)

        findViewById<View>(R.id.use_this_location_button).setOnClickListener { confirmSelection() }
    }

    /** Mirrors InfrastructureIssueActivity.setupMapFragment: add the map, center on the initial point. */
    private fun setupMapFragment(center: Location?) {
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(ObaMapFragment.TAG) as? ObaMapFragment
        if (existing != null) {
            mapFragment = existing
            return
        }
        val fragment = ObaMapFragment.newInstance()
        fragment.asFragment().arguments = Bundle().apply {
            if (center != null) {
                putDouble(MapParams.CENTER_LAT, center.latitude)
                putDouble(MapParams.CENTER_LON, center.longitude)
                putFloat(MapParams.ZOOM, INITIAL_ZOOM)
            }
        }
        fm.beginTransaction()
            .add(R.id.map_picker_container, fragment.asFragment(), ObaMapFragment.TAG)
            .commit()
        mapFragment = fragment
        if (center != null) {
            fragment.setMapCenter(center, true, false)
        }
    }

    private fun confirmSelection() {
        val center = mapFragment?.mapView?.mapCenterAsLocation ?: initialCenter
        if (center == null) {
            finish()
            return
        }
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(MapParams.CENTER_LAT, center.latitude)
                .putExtra(MapParams.CENTER_LON, center.longitude)
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
