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
import org.onebusaway.android.speed.BetaDistribution;
import org.onebusaway.android.speed.CalibrationTracker;
import org.onebusaway.android.speed.DistanceExtrapolator;
import org.onebusaway.android.speed.VehicleHistoryEntry;
import org.onebusaway.android.util.PreferenceUtils;

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
    private double mEstimatedVelVariance;
    private double mScheduleSpeedMps;
    private String mHighlightedStopId;
    private long mModelCoverageMin;
    private long mModelCoverageMax;
    private CalibrationTracker mCalibrationTracker;

    // Scrub state
    private boolean mScrubActive;
    private long mScrubTime;
    private CalibrationTracker.SnapshotRecord mScrubSnapshot;

    // Per-draw coordinate transform state (set at top of onDraw, used by toPixelX/toPixelY)
    private float mGraphW, mGraphH;
    private double mVisMinDist, mVisDistRange;
    private long mVisMinTime, mVisTimeRange;

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
    private final Paint mDeviationDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDeviationLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDeviationLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mConfidenceBandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPdfFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mModelCoveragePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mScrubLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mScrubPdfFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path mSchedulePath = new Path();
    private final Path mTrajectoryPath = new Path();
    private final Path mPdfPath = new Path();

    private static final int PDF_NUM_BINS = 160;
    private final Date mReusableDate = new Date();

    private final float mDensity;
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final boolean mUseImperial;
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
        mUseImperial = !PreferenceUtils.getUnitsAreMetricFromPreferences(context);
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
        if (handleScrubTouch(event)) {
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        mScaleDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);
        if (mScaleX > 1f || mScaleY > 1f || mScaleDetector.isInProgress()) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return true;
    }

    private boolean handleScrubTouch(MotionEvent event) {
        if (mCalibrationTracker == null
                || mModelCoverageMin <= 0 || mModelCoverageMax <= mModelCoverageMin) {
            return false;
        }

        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                float covTop = toPixelY(mModelCoverageMax);
                float covBot = toPixelY(mModelCoverageMin);
                covTop = Math.max(mMarginTop, covTop);
                covBot = Math.min(getHeight() - mMarginBottom, covBot);
                // Touch target: coverage bar region with generous horizontal margin
                if (x <= mMarginLeft + 5 * mDensity && y >= covTop && y <= covBot) {
                    mScrubActive = true;
                    updateScrubTime(y);
                    invalidate();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (mScrubActive) {
                    updateScrubTime(y);
                    invalidate();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mScrubActive) {
                    mScrubActive = false;
                    mScrubSnapshot = null;
                    invalidate();
                    return true;
                }
                return false;
        }
        return false;
    }

    private void updateScrubTime(float pixelY) {
        if (mGraphH <= 0 || mVisTimeRange <= 0) return;
        float fraction = 1f - (pixelY - mMarginTop) / mGraphH;
        long time = mVisMinTime + (long) (fraction * mVisTimeRange);
        mScrubTime = Math.max(mModelCoverageMin, Math.min(mModelCoverageMax, time));
        mScrubSnapshot = mCalibrationTracker.findSnapshotAt(mScrubTime);
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

        mDeviationDotPaint.setColor(Color.parseColor("#FFAA00"));
        mDeviationDotPaint.setStyle(Paint.Style.FILL);

        mDeviationLabelPaint.setColor(Color.parseColor("#FFAA00"));
        mDeviationLabelPaint.setTextSize(11 * mDensity);
        mDeviationLabelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        mDeviationLinePaint.setColor(Color.parseColor("#FFAA00"));
        mDeviationLinePaint.setStyle(Paint.Style.STROKE);
        mDeviationLinePaint.setStrokeWidth(1.5f * mDensity);
        mDeviationLinePaint.setPathEffect(new DashPathEffect(
                new float[]{4 * mDensity, 3 * mDensity}, 0));

        mConfidenceBandPaint.setColor(Color.parseColor("#66BBBBBB"));
        mConfidenceBandPaint.setStyle(Paint.Style.STROKE);
        mConfidenceBandPaint.setStrokeWidth(1f * mDensity);
        mConfidenceBandPaint.setPathEffect(new DashPathEffect(
                new float[]{4 * mDensity, 4 * mDensity}, 0));

        mPdfFillPaint.setColor(Color.parseColor("#40BBBBBB"));
        mPdfFillPaint.setStyle(Paint.Style.FILL);

        mModelCoveragePaint.setColor(Color.parseColor("#3066AAFF"));
        mModelCoveragePaint.setStyle(Paint.Style.FILL);

        mScrubLinePaint.setColor(Color.parseColor("#66AAFF"));
        mScrubLinePaint.setStyle(Paint.Style.STROKE);
        mScrubLinePaint.setStrokeWidth(1.5f * mDensity);

        mScrubPdfFillPaint.setColor(Color.parseColor("#4066AAFF"));
        mScrubPdfFillPaint.setStyle(Paint.Style.FILL);
    }

    public void setHighlightedStopId(String stopId) {
        mHighlightedStopId = stopId;
        invalidate();
    }

    /** Sets the time range covered by model predictions for Y-axis indicator. */
    public void setModelCoverageRange(long minTime, long maxTime) {
        mModelCoverageMin = minTime;
        mModelCoverageMax = maxTime;
    }

    public void setCalibrationTracker(CalibrationTracker tracker) {
        mCalibrationTracker = tracker;
    }

    public void setData(List<VehicleHistoryEntry> history, ObaTripSchedule schedule,
                        long serviceDate, Double estimatedSpeedMps,
                        double estimatedVelVariance, double scheduleSpeedMps) {
        mHistory = history != null ? new ArrayList<>(history) : new ArrayList<>();
        mSchedule = schedule;
        mServiceDate = serviceDate;
        mEstimatedSpeedMps = estimatedSpeedMps != null ? estimatedSpeedMps : 0;
        mEstimatedVelVariance = estimatedVelVariance;
        mScheduleSpeedMps = scheduleSpeedMps;
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

        // Store for toPixelX / toPixelY helpers
        mGraphW = graphW;
        mGraphH = graphH;
        mVisMinDist = visMinDist;
        mVisDistRange = visDistRange;
        mVisMinTime = visMinTime;
        mVisTimeRange = visTimeRange;

        // Draw axes (outside clip)
        canvas.drawLine(mMarginLeft, mMarginTop, mMarginLeft, getHeight() - mMarginBottom,
                mAxisPaint);
        canvas.drawLine(mMarginLeft, getHeight() - mMarginBottom,
                getWidth() - mMarginRight, getHeight() - mMarginBottom, mAxisPaint);

        // Model coverage indicator on Y-axis
        if (mModelCoverageMin > 0 && mModelCoverageMax > mModelCoverageMin) {
            float covTop = toPixelY(mModelCoverageMax);
            float covBot = toPixelY(mModelCoverageMin);
            float barLeft = mMarginLeft - 5 * mDensity;
            float barRight = mMarginLeft;
            // Clamp to graph area
            covTop = Math.max(mMarginTop, Math.min(covTop, getHeight() - mMarginBottom));
            covBot = Math.max(mMarginTop, Math.min(covBot, getHeight() - mMarginBottom));
            if (covBot > covTop) {
                canvas.drawRect(barLeft, covTop, barRight, covBot, mModelCoveragePaint);
                if (mScrubActive) {
                    float scrubDotY = toPixelY(mScrubTime);
                    scrubDotY = Math.max(covTop, Math.min(scrubDotY, covBot));
                    mScrubLinePaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle((barLeft + barRight) / 2f, scrubDotY,
                            4 * mDensity, mScrubLinePaint);
                }
            }
        }

        // Y-axis labels (time) — outside clip
        int timeGridCount = 5;
        long timeStep = visTimeRange / timeGridCount;
        timeStep = Math.max(10_000, (long) niceStep(timeStep));
        long firstTimeTick = ((visMinTime / timeStep) + 1) * timeStep;
        for (long t = firstTimeTick; t < visMaxTime; t += timeStep) {
            float y = toPixelY(t);
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
            float x = toPixelX(d);
            if (x >= mMarginLeft && x <= getWidth() - mMarginRight) {
                String label = formatDist(d);
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
            canvas.drawLine(mMarginLeft, toPixelY(t), getWidth() - mMarginRight,
                    toPixelY(t), mGridPaint);
        }
        for (double d = firstDistTick; d < visMaxDist; d += distStepVis) {
            float x = toPixelX(d);
            canvas.drawLine(x, mMarginTop, x, getHeight() - mMarginBottom, mGridPaint);
        }

        // Draw schedule line
        if (mSchedule != null && mSchedule.getStopTimes() != null && mServiceDate > 0) {
            ObaTripSchedule.StopTime[] stops = mSchedule.getStopTimes();
            if (stops.length > 0) {
                mSchedulePath.reset();
                boolean first = true;
                for (ObaTripSchedule.StopTime st : stops) {
                    float x = toPixelX(st.getDistanceAlongTrip());
                    long absTime = mServiceDate + st.getArrivalTime() * 1000;
                    float y = toPixelY(absTime);
                    if (first) {
                        mSchedulePath.moveTo(x, y);
                        first = false;
                    } else {
                        mSchedulePath.lineTo(x, y);
                    }
                    float dotRadius = (mHighlightedStopId != null
                            && mHighlightedStopId.equals(st.getStopId()))
                            ? 8 * mDensity : 4 * mDensity;
                    canvas.drawCircle(x, y, dotRadius, mScheduleDotPaint);
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
                float x = toPixelX(d);
                float y = toPixelY(t);
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

        VehicleHistoryEntry newestValid = DistanceExtrapolator.findNewestValidEntry(mHistory);
        Double lastDist = newestValid != null ? newestValid.getBestDistanceAlongTrip() : null;
        long lastTime = newestValid != null ? newestValid.getLastLocationUpdateTime() : 0;

        // Draw extrapolation line from last trajectory point to "now"
        if (mEstimatedSpeedMps > 0 && lastDist != null && mCurrentTime > lastTime) {
            Double extrapolated = DistanceExtrapolator.extrapolateDistance(mHistory, mEstimatedSpeedMps, mCurrentTime);
            double extrapolatedDist = extrapolated != null ? extrapolated : lastDist;
            float x1 = toPixelX(lastDist);
            float y1 = toPixelY(lastTime);
            float x2 = toPixelX(extrapolatedDist);
            float y2 = toPixelY(mCurrentTime);
            canvas.drawLine(x1, y1, x2, y2, mExtrapolatePaint);
            // Vertical drop line to X axis
            float xAxisY = getHeight() - mMarginBottom;
            canvas.drawLine(x2, y2, x2, xAxisY, mExtrapolateDashPaint);
            // Distance label
            String distLabel = "~" + formatDistPrecise(extrapolatedDist);
            canvas.drawText(distLabel, x2 - 10 * mDensity,
                    xAxisY - 5 * mDensity, mExtrapolateLabelPaint);

            // Schedule deviation: find scheduled time at extrapolated distance
            long scheduledTime = interpolateScheduleTime(extrapolatedDist);
            if (scheduledTime > 0) {
                float schedY = toPixelY(scheduledTime);
                canvas.drawCircle(x2, schedY, 5 * mDensity, mDeviationDotPaint);
                canvas.drawLine(x2, schedY, x2, y2, mDeviationLinePaint);
                long devSeconds = (mCurrentTime - scheduledTime) / 1000;
                String devLabel;
                long absSeconds = Math.abs(devSeconds);
                if (absSeconds >= 60) {
                    long mins = absSeconds / 60;
                    long secs = absSeconds % 60;
                    devLabel = mins + "m" + (secs > 0 ? secs + "s" : "");
                } else {
                    devLabel = absSeconds + "s";
                }
                if (devSeconds > 0) {
                    devLabel += " late";
                } else if (devSeconds < 0) {
                    devLabel += " early";
                } else {
                    devLabel = "on time";
                }
                float labelX = x2 + 5 * mDensity;
                float labelY = (schedY + y2) / 2 + 4 * mDensity;
                canvas.drawText(devLabel, labelX, labelY, mDeviationLabelPaint);
            }
        }

        // Fit a Beta distribution to the Kalman velocity estimate for CI + PDF
        if (mEstimatedVelVariance > 0 && mEstimatedSpeedMps > 0
                && lastDist != null && mCurrentTime > lastTime) {
            double dtSec = (mCurrentTime - lastTime) / 1000.0;
            BetaDistribution.BetaParams bp = BetaDistribution.fromKalmanEstimate(
                    mEstimatedSpeedMps, mEstimatedVelVariance, mScheduleSpeedMps);

            if (bp != null) {
                double alpha = bp.alpha;
                double beta = bp.beta;
                double posMin = lastDist;
                double posMax = lastDist + bp.vMax * dtSec;

                // PDF max via analytical Beta mode (O(1) for bell-shaped)
                double maxVal;
                if (alpha > 1 && beta > 1) {
                    double tMode = (alpha - 1) / (alpha + beta - 2);
                    maxVal = BetaDistribution.pdf(tMode, alpha, beta);
                } else {
                    maxVal = 0;
                    for (int i = 0; i < PDF_NUM_BINS; i++) {
                        double val = BetaDistribution.pdf((i + 0.5) / PDF_NUM_BINS, alpha, beta);
                        if (val > maxVal) maxVal = val;
                    }
                }

                // Draw PDF
                float xAxisY = getHeight() - mMarginBottom;
                float maxHeightPx = 105 * mDensity;
                double binStep = (posMax - posMin) / PDF_NUM_BINS;

                mPdfPath.reset();
                mPdfPath.moveTo(toPixelX(posMin), xAxisY);
                for (int i = 0; i < PDF_NUM_BINS; i++) {
                    double t = (i + 0.5) / PDF_NUM_BINS;
                    double val = BetaDistribution.pdf(t, alpha, beta);
                    if (maxVal > 0) {
                        double d = posMin + (i + 0.5) * binStep;
                        float h = (float) (val / maxVal * maxHeightPx);
                        mPdfPath.lineTo(toPixelX(d), xAxisY - h);
                    }
                }
                if (maxVal > 0) {
                    mPdfPath.lineTo(toPixelX(posMax), xAxisY);
                    mPdfPath.close();
                    canvas.drawPath(mPdfPath, mPdfFillPaint);
                }

                // Draw 80% confidence band using exact CDF percentiles
                double velP10 = bp.vMax * inverseCdfApprox(0.10, alpha, beta);
                double velP90 = bp.vMax * inverseCdfApprox(0.90, alpha, beta);
                float xStart = toPixelX(lastDist);
                float yStart = toPixelY(lastTime);
                float yNow = toPixelY(mCurrentTime);
                canvas.drawLine(xStart, yStart,
                        toPixelX(lastDist + velP10 * dtSec), yNow, mConfidenceBandPaint);
                canvas.drawLine(xStart, yStart,
                        toPixelX(lastDist + velP90 * dtSec), yNow, mConfidenceBandPaint);
            }
        }

        // Draw scrub line and historical PDF when scrubbing
        if (mScrubActive && mScrubSnapshot != null) {
            CalibrationTracker.SnapshotRecord snap = mScrubSnapshot;
            BetaDistribution.BetaParams sbp = snap.betaParams;
            float scrubY = toPixelY(mScrubTime);
            double sDtSec = (mScrubTime - snap.lastAvlTime) / 1000.0;

            if (sDtSec > 0) {
                double sPosMin = snap.lastDist;
                double sPosMax = snap.lastDist + sbp.vMax * sDtSec;
                double sAlpha = sbp.alpha;
                double sBeta = sbp.beta;

                // PDF max
                double sMaxVal;
                if (sAlpha > 1 && sBeta > 1) {
                    double tMode = (sAlpha - 1) / (sAlpha + sBeta - 2);
                    sMaxVal = BetaDistribution.pdf(tMode, sAlpha, sBeta);
                } else {
                    sMaxVal = 0;
                    for (int i = 0; i < PDF_NUM_BINS; i++) {
                        double val = BetaDistribution.pdf(
                                (i + 0.5) / PDF_NUM_BINS, sAlpha, sBeta);
                        if (val > sMaxVal) sMaxVal = val;
                    }
                }

                // Draw PDF hanging below the scrub line
                if (sMaxVal > 0) {
                    float sMaxH = 80 * mDensity;
                    double sBinStep = (sPosMax - sPosMin) / PDF_NUM_BINS;
                    mPdfPath.reset();
                    mPdfPath.moveTo(toPixelX(sPosMin), scrubY);
                    for (int i = 0; i < PDF_NUM_BINS; i++) {
                        double t = (i + 0.5) / PDF_NUM_BINS;
                        double val = BetaDistribution.pdf(t, sAlpha, sBeta);
                        double d = sPosMin + (i + 0.5) * sBinStep;
                        float h = (float) (val / sMaxVal * sMaxH);
                        mPdfPath.lineTo(toPixelX(d), scrubY + h);
                    }
                    mPdfPath.lineTo(toPixelX(sPosMax), scrubY);
                    mPdfPath.close();
                    canvas.drawPath(mPdfPath, mScrubPdfFillPaint);
                }

                // Draw CI lines from snapshot origin to scrub time
                double sVelP10 = sbp.vMax * inverseCdfApprox(0.10, sAlpha, sBeta);
                double sVelP90 = sbp.vMax * inverseCdfApprox(0.90, sAlpha, sBeta);
                float sxStart = toPixelX(snap.lastDist);
                float syStart = toPixelY(snap.lastAvlTime);
                mScrubLinePaint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(sxStart, syStart,
                        toPixelX(snap.lastDist + sVelP10 * sDtSec), scrubY, mScrubLinePaint);
                canvas.drawLine(sxStart, syStart,
                        toPixelX(snap.lastDist + sVelP90 * sDtSec), scrubY, mScrubLinePaint);
            }

            // Scrub line across graph
            canvas.drawLine(mMarginLeft, scrubY,
                    getWidth() - mMarginRight, scrubY, mScrubLinePaint);

            // Time label
            mReusableDate.setTime(mScrubTime);
            String scrubLabel = mTimeFmt.format(mReusableDate);
            canvas.drawText(scrubLabel, mMarginLeft + 5 * mDensity,
                    scrubY - 4 * mDensity, mScrubLinePaint);
        }

        // Draw current time line
        float nowY = toPixelY(mCurrentTime);
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
        legendY += 18 * mDensity;
        canvas.drawLine(legendX, legendY, legendX + 20 * mDensity, legendY, mConfidenceBandPaint);
        canvas.drawText("80% CI", legendX + 25 * mDensity, legendY + 4 * mDensity, mLabelPaint);
        legendY += 18 * mDensity;
        canvas.drawRect(legendX, legendY - 5 * mDensity, legendX + 20 * mDensity,
                legendY + 5 * mDensity, mPdfFillPaint);
        canvas.drawText("Position PDF", legendX + 25 * mDensity, legendY + 4 * mDensity, mLabelPaint);
        if (mModelCoverageMin > 0 && mModelCoverageMax > mModelCoverageMin) {
            legendY += 18 * mDensity;
            canvas.drawRect(legendX, legendY - 5 * mDensity, legendX + 20 * mDensity,
                    legendY + 5 * mDensity, mModelCoveragePaint);
            canvas.drawText("Model coverage", legendX + 25 * mDensity,
                    legendY + 4 * mDensity, mLabelPaint);
        }
    }

    /** Converts a distance-along-trip value to pixel X coordinate. */
    private float toPixelX(double dist) {
        return mMarginLeft + mGraphW * (float) ((dist - mVisMinDist) / mVisDistRange);
    }

    /** Converts a timestamp to pixel Y coordinate. */
    private float toPixelY(long time) {
        return mMarginTop + mGraphH * (1f - (float) (time - mVisMinTime) / mVisTimeRange);
    }

    /**
     * Interpolates the schedule to find the expected time at a given distance along the trip.
     * Returns 0 if the schedule is unavailable or the distance is out of range.
     */
    private long interpolateScheduleTime(double distanceMeters) {
        if (mSchedule == null) return 0;
        ObaTripSchedule.StopTime[] stops = mSchedule.getStopTimes();
        if (stops == null || stops.length < 2) return 0;

        for (int i = 1; i < stops.length; i++) {
            double d0 = stops[i - 1].getDistanceAlongTrip();
            double d1 = stops[i].getDistanceAlongTrip();
            if (distanceMeters >= d0 && distanceMeters <= d1 && d1 > d0) {
                double fraction = (distanceMeters - d0) / (d1 - d0);
                long t0 = mServiceDate + stops[i - 1].getArrivalTime() * 1000L;
                long t1 = mServiceDate + stops[i].getArrivalTime() * 1000L;
                return t0 + (long) (fraction * (t1 - t0));
            }
        }
        return 0;
    }

    private static final double METERS_PER_FOOT = 0.3048;
    private static final double FEET_PER_MILE = 5280;

    private String formatDist(double meters) {
        if (mUseImperial) {
            double feet = meters / METERS_PER_FOOT;
            if (feet >= FEET_PER_MILE) {
                return String.format(Locale.US, "%.1fmi", feet / FEET_PER_MILE);
            }
            return String.format(Locale.US, "%.0fft", feet);
        } else {
            if (meters >= 1000) {
                return String.format(Locale.US, "%.1fkm", meters / 1000.0);
            }
            return String.format(Locale.US, "%.0fm", meters);
        }
    }

    private String formatDistPrecise(double meters) {
        if (mUseImperial) {
            double feet = meters / METERS_PER_FOOT;
            return String.format(Locale.US, "%.0fft", feet);
        } else {
            return String.format(Locale.US, "%.0fm", meters);
        }
    }

    /** Approximate inverse CDF via bisection on BetaDistribution.cdf(). */
    private static double inverseCdfApprox(double p, double alpha, double beta) {
        double lo = 0, hi = 1;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) / 2;
            if (BetaDistribution.cdf(mid, alpha, beta) < p) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
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
