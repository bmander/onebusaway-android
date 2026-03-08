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
package org.onebusaway.android.speed;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.onebusaway.android.io.ObaApi;
import org.onebusaway.android.io.elements.ObaTripSchedule;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.request.ObaTripDetailsRequest;
import org.onebusaway.android.io.request.ObaTripDetailsResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Singleton manager that tracks vehicle trajectory history and estimates speed.
 * Thread-safe via synchronized methods.
 *
 * Two ingestion paths:
 * 1. Passive push — external callers (e.g. VehicleOverlay) call recordState() directly.
 * 2. Active polling — UI components subscribe/unsubscribe by trip ID; the tracker
 *    manages its own Handler-based polling loop with reference counting.
 */
public final class VehicleTrajectoryTracker {

    private static final String TAG = "VehicleTrajectoryTracker";
    private static final VehicleTrajectoryTracker INSTANCE = new VehicleTrajectoryTracker();
    private static final int MAX_HISTORY_SIZE = 100;
    private static final long POLL_INTERVAL_MS = 30_000;

    /** Conversion factor: meters per second to miles per hour. */
    public static final double MPS_TO_MPH = 2.23694;

    private final Map<String, List<VehicleHistoryEntry>> historyMap = new HashMap<>();
    private final Map<String, VehicleState> lastStateCache = new HashMap<>();
    private final Map<String, ObaTripSchedule> scheduleCache = new HashMap<>();
    private final Map<String, Long> serviceDateCache = new HashMap<>();
    private final Set<String> pendingScheduleFetches = new HashSet<>();
    private SpeedEstimator estimator = new WeightedSpeedEstimator();

    private final Handler mPollHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> mTripSubscribers = new HashMap<>();
    /** Stable token objects per trip for Handler identity matching. */
    private final Map<String, Object> mPollTokens = new HashMap<>();

    private VehicleTrajectoryTracker() {
    }

    public static VehicleTrajectoryTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Records a vehicle state snapshot into the history for the given key (activeTripId).
     * Deduplicates by lastLocationUpdateTime — only records when a genuinely new AVL
     * report has arrived from the vehicle, filtering out server re-extrapolations.
     */
    public synchronized void recordState(String key, VehicleState state) {
        if (key == null || state == null) {
            return;
        }

        List<VehicleHistoryEntry> history = historyMap.get(key);
        if (history == null) {
            history = new ArrayList<>();
            historyMap.put(key, history);
        }

        // Skip AVL reports with no timestamp
        long locUpdateTime = state.getLastLocationUpdateTime();
        if (locUpdateTime <= 0) {
            return;
        }

        // Skip if lastLocationUpdateTime hasn't advanced — filters out duplicates from
        // multiple callers that may fetch the same or older AVL reports at different times.
        if (!history.isEmpty()) {
            VehicleHistoryEntry last = history.get(history.size() - 1);
            if (locUpdateTime <= last.getLastLocationUpdateTime()) {
                return;
            }
        }

        Location position = state.getLastKnownLocation();
        if (position == null) {
            position = state.getPosition();
        }

        history.add(new VehicleHistoryEntry(
                position,
                state.getDistanceAlongTrip(),
                state.getLastKnownDistanceAlongTrip(),
                locUpdateTime,
                state.getTimestamp()
        ));

        // Cap history size to prevent unbounded growth
        if (history.size() > MAX_HISTORY_SIZE) {
            history.subList(0, history.size() - MAX_HISTORY_SIZE).clear();
        }

        lastStateCache.put(key, state);
    }

