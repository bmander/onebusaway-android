/*
 * Copyright (C) 2024-2026 Open Transit Software Foundation
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
package org.onebusaway.android.ui.dataview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.util.UIUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard activity showing trip data collected in [TripDataManager].
 */
class DataViewActivity : AppCompatActivity() {

    companion object {
        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, DataViewActivity::class.java))
        }
    }

    private val timeFmt = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
    private var collectJob: Job? = null

    private lateinit var collectedDataEmpty: TextView
    private lateinit var collectedDataContainer: LinearLayout

    private var lastTrackedTripIds: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_view)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        UIUtils.setupActionBar(this)
        supportActionBar?.setTitle(R.string.data_views_title)

        collectedDataEmpty = findViewById(R.id.collected_data_empty)
        collectedDataContainer = findViewById(R.id.collected_data_container)
    }

    override fun onResume() {
        super.onResume()
        refreshCollectedData()
        collectJob = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            TripDataManager.changes.collect { refreshCollectedData() }
        }
    }

    override fun onPause() {
        super.onPause()
        collectJob?.cancel()
        collectJob = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun refreshCollectedData() {
        val tripIds = TripDataManager.getTrackedTripIds()
        val hasData = tripIds.isNotEmpty()
        collectedDataEmpty.visibility = if (hasData) View.GONE else View.VISIBLE
        collectedDataContainer.visibility = if (hasData) View.VISIBLE else View.GONE

        if (!hasData) return

        // Only rebuild if tracked trips changed
        if (tripIds == lastTrackedTripIds) {
            // Update details in-place
            updateCollectedDataDetails(tripIds)
            return
        }
        lastTrackedTripIds = tripIds

        collectedDataContainer.removeAllViews()
        for (tripId in tripIds) {
            val view = LayoutInflater.from(this)
                    .inflate(R.layout.item_data_view_trip, collectedDataContainer, false)
            view.tag = tripId
            view.findViewById<TextView>(R.id.trip_id).text = tripId
            view.findViewById<TextView>(R.id.trip_details).text =
                    buildDetailText(tripId, TripDataManager.getLastState(tripId))
            view.setOnClickListener {
                val currentState = TripDataManager.getLastState(tripId)
                VehicleLocationDataActivity.start(this, tripId, currentState?.vehicleId)
            }
            collectedDataContainer.addView(view)
        }
    }

    private fun updateCollectedDataDetails(tripIds: Set<String>) {
        for (i in 0 until collectedDataContainer.childCount) {
            val view = collectedDataContainer.getChildAt(i)
            val tripId = view.tag as? String ?: continue
            if (tripId !in tripIds) continue
            view.findViewById<TextView>(R.id.trip_details).text =
                    buildDetailText(tripId, TripDataManager.getLastState(tripId))
        }
    }

    private fun buildDetailText(tripId: String, lastState: ObaTripStatus?) = buildString {
        lastState?.vehicleId?.let { append("Vehicle: $it  ") }
        append("Samples: ${TripDataManager.getHistorySize(tripId)}")
        val updateTime = lastState?.lastLocationUpdateTime ?: 0
        if (updateTime > 0) {
            append("  Last: ${timeFmt.format(Date(updateTime))}")
        }
    }
}
