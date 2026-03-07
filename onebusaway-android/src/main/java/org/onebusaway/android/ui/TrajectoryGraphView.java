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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import org.onebusaway.android.io.elements.ObaTripSchedule;
import org.onebusaway.android.speed.VehicleHistoryEntry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Custom View that draws a distance-time graph comparing scheduled vs actual vehicle trajectory.
 */
public class TrajectoryGraphView extends View {

    private static final long TICK_INTERVAL_MS = 1000;

    private List<VehicleHistoryEntry> mHistory = new ArrayList<>();
    private ObaTripSchedule mSchedule;
    private long mServiceDate;
    private long mCurrentTime = System.currentTimeMillis();

    private final Paint mSchedulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mScheduleDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrajectoryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrajectoryDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNowLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNowLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAxisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path mSchedulePath = new Path();
    private final Path mTrajectoryPath = new Path();
    private final Date mReusableDate = new Date();

    private final float mDensity;
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private boolean mTickingActive;

    private final Handler mTickHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTickRunnable = new Runnable() {
        @Override
        public void run() {
            mCurrentTime = System.currentTimeMillis();
            invalidate();
            mTickHandler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    public TrajectoryGraphView(Context context) {
        this(context, null);
    }

    public TrajectoryGraphView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TrajectoryGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;
        initPaints();
    }

    private void initPaints() {
        mSchedulePaint.setColor(Color.parseColor("#4488FF"));
        mSchedulePaint.setStyle(Paint.Style.STROKE);
        mSchedulePaint.setStrokeWidth(2 * mDensity);

        mScheduleDotPaint.setColor(Color.parseColor("#4488FF"));
        mScheduleDotPaint.setStyle(Paint.Style.FILL);

        mTrajectoryPaint.setColor(Color.parseColor("#44CC44"));
        mTrajectoryPaint.setStyle(Paint.Style.STROKE);
        mTrajectoryPaint.setStrokeWidth(3 * mDensity);

        mTrajectoryDotPaint.setColor(Color.parseColor("#44CC44"));
        mTrajectoryDotPaint.setStyle(Paint.Style.FILL);

        mNowLinePaint.setColor(Color.parseColor("#FF4444"));
        mNowLinePaint.setStyle(Paint.Style.STROKE);
        mNowLinePaint.setStrokeWidth(1 * mDensity);
        mNowLinePaint.setPathEffect(new DashPathEffect(
                new float[]{8 * mDensity, 4 * mDensity}, 0));

        mAxisPaint.setColor(Color.parseColor("#888888"));
        mAxisPaint.setStyle(Paint.Style.STROKE);
        mAxisPaint.setStrokeWidth(1 * mDensity);

        mLabelPaint.setColor(Color.parseColor("#AAAAAA"));
        mLabelPaint.setTextSize(10 * mDensity);

        mGridPaint.setColor(Color.parseColor("#333333"));
        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setStrokeWidth(0.5f * mDensity);

        mNowLabelPaint.setColor(Color.parseColor("#FF4444"));
        mNowLabelPaint.setTextSize(10 * mDensity);
    }

    public void setData(List<VehicleHistoryEntry> history, ObaTripSchedule schedule,
                        long serviceDate) {
        mHistory = history != null ? new ArrayList<>(history) : new ArrayList<>();
        mSchedule = schedule;
        mServiceDate = serviceDate;
        mCurrentTime = System.currentTimeMillis();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateTicking();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTicking();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateTicking();
    }

    private void updateTicking() {
        if (isAttachedToWindow() && getVisibility() == VISIBLE) {
            startTicking();
        } else {
            stopTicking();
        }
    }

    private void startTicking() {
        if (!mTickingActive) {
            mTickingActive = true;
            mTickHandler.postDelayed(mTickRunnable, TICK_INTERVAL_MS);
        }
    }

    private void stopTicking() {
        if (mTickingActive) {
            mTickingActive = false;
            mTickHandler.removeCallbacks(mTickRunnable);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.parseColor("#1A1A1A"));

        float marginLeft = 65 * mDensity;
        float marginBottom = 35 * mDensity;
        float marginTop = 15 * mDensity;
        float marginRight = 15 * mDensity;

        float graphW = getWidth() - marginLeft - marginRight;
        float graphH = getHeight() - marginTop - marginBottom;

        if (graphW <= 0 || graphH <= 0) return;

        // Compute data bounds
        double minDist = 0;
        double maxDist = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;

        boolean hasData = false;

        // Schedule bounds
        if (mSchedule != null && mSchedule.getStopTimes() != null && mServiceDate > 0) {
            ObaTripSchedule.StopTime[] stops = mSchedule.getStopTimes();
            for (ObaTripSchedule.StopTime st : stops) {
                double d = st.getDistanceAlongTrip();
                long t = mServiceDate + st.getArrivalTime() * 1000;
                if (d > maxDist) maxDist = d;
                if (t < minTime) minTime = t;
                if (t > maxTime) maxTime = t;
                hasData = true;
            }
        }

        // Trajectory bounds
        for (VehicleHistoryEntry e : mHistory) {
            Double d = e.getBestDistanceAlongTrip();
            if (d != null) {
                if (d > maxDist) maxDist = d;
                if (d < minDist) minDist = d;
            }
            long t = e.getLastLocationUpdateTime();
            if (t > 0) {
                if (t < minTime) minTime = t;
                if (t > maxTime) maxTime = t;
                hasData = true;
            }
        }

        if (!hasData) {
            mNowLabelPaint.setTextSize(14 * mDensity);
            canvas.drawText("No data available", marginLeft + 10 * mDensity,
                    getHeight() / 2f, mNowLabelPaint);
            mNowLabelPaint.setTextSize(10 * mDensity);
            return;
        }

        // Extend time range to include current time
        if (mCurrentTime < minTime) minTime = mCurrentTime;
        if (mCurrentTime + 60_000 > maxTime) maxTime = mCurrentTime + 60_000;

        // Add some padding
        double distRange = maxDist - minDist;
        if (distRange < 100) distRange = 100;
        maxDist = minDist + distRange * 1.05;
        minDist = Math.max(0, minDist - distRange * 0.02);

        long timeRange = maxTime - minTime;
        if (timeRange < 60_000) timeRange = 60_000;
        minTime -= timeRange / 20;
        maxTime += timeRange / 20;
        timeRange = maxTime - minTime;

        // Draw axes
        canvas.drawLine(marginLeft, marginTop, marginLeft, getHeight() - marginBottom,
                mAxisPaint);
        canvas.drawLine(marginLeft, getHeight() - marginBottom,
                getWidth() - marginRight, getHeight() - marginBottom, mAxisPaint);

        // Grid and labels — Y axis (time)
        int timeGridCount = 5;
        long timeStep = timeRange / timeGridCount;
        // Round to nearest minute
        timeStep = Math.max(60_000, (timeStep / 60_000) * 60_000);
        long firstTimeTick = ((minTime / timeStep) + 1) * timeStep;
        for (long t = firstTimeTick; t < maxTime; t += timeStep) {
            float y = marginTop + graphH * (1f - (float) (t - minTime) / timeRange);
            canvas.drawLine(marginLeft, y, getWidth() - marginRight, y, mGridPaint);
            mReusableDate.setTime(t);
            String label = mTimeFmt.format(mReusableDate);
            canvas.drawText(label, 4 * mDensity, y + 4 * mDensity, mLabelPaint);
        }

        // Grid and labels — X axis (distance)
        double distRangeFinal = maxDist - minDist;
        int distGridCount = 5;
        double distStep = distRangeFinal / distGridCount;
        // Round to a nice number
        distStep = niceStep(distStep);
        double firstDistTick = Math.ceil(minDist / distStep) * distStep;
        for (double d = firstDistTick; d < maxDist; d += distStep) {
            float x = marginLeft + graphW * (float) ((d - minDist) / distRangeFinal);
            canvas.drawLine(x, marginTop, x, getHeight() - marginBottom, mGridPaint);
            String label;
            if (distStep >= 1000) {
                label = String.format(Locale.US, "%.1fkm", d / 1000.0);
            } else {
                label = String.format(Locale.US, "%.0fm", d);
            }
            canvas.drawText(label, x - 10 * mDensity,
                    getHeight() - marginBottom + 15 * mDensity, mLabelPaint);
        }

        // Draw schedule line
        if (mSchedule != null && mSchedule.getStopTimes() != null && mServiceDate > 0) {
            ObaTripSchedule.StopTime[] stops = mSchedule.getStopTimes();
            if (stops.length > 0) {
                mSchedulePath.reset();
                boolean first = true;
                for (ObaTripSchedule.StopTime st : stops) {
                    float x = marginLeft + graphW * (float) ((st.getDistanceAlongTrip() - minDist) / distRangeFinal);
                    long absTime = mServiceDate + st.getArrivalTime() * 1000;
                    float y = marginTop + graphH * (1f - (float) (absTime - minTime) / timeRange);
                    if (first) {
                        mSchedulePath.moveTo(x, y);
                        first = false;
                    } else {
                        mSchedulePath.lineTo(x, y);
                    }
                    canvas.drawCircle(x, y, 4 * mDensity, mScheduleDotPaint);
                }
                canvas.drawPath(mSchedulePath, mSchedulePaint);
            }
        }

        // Draw trajectory line
        if (!mHistory.isEmpty()) {
            mTrajectoryPath.reset();
            boolean first = true;
            for (VehicleHistoryEntry e : mHistory) {
                Double d = e.getBestDistanceAlongTrip();
                long t = e.getLastLocationUpdateTime();
                if (d == null || t <= 0) continue;
                float x = marginLeft + graphW * (float) ((d - minDist) / distRangeFinal);
                float y = marginTop + graphH * (1f - (float) (t - minTime) / timeRange);
                if (first) {
                    mTrajectoryPath.moveTo(x, y);
                    first = false;
                } else {
                    mTrajectoryPath.lineTo(x, y);
                }
                canvas.drawCircle(x, y, 3 * mDensity, mTrajectoryDotPaint);
            }
            canvas.drawPath(mTrajectoryPath, mTrajectoryPaint);
        }

        // Draw current time line
        float nowY = marginTop + graphH * (1f - (float) (mCurrentTime - minTime) / timeRange);
        if (nowY >= marginTop && nowY <= getHeight() - marginBottom) {
            canvas.drawLine(marginLeft, nowY, getWidth() - marginRight, nowY, mNowLinePaint);
            mReusableDate.setTime(mCurrentTime);
            String nowLabel = "now " + mTimeFmt.format(mReusableDate);
            canvas.drawText(nowLabel, marginLeft + 5 * mDensity, nowY - 4 * mDensity,
                    mNowLabelPaint);
        }

        // Legend
        float legendX = marginLeft + 10 * mDensity;
        float legendY = marginTop + 15 * mDensity;
        canvas.drawLine(legendX, legendY, legendX + 20 * mDensity, legendY, mSchedulePaint);
        canvas.drawText("Schedule", legendX + 25 * mDensity, legendY + 4 * mDensity, mLabelPaint);
        legendY += 18 * mDensity;
        canvas.drawLine(legendX, legendY, legendX + 20 * mDensity, legendY, mTrajectoryPaint);
        canvas.drawText("Actual", legendX + 25 * mDensity, legendY + 4 * mDensity, mLabelPaint);
    }

    private static double niceStep(double raw) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double residual = raw / magnitude;
        if (residual <= 1.5) return magnitude;
        if (residual <= 3.5) return 2 * magnitude;
        if (residual <= 7.5) return 5 * magnitude;
        return 10 * magnitude;
    }
}