    /**
     * Returns a defensive copy of the history for the given key.
     */
    public synchronized List<VehicleHistoryEntry> getHistory(String key) {
        List<VehicleHistoryEntry> history = historyMap.get(key);
        if (history == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(history);
    }

    /**
     * Returns the number of history entries for the given key, without copying.
     */
    public synchronized int getHistorySize(String key) {
        List<VehicleHistoryEntry> history = historyMap.get(key);
        return history == null ? 0 : history.size();
    }

    /**
     * Returns the estimated speed in m/s for the given key and current state.
     */
    public synchronized Double getEstimatedSpeed(String key, VehicleState state) {
        if (key == null || state == null) {
            return null;
        }
        return estimator.estimateSpeed(state.getVehicleId(), state, this);
    }

    /**
     * Returns the estimated speed in m/s for the given key, using the last cached VehicleState.
     */
    public synchronized Double getEstimatedSpeed(String key) {
        return getEstimatedSpeed(key, lastStateCache.get(key));
    }

    /**
     * Sets the active speed estimator.
     */
    public synchronized void setEstimator(SpeedEstimator estimator) {
        if (estimator != null) {
            this.estimator = estimator;
        }
    }

    /**
     * Stores a trip schedule in the cache.
     */
    public synchronized void putSchedule(String tripId, ObaTripSchedule schedule) {
        if (tripId != null && schedule != null) {
            scheduleCache.put(tripId, schedule);
        }
    }

    /**
     * Returns the cached schedule for the given trip, or null if not cached.
     */
    public synchronized ObaTripSchedule getSchedule(String tripId) {
        return scheduleCache.get(tripId);
    }

    /**
     * Stores the service date for a trip.
     */
    public synchronized void putServiceDate(String tripId, long serviceDate) {
        if (tripId != null && serviceDate > 0) {
            serviceDateCache.put(tripId, serviceDate);
        }
    }

    /**
     * Returns the cached service date for the given trip, or null if not cached.
     */
    public synchronized Long getServiceDate(String tripId) {
        return serviceDateCache.get(tripId);
    }

    /**
     * Returns true if a schedule fetch is already pending or the schedule is cached.
     */
    public synchronized boolean isSchedulePendingOrCached(String tripId) {
        return scheduleCache.containsKey(tripId) || pendingScheduleFetches.contains(tripId);
    }

    /**
     * Marks a trip as having a pending schedule fetch.
     */
    public synchronized void markSchedulePending(String tripId) {
        if (tripId != null) {
            pendingScheduleFetches.add(tripId);
        }
    }

    /**
     * Removes a trip from pending fetches (e.g., on failure so it can be retried).
     */
    public synchronized void clearPending(String tripId) {
        pendingScheduleFetches.remove(tripId);
    }

    // --- Active polling infrastructure ---

    /**
     * Subscribes a UI component to active polling for the given trip.
     * When the first subscriber for a trip arrives, polling starts automatically.
     * Multiple subscribers share a single poller via reference counting.
     */
    public synchronized void subscribeTripPolling(Context appContext, String tripId) {
        if (tripId == null || appContext == null) {
            return;
        }
        Integer count = mTripSubscribers.get(tripId);
        int newCount = (count == null ? 0 : count) + 1;
        mTripSubscribers.put(tripId, newCount);

        if (newCount == 1) {
            // First subscriber — create a stable token and start polling
            Object token = new Object();
            mPollTokens.put(tripId, token);
            schedulePoll(appContext, tripId, token, 0);
        }
    }

    /**
     * Unsubscribes a UI component from active polling for the given trip.
     * When the last subscriber leaves, polling stops.
     */
    public synchronized void unsubscribeTripPolling(String tripId) {
        if (tripId == null) {
            return;
        }
        Integer count = mTripSubscribers.get(tripId);
        if (count == null || count <= 1) {
            mTripSubscribers.remove(tripId);
            Object token = mPollTokens.remove(tripId);
            if (token != null) {
                mPollHandler.removeCallbacksAndMessages(token);
            }
        } else {
            mTripSubscribers.put(tripId, count - 1);
        }
    }

    /**
     * Returns the current subscriber count for a trip. Visible for testing.
     */
    public synchronized int getSubscriberCount(String tripId) {
        Integer count = mTripSubscribers.get(tripId);
        return count == null ? 0 : count;
    }

    private void schedulePoll(Context appContext, String tripId, Object token, long delayMs) {
        Runnable pollRunnable = () -> pollTrip(appContext, tripId, token);
        mPollHandler.postDelayed(pollRunnable, token, delayMs);
    }

    private void pollTrip(Context ctx, String tripId, Object token) {
        new Thread(() -> {
            try {
                ObaTripDetailsResponse response =
                        ObaTripDetailsRequest.newRequest(ctx, tripId).call();
                if (response != null && response.getCode() == ObaApi.OBA_OK) {
                    ObaTripStatus status = response.getStatus();
                    if (status != null && status.getActiveTripId() != null) {
                        VehicleState state = VehicleState.fromTripStatus(status);
                        recordState(status.getActiveTripId(), state);
                        if (status.getServiceDate() > 0) {
                            putServiceDate(status.getActiveTripId(), status.getServiceDate());
                        }
                        Log.d(TAG, "Polled vehicle position for " + tripId);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to poll trip details for " + tripId, e);
            }
            // Schedule next poll on main thread, only if still subscribed.
            // Use the same token so unsubscribe can cancel this callback too.
            mPollHandler.postDelayed(() -> {
                synchronized (VehicleTrajectoryTracker.this) {
                    Integer count = mTripSubscribers.get(tripId);
                    if (count != null && count > 0) {
                        schedulePoll(ctx, tripId, token, POLL_INTERVAL_MS);
                    }
                }
            }, token, 0);
        }).start();
    }

    /**
     * Clears all history data, schedule cache, and cancels all active polling.
     */
    public synchronized void clearAll() {
        historyMap.clear();
        lastStateCache.clear();
        scheduleCache.clear();
        serviceDateCache.clear();
        pendingScheduleFetches.clear();
        mTripSubscribers.clear();
        mPollTokens.clear();
        mPollHandler.removeCallbacksAndMessages(null);
    }
}
