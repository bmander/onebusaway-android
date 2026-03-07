/*
 * Copyright (C) 2024 Open Transit Software Foundation
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
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import org.onebusaway.android.R;
import org.onebusaway.android.io.ObaApi;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.request.ObaTripDetailsRequest;
import org.onebusaway.android.io.request.ObaTripDetailsResponse;
import org.onebusaway.android.speed.VehicleHistoryEntry;
import org.onebusaway.android.speed.VehicleSpeedTracker;
import org.onebusaway.android.speed.VehicleState;
import org.onebusaway.android.util.UIUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Debug activity that displays all collected location data for a vehicle's trip
 * in a scrollable table. Continues to fetch and record vehicle positions while open.
 */
public class VehicleLocationDataActivity extends AppCompatActivity {

    private static final String TAG = "VehicleLocationData";
    private static final String EXTRA_TRIP_ID = ".TripId";
    private static final String EXTRA_VEHICLE_ID = ".VehicleId";

    private static final int PAD_H = 12;
    private static final int PAD_V = 6;
    private static final int TEXT_SIZE = 12;
    private static final long REFRESH_PERIOD = 30 * 1000;

    private String mTripId;
    private final Handler mRefreshHandler = new Handler();
    private int mLastRowCount = -1;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            fetchAndRecord();
        }
    };

    public static void start(Context context, String tripId, String vehicleId) {
        Intent intent = new Intent(context, VehicleLocationDataActivity.class);
        intent.putExtra(EXTRA_TRIP_ID, tripId);
        intent.putExtra(EXTRA_VEHICLE_ID, vehicleId);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UIUtils.setupActionBar(this);
        setContentView(R.layout.activity_vehicle_location_data);

        mTripId = getIntent().getStringExtra(EXTRA_TRIP_ID);
        String vehicleId = getIntent().getStringExtra(EXTRA_VEHICLE_ID);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Location Data");
            if (vehicleId != null) {
                getSupportActionBar().setSubtitle("Vehicle: " + vehicleId);
            }
        }

        refreshTable();
    }

    @Override
    protected void onResume() {
        super.onResume();
        fetchAndRecord();
    }

    @Override
    protected void onPause() {
        mRefreshHandler.removeCallbacks(mRefresh);
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void fetchAndRecord() {
        final String tripId = mTripId;
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            try {
                ObaTripDetailsResponse response =
                        ObaTripDetailsRequest.newRequest(ctx, tripId).call();
                if (response != null && response.getCode() == ObaApi.OBA_OK) {
                    ObaTripStatus status = response.getStatus();
                    if (status != null && status.getActiveTripId() != null) {
                        VehicleState state = VehicleState.fromTripStatus(status);
                        VehicleSpeedTracker.getInstance()
                                .recordState(status.getActiveTripId(), state);
                        Log.d(TAG, "Recorded vehicle position for " + tripId);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch trip details for " + tripId, e);
            }
            runOnUiThread(() -> {
                refreshTable();
                mRefreshHandler.removeCallbacks(mRefresh);
                mRefreshHandler.postDelayed(mRefresh, REFRESH_PERIOD);
            });
        }).start();
    }

    private void refreshTable() {
        List<VehicleHistoryEntry> history =
                VehicleSpeedTracker.getInstance().getHistory(mTripId);

        // Skip rebuild if row count hasn't changed
        if (history.size() == mLastRowCount) {
            return;
        }
        mLastRowCount = history.size();

        TextView header = findViewById(R.id.location_data_header);
        header.setText(String.format(Locale.US, "Trip: %s  |  Samples: %d",
                mTripId, history.size()));

        TableLayout table = findViewById(R.id.location_data_table);
        table.removeAllViews();
        buildTable(table, history);
    }

    private void buildTable(TableLayout table, List<VehicleHistoryEntry> history) {
        // Header row
        String[] headers = {"#", "AVL time", "Lat", "Lon",
                "Dist (m)", "\u0394t (s)",
                "\u0394dist (m)", "Speed (mph)", "Geo \u0394 (m)"};
        TableRow headerRow = new TableRow(this);
        headerRow.setBackgroundColor(0xFF424242);
        for (String h : headers) {
            headerRow.addView(createCell(h, true));
        }
        table.addView(headerRow);

        // Divider
        View divider = new View(this);
        divider.setLayoutParams(new TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT, 2));
        divider.setBackgroundColor(0xFF888888);
        table.addView(divider);

        if (history.isEmpty()) {
            TableRow emptyRow = new TableRow(this);
            TextView cell = createCell("No data collected yet \u2014 waiting for updates...", false);
            TableRow.LayoutParams params = new TableRow.LayoutParams();
            params.span = headers.length;
            cell.setLayoutParams(params);
            emptyRow.addView(cell);
            table.addView(emptyRow);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);

        VehicleHistoryEntry prev = null;
        for (int i = 0; i < history.size(); i++) {
            VehicleHistoryEntry entry = history.get(i);
            Location pos = entry.getPosition();

            TableRow row = new TableRow(this);
            row.setBackgroundColor(i % 2 == 0 ? 0xFF1A1A1A : 0xFF262626);

            // #
            row.addView(createCell(String.valueOf(i + 1), false));

            // AVL time (when the vehicle actually reported)
            long avlTime = entry.getLastLocationUpdateTime();
            row.addView(createCell(
                    avlTime > 0 ? sdf.format(new Date(avlTime)) : "\u2014", false));

            // Lat, Lon
            if (pos != null) {
                row.addView(createCell(
                        String.format(Locale.US, "%.6f", pos.getLatitude()), false));
                row.addView(createCell(
                        String.format(Locale.US, "%.6f", pos.getLongitude()), false));
            } else {
                row.addView(createCell("\u2014", false));
                row.addView(createCell("\u2014", false));
            }

            // Distance along trip
            if (entry.getDistanceAlongTrip() != null) {
                row.addView(createCell(
                        String.format(Locale.US, "%.1f", entry.getDistanceAlongTrip()), false));
            } else {
                row.addView(createCell("\u2014", false));
            }

            // Delta columns
            if (prev != null) {
                // Δt from AVL timestamps
                long prevAvl = prev.getLastLocationUpdateTime();
                long dtMs = (avlTime > 0 && prevAvl > 0)
                        ? avlTime - prevAvl
                        : entry.getTimestamp() - prev.getTimestamp();
                row.addView(createCell(
                        String.format(Locale.US, "%.1f", dtMs / 1000.0), false));

                // Δdist
                if (prev.getDistanceAlongTrip() != null
                        && entry.getDistanceAlongTrip() != null) {
                    double dd = entry.getDistanceAlongTrip() - prev.getDistanceAlongTrip();
                    row.addView(createCell(
                            String.format(Locale.US, "%.1f", dd), false));

                    // Speed
                    if (dtMs > 0) {
                        double speedDist = dd < 0 ? 0 : dd;
                        double speedMph = (speedDist / (dtMs / 1000.0)) * 2.23694;
                        row.addView(createCell(
                                String.format(Locale.US, "%.1f", speedMph), false));
                    } else {
                        row.addView(createCell("\u2014", false));
                    }
                } else {
                    row.addView(createCell("\u2014", false));
                    row.addView(createCell("\u2014", false));
                }

                // Geo distance
                if (prev.getPosition() != null && pos != null) {
                    float geoDistM = prev.getPosition().distanceTo(pos);
                    row.addView(createCell(
                            String.format(Locale.US, "%.1f", geoDistM), false));
                } else {
                    row.addView(createCell("\u2014", false));
                }
            } else {
                row.addView(createCell("", false));
                row.addView(createCell("", false));
                row.addView(createCell("", false));
                row.addView(createCell("", false));
            }

            table.addView(row);
            prev = entry;
        }
    }

    private TextView createCell(String text, boolean isHeader) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(PAD_H, PAD_V, PAD_H, PAD_V);
        tv.setTextSize(TEXT_SIZE);
        tv.setGravity(Gravity.END);
        tv.setSingleLine(true);
        tv.setTextColor(Color.WHITE);
        if (isHeader) {
            tv.setTypeface(null, Typeface.BOLD);
            tv.setGravity(Gravity.CENTER);
        }
        return tv;
    }
}
