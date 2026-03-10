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
import android.os.Looper;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;

import org.onebusaway.android.R;
import org.onebusaway.android.io.elements.ObaTripSchedule;
import org.onebusaway.android.speed.CalibrationTracker;
import org.onebusaway.android.speed.DistanceExtrapolator;
import org.onebusaway.android.speed.VehicleHistoryEntry;
import org.onebusaway.android.speed.VehicleTrajectoryTracker;
import org.onebusaway.android.util.UIUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Debug activity that displays all collected location data for a vehicle's trip
 * in a scrollable table and a distance-time graph. Data collection is managed by
 * VehicleTrajectoryTracker's polling infrastructure; this activity only refreshes its UI display.
 */
public class VehicleLocationDataActivity extends AppCompatActivity {

    private static final String EXTRA_TRIP_ID = ".TripId";
    private static final String EXTRA_VEHICLE_ID = ".VehicleId";
    private static final String EXTRA_STOP_ID = ".StopId";

    private static final int PAD_H = 12;
    private static final int PAD_V = 6;
    private static final int TEXT_SIZE = 12;
    private static final long UI_REFRESH_PERIOD = 1_000;

    private static final double[] COVERAGE_LEVELS = {0.50, 0.80, 0.95};
    private static final String[] HISTOGRAM_BLOCKS = {
            " ", "\u2581", "\u2582", "\u2583", "\u2584",
            "\u2585", "\u2586", "\u2587", "\u2588"
    };
    private String mTripId;
    private String mStopId;
    private final Handler mRefreshHandler = new Handler(Looper.getMainLooper());
    private int mLastRowCount = -1;

