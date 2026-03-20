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
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import org.onebusaway.android.R
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.util.UIUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hub activity listing all trips with collected AVL data. Tapping a row opens
 * [VehicleLocationDataActivity] for that trip.
 */
class DataViewActivity : AppCompatActivity() {

    companion object {
        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, DataViewActivity::class.java))
        }
    }

    private val timeFmt = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
    private lateinit var listView: ListView
    private var adapter: TripAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_view)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        UIUtils.setupActionBar(this)
        supportActionBar?.setTitle(R.string.data_views_title)

        listView = findViewById(R.id.trip_list)
        listView.emptyView = findViewById(R.id.empty_text)
        listView.setOnItemClickListener { _, _, position, _ ->
            adapter?.getItem(position)?.let { row ->
                VehicleLocationDataActivity.start(this, row.tripId, row.vehicleId)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun refreshList() {
        val rows = TripDataManager.getTrackedTripIds().map { tripId ->
            val count = TripDataManager.getHistorySize(tripId)
            val lastState = TripDataManager.getLastState(tripId)
            TripRow(tripId, lastState?.vehicleId, count, lastState?.lastLocationUpdateTime ?: 0)
        }

        adapter?.updateRows(rows) ?: run {
            adapter = TripAdapter(rows)
            listView.adapter = adapter
        }
    }

    // --- Data and adapter ---

    private data class TripRow(
            val tripId: String,
            val vehicleId: String?,
            val sampleCount: Int,
            val lastUpdateTime: Long
    )

    private inner class TripAdapter(private var rows: List<TripRow>) : BaseAdapter() {
        fun updateRows(newRows: List<TripRow>) { rows = newRows; notifyDataSetChanged() }
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@DataViewActivity)
                    .inflate(R.layout.item_data_view_trip, parent, false)
            val row = rows[position]
            view.findViewById<TextView>(R.id.trip_id).text = row.tripId
            view.findViewById<TextView>(R.id.trip_details).text = buildDetailText(row)
            return view
        }

        private fun buildDetailText(row: TripRow) = buildString {
            row.vehicleId?.let { append("Vehicle: $it  ") }
            append("Samples: ${row.sampleCount}")
            if (row.lastUpdateTime > 0) {
                append("  Last: ${timeFmt.format(Date(row.lastUpdateTime))}")
            }
        }
    }
}
