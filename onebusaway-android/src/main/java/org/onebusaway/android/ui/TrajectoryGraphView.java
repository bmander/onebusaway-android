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
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
    private static final int BG_COLOR = Color.parseColor("#1A1A1A");

    private List<VehicleHistoryEntry> mHistory = new ArrayList<>();
    private ObaTripSchedule mSchedule;
    private long mServiceDate;
    private long mCurrentTime = System.currentTimeMillis();
    private double mEstimatedSpeedMps;

    private final Paint mSchedulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mScheduleDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrajectoryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrajectoryDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNowLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNowLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAxisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mExtrapolatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mExtrapolateDashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mExtrapolateLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path mSchedulePath = new Path();
    private final Path mTrajectoryPath = new Path();
    private final Date mReusableDate = new Date();

    private final float mDensity;
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private boolean mTickingActive;

    // Zoom & pan state
    private float mScaleX = 1f;
    private float mScaleY = 1f;
    private double mOffsetDist = 0;
    private long mOffsetTime = 0;

    // Full data bounds (set during onDraw, used by gesture handlers)
    private double mFullMinDist = 0;
    private double mFullMaxDist = 0;
    private long mFullMinTime = 0;
    private long mFullMaxTime = 0;

    // Graph margins for gesture coordinate conversion
    private final float mMarginLeft;
    private final float mMarginTop;
    private final float mMarginRight;
    private final float mMarginBottom;

    private final ScaleGestureDetector mScaleDetector;
    private final GestureDetector mGestureDetector;

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
        mMarginLeft = 65 * mDensity;
        mMarginBottom = 35 * mDensity;
        mMarginTop = 15 * mDensity;
        mMarginRight = 15 * mDensity;
        initPaints();

        mScaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor();
                        float focusX = detector.getFocusX();
                        float focusY = detector.getFocusY();

                        float graphW = getWidth() - mMarginLeft - mMarginRight;
                        float graphH = getHeight() - mMarginTop - mMarginBottom;
                        if (graphW <= 0 || graphH <= 0) return true;

                        double fullDistRange = mFullMaxDist - mFullMinDist;
                        long fullTimeRange = mFullMaxTime - mFullMinTime;
                        if (fullDistRange <= 0 || fullTimeRange <= 0) return true;

                        // Data-space point under the focal point before scaling
                        double visDistRange = fullDistRange / mScaleX;
                        long visTimeRange = (long) (fullTimeRange / mScaleY);
                        double focalDist = mFullMinDist + mOffsetDist
                                + visDistRange * ((focusX - mMarginLeft) / graphW);
                        long focalTime = mFullMinTime + mOffsetTime
                                + visTimeRange - (long) (visTimeRange * ((focusY - mMarginTop) / graphH));

                        // Apply scale
                        float newScaleX = Math.max(1f, Math.min(20f, mScaleX * factor));
                        float newScaleY = Math.max(1f, Math.min(20f, mScaleY * factor));
                        mScaleX = newScaleX;
                        mScaleY = newScaleY;

                        // Adjust offsets so the focal data point stays under the finger
                        double newVisDistRange = fullDistRange / mScaleX;
                        long newVisTimeRange = (long) (fullTimeRange / mScaleY);
                        mOffsetDist = focalDist - mFullMinDist
                                - newVisDistRange * ((focusX - mMarginLeft) / graphW);
                        mOffsetTime = focalTime - mFullMinTime
                                - newVisTimeRange + (long) (newVisTimeRange * ((focusY - mMarginTop) / graphH));

                        clampOffsets();
                        invalidate();
                        return true;
                    }
                });

        mGestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float distanceX, float distanceY) {
                        float graphW = getWidth() - mMarginLeft - mMarginRight;
                        float graphH = getHeight() - mMarginTop - mMarginBottom;
                        if (graphW <= 0 || graphH <= 0) return true;

                        double fullDistRange = mFullMaxDist - mFullMinDist;
                        long fullTimeRange = mFullMaxTime - mFullMinTime;

                        double visDistRange = fullDistRange / mScaleX;
                        long visTimeRange = (long) (fullTimeRange / mScaleY);

                        // Convert pixel delta to data-space delta
                        mOffsetDist += visDistRange * (distanceX / graphW);
                        // Y is inverted (up = higher time, pixel up = negative distanceY)
                        mOffsetTime -= (long) (visTimeRange * (distanceY / graphH));

                        clampOffsets();
                        invalidate();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        mScaleX = 1f;
                        mScaleY = 1f;
                        mOffsetDist = 0;
                        mOffsetTime = 0;
                        invalidate();
                        return true;
                    }
                });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mScaleDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);
        if (mScaleX > 1f || mScaleY > 1f || mScaleDetector.isInProgress()) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return true;
    }

    private void clampOffsets() {
        double fullDistRange = mFullMaxDist - mFullMinDist;
        long fullTimeRange = mFullMaxTime - mFullMinTime;
        if (fullDistRange <= 0 || fullTimeRange <= 0) return;

        double visDistRange = fullDistRange / mScaleX;
        long visTimeRange = (long) (fullTimeRange / mScaleY);

        mOffsetDist = Math.max(0, Math.min(mOffsetDist, fullDistRange - visDistRange));
        mOffsetTime = Math.max(0, Math.min(mOffsetTime, fullTimeRange - visTimeRange));
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

        mExtrapolatePaint.setColor(Color.parseColor("#BBBBBB"));
        mExtrapolatePaint.setStyle(Paint.Style.STROKE);
        mExtrapolatePaint.setStrokeWidth(1.5f * mDensity);

        mExtrapolateDashPaint.setColor(Color.parseColor("#BBBBBB"));
        mExtrapolateDashPaint.setStyle(Paint.Style.STROKE);
        mExtrapolateDashPaint.setStrokeWidth(1.5f * mDensity);
        mExtrapolateDashPaint.setPathEffect(new DashPathEffect(
                new float[]{6 * mDensity, 4 * mDensity}, 0));

        mExtrapolateLabelPaint.setColor(Color.parseColor("#BBBBBB"));
        mExtrapolateLabelPaint.setTextSize(10 * mDensity);
    }

    public void setData(List<VehicleHistoryEntry> history, ObaTripSchedule schedule,
                        long serviceDate, Double estimatedSpeedMps) {
        mHistory = history != null ? new ArrayList<>(history) : new ArrayList<>();
        mSchedule = schedule;
        mServiceDate = serviceDate;
        mEstimatedSpeedMps = estimatedSpeedMps != null ? estimatedSpeedMps : 0;
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
        canvas.drawColor(BG_COLOR);

        float graphW = getWidth() - mMarginLeft - mMarginRight;
        float graphH = getHeight() - mMarginTop - mMarginBottom;

        if (graphW <= 0 || graphH <= 0) return;

        // Compute full data bounds
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
            canvas.drawText("No data available", mMarginLeft + 10 * mDensity,
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

        // Store full data bounds for gesture handlers
        mFullMinDist = minDist;
        mFullMaxDist = maxDist;
        mFullMinTime = minTime;
        mFullMaxTime = maxTime;
        clampOffsets();

        // Compute visible window based on zoom/pan
        double fullDistRange = maxDist - minDist;
        long fullTimeRange = maxTime - minTime;

        double visDistRange = fullDistRange / mScaleX;
        long visTimeRange = (long) (fullTimeRange / mScaleY);

        double visMinDist = minDist + mOffsetDist;
        double visMaxDist = visMinDist + visDistRange;
        long visMinTime = minTime + mOffsetTime;
        long visMaxTime = visMinTime + visTimeRange;

        // Draw axes (outside clip)
        canvas.drawLine(mMarginLeft, mMarginTop, mMarginLeft, getHeight() - mMarginBottom,
                mAxisPaint);
        canvas.drawLine(mMarginLeft, getHeight() - mMarginBottom,
                getWidth() - mMarginRight, getHeight() - mMarginBottom, mAxisPaint);

        // Y-axis labels (time) — outside clip
        int timeGridCount = 5;
        long timeStep = visTimeRange / timeGridCount;
        timeStep = Math.max(60_000, (timeStep / 60_000) * 60_000);
        long firstTimeTick = ((visMinTime / timeStep) + 1) * timeStep;
        for (long t = firstTimeTick; t < visMaxTime; t += timeStep) {
            float y = mMarginTop + graphH * (1f - (float) (t - visMinTime) / visTimeRange);
            if (y >= mMarginTop && y <= getHeight() - mMarginBottom) {
                mReusableDate.setTime(t);
                String label = mTimeFmt.format(mReusableDate);
                canvas.drawText(label, 4 * mDensity, y + 4 * mDensity, mLabelPaint);
            }
        }

        // X-axis labels (distance) — outside clip
        double distStepVis = visDistRange / 5;
        distStepVis = niceStep(distStepVis);
        double firstDistTick = Math.ceil(visMinDist / distStepVis) * distStepVis;
        for (double d = firstDistTick; d < visMaxDist; d += distStepVis) {
            float x = mMarginLeft + graphW * (float) ((d - visMinDist) / visDistRange);
            if (x >= mMarginLeft && x <= getWidth() - mMarginRight) {
                String label;
                if (distStepVis >= 1000) {
                    label = String.format(Locale.US, "%.1fkm", d / 1000.0);
                } else {
                    label = String.format(Locale.US, "%.0fm", d);
                }
                canvas.drawText(label, x - 10 * mDensity,
                        getHeight() - mMarginBottom + 15 * mDensity, mLabelPaint);
            }
        }

        // Clip to graph area for data drawing
        canvas.save();
        canvas.clipRect(mMarginLeft, mMarginTop, getWidth() - mMarginRight,
                getHeight() - mMarginBottom);

        // Grid lines
        for (long t = firstTimeTick; t < visMaxTime; t += timeStep) {
            float y = mMarginTop + graphH * (1f - (float) (t - visMinTime) / visTimeRange);
            canvas.drawLine(mMarginLeft, y, getWidth() - mMarginRight, y, mGridPaint);
        }
        for (double d = firstDistTick; d < visMaxDist; d += distStepVis) {
            float x = mMarginLeft + graphW * (float) ((d - visMinDist) / visDistRange);
            canvas.drawLine(x, mMarginTop, x, getHeight() - mMarginBottom, mGridPaint);
        }

        // Draw schedule line
        if (mSchedule != null && mSchedule.getStopTimes() != null && mServiceDate > 0) {
            ObaTripSchedule.StopTime[] stops = mSchedule.getStopTimes();
            if (stops.length > 0) {
                mSchedulePath.reset();
                boolean first = true;
                for (ObaTripSchedule.StopTime st : stops) {
                    float x = mMarginLeft + graphW * (float) ((st.getDistanceAlongTrip() - visMinDist) / visDistRange);
                    long absTime = mServiceDate + st.getArrivalTime() * 1000;
                    float y = mMarginTop + graphH * (1f - (float) (absTime - visMinTime) / visTimeRange);
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
                float x = mMarginLeft + graphW * (float) ((d - visMinDist) / visDistRange);
                float y = mMarginTop + graphH * (1f - (float) (t - visMinTime) / visTimeRange);
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

        // Draw extrapolation line from last trajectory point to "now"
        if (mEstimatedSpeedMps > 0 && !mHistory.isEmpty()) {
            Double lastDist = null;
            long lastTime = 0;
            for (int i = mHistory.size() - 1; i >= 0; i--) {
                VehicleHistoryEntry e = mHistory.get(i);
                Double d = e.getBestDistanceAlongTrip();
                long t = e.getLastLocationUpdateTime();
                if (d != null && t > 0) {
                    lastDist = d;
                    lastTime = t;
                    break;
                }
            }
            if (lastDist != null && mCurrentTime > lastTime) {
                double extrapolatedDist = lastDist + mEstimatedSpeedMps * (mCurrentTime - lastTime) / 1000.0;
                float x1 = mMarginLeft + graphW * (float) ((lastDist - visMinDist) / visDistRange);
                float y1 = mMarginTop + graphH * (1f - (float) (lastTime - visMinTime) / visTimeRange);
                float x2 = mMarginLeft + graphW * (float) ((extrapolatedDist - visMinDist) / visDistRange);
                float y2 = mMarginTop + graphH * (1f - (float) (mCurrentTime - visMinTime) / visTimeRange);
                canvas.drawLine(x1, y1, x2, y2, mExtrapolatePaint);
                // Vertical drop line to X axis
                float xAxisY = getHeight() - mMarginBottom;
                canvas.drawLine(x2, y2, x2, xAxisY, mExtrapolateDashPaint);
                // Distance label
                String distLabel;
                if (extrapolatedDist >= 1000) {
                    distLabel = String.format(Locale.US, "~%.1fkm", extrapolatedDist / 1000.0);
                } else {
                    distLabel = String.format(Locale.US, "~%.0fm", extrapolatedDist);
                }
                canvas.drawText(distLabel, x2 - 10 * mDensity,
                        xAxisY - 5 * mDensity, mExtrapolateLabelPaint);
            }
        }

        // Draw current time line
        float nowY = mMarginTop + graphH * (1f - (float) (mCurrentTime - visMinTime) / visTimeRange);
        if (nowY >= mMarginTop && nowY <= getHeight() - mMarginBottom) {
            canvas.drawLine(mMarginLeft, nowY, getWidth() - mMarginRight, nowY, mNowLinePaint);
            mReusableDate.setTime(mCurrentTime);
            String nowLabel = "now " + mTimeFmt.format(mReusableDate);
            canvas.drawText(nowLabel, mMarginLeft + 5 * mDensity, nowY - 4 * mDensity,
                    mNowLabelPaint);
        }

        canvas.restore();

        // Legend (outside clip, fixed position)
        float legendX = mMarginLeft + 10 * mDensity;
        float legendY = mMarginTop + 15 * mDensity;
        canvas.drawLine(legendX, legendY, legendX + 20 * mDensity, legendY, mSchedulePaint);
        canvas.drawText("Schedule", legendX + 25 * mDensity, legendY + 4 * mDensity, mLabelPaint);
        legendY += 18 * mDensity;
        canvas.drawLine(legendX, legendY, legendX + 20 * mDensity, legendY, mTrajectoryPaint);
        canvas.drawText("Actual", legendX + 25 * mDensity, legendY + 4 * mDensity, mLabelPaint);
        legendY += 18 * mDensity;
        canvas.drawLine(legendX, legendY, legendX + 20 * mDensity, legendY, mExtrapolateDashPaint);
        canvas.drawText("Estimated", legendX + 25 * mDensity, legendY + 4 * mDensity, mLabelPaint);
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