    private View mTableContainer;
    private TrajectoryGraphView mGraphView;
    private View mCalibrationContainer;
    private TextView mCalibrationText;
    private CalibrationTracker mCalibrationTracker;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            refreshData();
            mRefreshHandler.postDelayed(mRefresh, UI_REFRESH_PERIOD);
        }
    };

    public static void start(Context context, String tripId, String vehicleId) {
        start(context, tripId, vehicleId, null);
    }

    public static void start(Context context, String tripId, String vehicleId, String stopId) {
        Intent intent = new Intent(context, VehicleLocationDataActivity.class);
        intent.putExtra(EXTRA_TRIP_ID, tripId);
        intent.putExtra(EXTRA_VEHICLE_ID, vehicleId);
        intent.putExtra(EXTRA_STOP_ID, stopId);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UIUtils.setupActionBar(this);
        setContentView(R.layout.activity_vehicle_location_data);

        mTripId = getIntent().getStringExtra(EXTRA_TRIP_ID);
        mStopId = getIntent().getStringExtra(EXTRA_STOP_ID);
        String vehicleId = getIntent().getStringExtra(EXTRA_VEHICLE_ID);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Trip Trajectory");
            if (vehicleId != null) {
                getSupportActionBar().setSubtitle("Vehicle: " + vehicleId);
            }
        }

        mTableContainer = findViewById(R.id.location_data_table_container);
        mGraphView = findViewById(R.id.location_data_graph);
        mGraphView.setHighlightedStopId(mStopId);
        mCalibrationContainer = findViewById(R.id.location_data_calibration_container);
        mCalibrationText = findViewById(R.id.location_data_calibration);
        mCalibrationTracker = VehicleTrajectoryTracker.getInstance()
                .getCalibrationTracker(mTripId);
        mGraphView.setCalibrationTracker(mCalibrationTracker);

        TabLayout tabs = findViewById(R.id.location_data_tabs);
        tabs.addTab(tabs.newTab().setText("Table"));
        tabs.addTab(tabs.newTab().setText("Graph"));
        tabs.addTab(tabs.newTab().setText("Calibration"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                mTableContainer.setVisibility(View.GONE);
                mGraphView.setVisibility(View.GONE);
                mCalibrationContainer.setVisibility(View.GONE);
                switch (tab.getPosition()) {
                    case 0:
                        mTableContainer.setVisibility(View.VISIBLE);
                        break;
                    case 1:
                        mGraphView.setVisibility(View.VISIBLE);
                        break;
                    case 2:
                        mCalibrationContainer.setVisibility(View.VISIBLE);
                        break;
                }
                refreshData();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        refreshData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        VehicleTrajectoryTracker.getInstance()
                .subscribeTripPolling(getApplicationContext(), mTripId);
        refreshData();
        mRefreshHandler.postDelayed(mRefresh, UI_REFRESH_PERIOD);
    }

    @Override
    protected void onPause() {
        mRefreshHandler.removeCallbacks(mRefresh);
        VehicleTrajectoryTracker.getInstance().unsubscribeTripPolling(mTripId);
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

    private void refreshData() {
        VehicleTrajectoryTracker tracker = VehicleTrajectoryTracker.getInstance();
        List<VehicleHistoryEntry> history = tracker.getHistory(mTripId);

        // Check if the vehicle is still serving this trip
        String activeTripId = tracker.getLastActiveTripId(mTripId);
        boolean tripEnded = activeTripId != null && !mTripId.equals(activeTripId);

        // Update header
        int currentCount = history.size();
        TextView header = findViewById(R.id.location_data_header);
        if (tripEnded) {
            header.setText(String.format(Locale.US,
                    "Trip: %s  |  Samples: %d  |  Vehicle no longer serving trip",
                    mTripId, currentCount));
        } else {
            header.setText(String.format(Locale.US, "Trip: %s  |  Samples: %d",
                    mTripId, currentCount));
        }

        // Refresh table only if row count changed
        if (currentCount != mLastRowCount) {
            mLastRowCount = currentCount;
            TableLayout table = findViewById(R.id.location_data_table);
            table.removeAllViews();
            buildTable(table, history);
        }

        // Feed calibration tracker
        if (!tripEnded && !history.isEmpty()) {
            Double speed = tracker.getEstimatedSpeed(mTripId);
            double velVariance = tracker.getEstimatedVelVariance();
            double scheduleSpeed = tracker.getLastScheduleSpeed();
            VehicleHistoryEntry newestValid = DistanceExtrapolator.findNewestValidEntry(history);
            if (newestValid != null && speed != null && speed > 0) {
                Double lastDist = newestValid.getBestDistanceAlongTrip();
                long lastAvlTime = newestValid.getLastLocationUpdateTime();
                if (lastDist != null && lastAvlTime > 0) {
                    mCalibrationTracker.recordPrediction(
                            System.currentTimeMillis(), lastDist, lastAvlTime,
                            speed, velVariance, scheduleSpeed);
                    mCalibrationTracker.checkNewAvl(newestValid);
                }
            }
        }

        // Refresh graph only when visible
        if (mGraphView.getVisibility() == View.VISIBLE) {
            refreshGraph(tripEnded);
        }

        // Refresh calibration only when visible
        if (mCalibrationContainer.getVisibility() == View.VISIBLE) {
            refreshCalibration();
        }
    }

    private void refreshGraph(boolean tripEnded) {
        VehicleTrajectoryTracker tracker = VehicleTrajectoryTracker.getInstance();
        List<VehicleHistoryEntry> history = tracker.getHistory(mTripId);
        ObaTripSchedule schedule = tracker.getSchedule(mTripId);
        Long serviceDate = tracker.getServiceDate(mTripId);
        Double speed = tripEnded ? null : tracker.getEstimatedSpeed(mTripId);
        double velVariance = tripEnded ? 0 : tracker.getEstimatedVelVariance();
        double scheduleSpeed = tripEnded ? 0 : tracker.getLastScheduleSpeed();
        mGraphView.setModelCoverageRange(
                mCalibrationTracker.getMinPredictionTime(),
                mCalibrationTracker.getMaxPredictionTime());
        mGraphView.setData(history, schedule, serviceDate != null ? serviceDate : 0, speed,
                velVariance, scheduleSpeed);
    }

    private void refreshCalibration() {
        int count = mCalibrationTracker.getSampleCount();
        if (count == 0) {
            mCalibrationText.setText("No calibration samples yet.\n\n"
                    + "Samples accumulate as new AVL observations arrive\n"
                    + "(approximately every 30 seconds).");
            return;
        }

        int numBins = CalibrationTracker.DEFAULT_HISTOGRAM_BINS;
        double[] hist = mCalibrationTracker.getPitHistogram(numBins);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Calibration Samples: %d\n\n", count));

        // Coverage metrics
        sb.append("Coverage (empirical vs nominal):\n");
        for (double level : COVERAGE_LEVELS) {
            double coverage = mCalibrationTracker.getCoverageAt(level);
            sb.append(String.format(Locale.US, "  %2.0f%% CI: %5.1f%% (nominal %2.0f%%)\n",
                    level * 100, coverage * 100, level * 100));
        }

        double mace = mCalibrationTracker.getMeanAbsoluteCalibrationError(hist);
        sb.append(String.format(Locale.US, "\nMACE: %.4f", mace));
        sb.append(mace < 0.02 ? " (excellent)" : mace < 0.05 ? " (good)" :
                mace < 0.10 ? " (fair)" : " (poor)");

        // PIT histogram
        sb.append("\n\nPIT Histogram (").append(numBins).append(" bins):\n");
        double maxBin = 0;
        for (double h : hist) {
            if (h > maxBin) maxBin = h;
        }

        int barHeight = 8;
        for (int row = barHeight; row >= 1; row--) {
            sb.append("  ");
            double threshold = maxBin * row / barHeight;
            for (int b = 0; b < numBins; b++) {
                if (hist[b] >= threshold) {
                    sb.append("\u2588\u2588");
                } else {
                    double prev = maxBin * (row - 1) / barHeight;
                    if (hist[b] > prev) {
                        int level = (int) ((hist[b] - prev) / (threshold - prev) * 8);
                        level = Math.max(0, Math.min(8, level));
                        sb.append(HISTOGRAM_BLOCKS[level]).append(HISTOGRAM_BLOCKS[level]);
                    } else {
                        sb.append("  ");
                    }
                }
            }
            sb.append('\n');
        }
        // X-axis labels
        sb.append("  ");
        for (int b = 0; b < numBins; b++) {
            sb.append(String.format(Locale.US, ".%d", b));
        }
        sb.append('\n');

        double uniform = 1.0 / numBins;
        sb.append(String.format(Locale.US, "\nUniform: %.1f%% per bin", uniform * 100));

        mCalibrationText.setText(sb.toString());
    }

    private void buildTable(TableLayout table, List<VehicleHistoryEntry> history) {
        // Header row
        String[] headers = {"#", "AVL time", "Lat", "Lon",
                "Dist (m)", "\u0394t (s)",
                "\u0394dist (m)", "Speed (mph)", "Geo \u0394 (m)", "P(\u2264d)"};
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
                        double speedMph = (speedDist / (dtMs / 1000.0)) * VehicleTrajectoryTracker.MPS_TO_MPH;
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

                // P(≤d): CDF from model prediction at time of this AVL
                Double curDist = entry.getBestDistanceAlongTrip();
                if (curDist != null && avlTime > 0) {
                    Double pit = mCalibrationTracker.computePitAt(avlTime, curDist);
                    if (pit != null) {
                        row.addView(createCell(
                                String.format(Locale.US, "%.0f%%", pit * 100), false));
                    } else {
                        row.addView(createCell("\u2014", false));
                    }
                } else {
                    row.addView(createCell("\u2014", false));
                }
            } else {
                row.addView(createCell("", false));
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
