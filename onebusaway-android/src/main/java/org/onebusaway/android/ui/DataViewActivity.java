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
package org.onebusaway.android.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import org.onebusaway.android.R;
import org.onebusaway.android.extrapolation.data.TripDataManager;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.util.UIUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Hub activity listing all trips with collected AVL data. Tapping a row opens
 * VehicleLocationDataActivity for that trip.
 */
public class DataViewActivity extends AppCompatActivity {

    private final SimpleDateFormat mTimeFmt =
            new SimpleDateFormat("h:mm:ss a", Locale.getDefault());

    private ListView mListView;
    private TextView mEmptyText;
    private TripAdapter mAdapter;

    public static void start(Context context) {
        context.startActivity(new Intent(context, DataViewActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_view);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        UIUtils.setupActionBar(this);
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            bar.setTitle(R.string.data_views_title);
        }

        mListView = findViewById(R.id.trip_list);
        mEmptyText = findViewById(R.id.empty_text);
        mListView.setEmptyView(mEmptyText);
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            TripRow row = mAdapter.getItem(position);
            VehicleLocationDataActivity.start(DataViewActivity.this,
                    row.tripId, row.vehicleId);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refreshList() {
        TripDataManager dm = TripDataManager.getInstance();
        Set<String> tripIds = dm.getTrackedTripIds();

        List<TripRow> rows = new ArrayList<>();
        for (String tripId : tripIds) {
            int count = dm.getHistorySize(tripId);
            ObaTripStatus lastState = dm.getLastState(tripId);
            String vehicleId = lastState != null ? lastState.getVehicleId() : null;
            long lastUpdate = lastState != null ? lastState.getLastLocationUpdateTime() : 0;
            rows.add(new TripRow(tripId, vehicleId, count, lastUpdate));
        }

        if (mAdapter == null) {
            mAdapter = new TripAdapter(rows);
            mListView.setAdapter(mAdapter);
        } else {
            mAdapter.updateRows(rows);
        }
    }

    private static class TripRow {
        final String tripId;
        final String vehicleId;
        final int sampleCount;
        final long lastUpdateTime;

        TripRow(String tripId, String vehicleId, int sampleCount, long lastUpdateTime) {
            this.tripId = tripId;
            this.vehicleId = vehicleId;
            this.sampleCount = sampleCount;
            this.lastUpdateTime = lastUpdateTime;
        }
    }

    private class TripAdapter extends BaseAdapter {
        private List<TripRow> rows;

        TripAdapter(List<TripRow> rows) {
            this.rows = rows;
        }

        void updateRows(List<TripRow> rows) {
            this.rows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public TripRow getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(DataViewActivity.this)
                        .inflate(R.layout.item_data_view_trip, parent, false);
            }
            TripRow row = rows.get(position);

            TextView tripIdView = convertView.findViewById(R.id.trip_id);
            TextView detailsView = convertView.findViewById(R.id.trip_details);

            tripIdView.setText(row.tripId);

            StringBuilder details = new StringBuilder();
            if (row.vehicleId != null) {
                details.append("Vehicle: ").append(row.vehicleId);
            }
            details.append("  Samples: ").append(row.sampleCount);
            if (row.lastUpdateTime > 0) {
                details.append("  Last: ").append(mTimeFmt.format(new Date(row.lastUpdateTime)));
            }
            detailsView.setText(details.toString().trim());

            return convertView;
        }
    }
}
