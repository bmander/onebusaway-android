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
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.tabs.TabLayout
import org.onebusaway.android.R
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.math.prob.ProbDistribution
import org.onebusaway.android.extrapolation.VehicleTrajectoryTracker
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.bestDistanceAlongTrip
import org.onebusaway.android.io.request.ObaTripDetailsRequest
import org.onebusaway.android.util.UIUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "VehicleLocationDataAct"
private const val PAD_H = 12
private const val PAD_V = 6
private const val TEXT_SIZE = 12f
private const val UI_REFRESH_MS = 1_000L
private const val POLL_INTERVAL_MS = 30_000L
private const val MPS_TO_MPH = 2.23694

/**
 * Debug activity that displays all collected location data for a vehicle's trip
 * in a scrollable table and a distance-time graph.
 */
class VehicleLocationDataActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TRIP_ID = ".TripId"
        private const val EXTRA_VEHICLE_ID = ".VehicleId"
        private const val EXTRA_STOP_ID = ".StopId"

        @JvmStatic
        fun start(context: Context, tripId: String?, vehicleId: String?) =
                start(context, tripId, vehicleId, null)

        @JvmStatic
        fun start(context: Context, tripId: String?, vehicleId: String?, stopId: String?) {
            context.startActivity(Intent(context, VehicleLocationDataActivity::class.java).apply {
                putExtra(EXTRA_TRIP_ID, tripId)
                putExtra(EXTRA_VEHICLE_ID, vehicleId)
                putExtra(EXTRA_STOP_ID, stopId)
            })
        }
    }

    private lateinit var tripId: String
    private var vehicleId: String? = null
    private var stopId: String? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastRowCount = -1

    private lateinit var tableContainer: View
    private lateinit var graphView: TrajectoryGraphView

    private val refreshRunnable = object : Runnable {
        override fun run() { refreshData(); refreshHandler.postDelayed(this, UI_REFRESH_MS) }
    }
    private val pollRunnable = object : Runnable {
        override fun run() { pollTrip(); pollHandler.postDelayed(this, POLL_INTERVAL_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_location_data)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        UIUtils.setupActionBar(this)
        supportActionBar?.title = getString(R.string.debug_trip_data_title)

        tripId = intent.getStringExtra(EXTRA_TRIP_ID) ?: run { finish(); return }
        vehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID)
        stopId = intent.getStringExtra(EXTRA_STOP_ID)

        tableContainer = findViewById(R.id.location_data_table_container)
        graphView = findViewById(R.id.location_data_graph)
        graphView.setHighlightedStopId(stopId)

        tableContainer.visibility = View.GONE
        graphView.visibility = View.VISIBLE

        setupTabs()
        refreshData()
    }

    override fun onResume() {
        super.onResume()
        pollTrip()
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        refreshData()
        refreshHandler.postDelayed(refreshRunnable, UI_REFRESH_MS)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        pollHandler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // --- Tab setup ---

    private fun setupTabs() {
        val tabs: TabLayout = findViewById(R.id.location_data_tabs)
        tabs.addTab(tabs.newTab().setText(getString(R.string.debug_tab_graph)))
        tabs.addTab(tabs.newTab().setText(getString(R.string.debug_tab_table)))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tableContainer.visibility = View.GONE
                graphView.visibility = View.GONE
                when (tab.position) {
                    0 -> graphView.visibility = View.VISIBLE
                    1 -> tableContainer.visibility = View.VISIBLE
                }
                refreshData()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // --- Data refresh ---

    private fun refreshData() {
        val history = TripDataManager.getHistory(tripId)
        val activeTripId = TripDataManager.getLastActiveTripId(tripId)
        val tripEnded = activeTripId != null && tripId != activeTripId

        updateHeader(history.size, tripEnded)

        if (history.size != lastRowCount) {
            lastRowCount = history.size
            val table: TableLayout = findViewById(R.id.location_data_table)
            table.removeAllViews()
            buildTable(table, history)
        }

        if (graphView.visibility == View.VISIBLE) {
            refreshGraph(history, tripEnded)
        }
    }

    private fun updateHeader(sampleCount: Int, tripEnded: Boolean) {
        val header: TextView = findViewById(R.id.location_data_header)
        header.text = buildString {
            append("Trip: $tripId")
            vehicleId?.let { append("\nVehicle: $it") }
            append("\nSamples: $sampleCount")
            if (tripEnded) append("  |  Vehicle no longer serving trip")
        }
    }

    private fun refreshGraph(history: List<ObaTripStatus>, tripEnded: Boolean) {
        val schedule = TripDataManager.getSchedule(tripId)
        val serviceDate = TripDataManager.getServiceDate(tripId) ?: 0L
        val distribution: ProbDistribution? = if (!tripEnded)
            VehicleTrajectoryTracker.extrapolate(tripId, System.currentTimeMillis())
        else null
        graphView.setData(history, schedule, serviceDate, distribution)
    }

    // --- Polling ---

    private fun pollTrip() {
        Thread {
            try {
                val response = ObaTripDetailsRequest.newRequest(applicationContext, tripId).call()
                if (response != null && response.code == ObaApi.OBA_OK) {
                    TripDataManager.recordTripDetailsResponse(tripId, response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to poll trip details for $tripId", e)
            }
        }.start()
    }

    // --- Table rendering ---

    private val tableHeaders = arrayOf("#", "AVL time", "Lat", "Lon",
            "Dist (m)", "\u0394t (s)", "\u0394dist (m)", "Speed (mph)", "Geo \u0394 (m)")
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun buildTable(table: TableLayout, history: List<ObaTripStatus>) {
        addHeaderRow(table)
        addDivider(table)

        if (history.isEmpty()) {
            addEmptyRow(table)
            return
        }

        var prev: ObaTripStatus? = null
        for ((i, entry) in history.withIndex()) {
            addDataRow(table, i, entry, prev)
            prev = entry
        }
    }

    private fun addHeaderRow(table: TableLayout) {
        val row = TableRow(this).apply { setBackgroundColor(0xFF424242.toInt()) }
        tableHeaders.forEach { row.addView(createCell(it, isHeader = true)) }
        table.addView(row)
    }

    private fun addDivider(table: TableLayout) {
        table.addView(View(this).apply {
            layoutParams = TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, 2)
            setBackgroundColor(0xFF888888.toInt())
        })
    }

    private fun addEmptyRow(table: TableLayout) {
        val row = TableRow(this)
        val cell = createCell("No data collected yet \u2014 waiting for updates...", isHeader = false)
        cell.layoutParams = TableRow.LayoutParams().apply { span = tableHeaders.size }
        row.addView(cell)
        table.addView(row)
    }

    private fun addDataRow(table: TableLayout, index: Int, entry: ObaTripStatus, prev: ObaTripStatus?) {
        val row = TableRow(this).apply {
            setBackgroundColor(if (index % 2 == 0) 0xFF1A1A1A.toInt() else 0xFF262626.toInt())
        }
        val pos = entry.lastKnownLocation
        val entryDist = entry.bestDistanceAlongTrip
        val avlTime = entry.lastLocationUpdateTime

        row.addView(cell("${index + 1}"))
        row.addView(cell(if (avlTime > 0) timeFmt.format(Date(avlTime)) else "\u2014"))
        row.addView(cell(pos?.let { "%.6f".format(it.latitude) } ?: "\u2014"))
        row.addView(cell(pos?.let { "%.6f".format(it.longitude) } ?: "\u2014"))
        row.addView(cell(entryDist?.let { "%.1f".format(it) } ?: "\u2014"))

        if (prev != null) {
            addDeltaCells(row, entry, prev, avlTime, entryDist, pos)
        } else {
            repeat(4) { row.addView(cell("")) }
        }

        table.addView(row)
    }

    private fun addDeltaCells(row: TableRow, entry: ObaTripStatus, prev: ObaTripStatus,
                               avlTime: Long, entryDist: Double?,
                               pos: android.location.Location?) {
        val prevAvl = prev.lastLocationUpdateTime
        val dtMs = if (avlTime > 0 && prevAvl > 0) avlTime - prevAvl
                   else entry.lastUpdateTime - prev.lastUpdateTime
        row.addView(cell("%.1f".format(dtMs / 1000.0)))

        val prevDist = prev.bestDistanceAlongTrip
        if (prevDist != null && entryDist != null) {
            val dd = entryDist - prevDist
            row.addView(cell("%.1f".format(dd)))
            row.addView(cell(if (dtMs > 0) "%.1f".format(maxOf(0.0, dd) / (dtMs / 1000.0) * MPS_TO_MPH) else "\u2014"))
        } else {
            row.addView(cell("\u2014"))
            row.addView(cell("\u2014"))
        }

        row.addView(cell(
                if (prev.lastKnownLocation != null && pos != null)
                    "%.1f".format(prev.lastKnownLocation.distanceTo(pos))
                else "\u2014"))
    }

    private fun cell(text: String) = createCell(text, isHeader = false)

    private fun createCell(text: String, isHeader: Boolean) = TextView(this).apply {
        this.text = text
        setPadding(PAD_H, PAD_V, PAD_H, PAD_V)
        textSize = TEXT_SIZE
        gravity = if (isHeader) Gravity.CENTER else Gravity.END
        isSingleLine = true
        setTextColor(Color.WHITE)
        if (isHeader) setTypeface(null, Typeface.BOLD)
    }
}
