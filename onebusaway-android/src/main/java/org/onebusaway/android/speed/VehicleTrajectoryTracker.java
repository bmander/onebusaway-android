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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.onebusaway.android.io.ObaApi;
import org.onebusaway.android.io.elements.ObaTrip;
import org.onebusaway.android.io.elements.ObaTripSchedule;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.request.ObaTripDetailsRequest;
import org.onebusaway.android.io.request.ObaTripDetailsResponse;

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
    private static final long POLL_INTERVAL_MS = 30_000;

    /** Conversion factor: meters per second to miles per hour. */
    public static final double MPS_TO_MPH = 2.23694;

    private final AvlRepository repository = AvlRepository.getInstance();
    private final Map<String, ObaTripSchedule> scheduleCache = new HashMap<>();
    private final Map<String, Long> serviceDateCache = new HashMap<>();
    private final Set<String> pendingScheduleFetches = new HashSet<>();
    private SpeedEstimator estimator = new WeightedSpeedEstimator();

    private final Handler mPollHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> mTripSubscribers = new HashMap<>();
    /** Stable token objects per trip for Handler identity matching. */
    private final Map<String, Object> mPollTokens = new HashMap<>();
    /** Last active trip ID returned by the server for each polled trip. */
    private final Map<String, String> mLastActiveTripId = new HashMap<>();

    private VehicleTrajectoryTracker() {
    }

    public static VehicleTrajectoryTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Records a vehicle state snapshot into the history for the given key (activeTripId).
     * Delegates to {@link AvlRepository} for storage.
     */
    public void recordState(String key, VehicleState state) {
        repository.record(key, state, null);
    }

    /**
     * Records a vehicle state snapshot with block ID information.
     * Delegates to {@link AvlRepository} for storage.
     */
    public void recordState(String key, VehicleState state, String blockId) {
        repository.record(key, state, blockId);
    }

    /**
     * Returns a defensive copy of the history for the given key.
     */
    public List<VehicleHistoryEntry> getHistory(String key) {
        return repository.getHistoryForTrip(key);
    }

    /**
     * Returns the number of history entries for the given key, without copying.
     */
    public int getHistorySize(String key) {
        return repository.getHistorySizeForTrip(key);
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
        return getEstimatedSpeed(key, repository.getLastState(key));
    }

    /**
     * Returns the predicted velocity variance from the last speed estimate.
     */
    public synchronized double getEstimatedVelVariance() {
        return estimator.getLastPredictedVelVariance();
    }

    /**
     * Returns the last active trip ID the server reported for a polled trip,
     * or null if no poll response has been received yet.
     */
    public synchronized String getLastActiveTripId(String polledTripId) {
        return mLastActiveTripId.get(polledTripId);
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
                    if (status != null) {
                        synchronized (VehicleTrajectoryTracker.this) {
                            mLastActiveTripId.put(tripId, status.getActiveTripId());
                        }
                        if (status.getActiveTripId() != null) {
                            VehicleState state = VehicleState.fromTripStatus(status);
                            String blockId = null;
                            ObaTrip trip = response.getTrip(status.getActiveTripId());
                            if (trip != null) {
                                blockId = trip.getBlockId();
                            }
                            recordState(status.getActiveTripId(), state, blockId);
                            if (status.getServiceDate() > 0) {
                                putServiceDate(status.getActiveTripId(),
                                        status.getServiceDate());
                            }
                        }
                        Log.d(TAG, "Polled vehicle position for " + tripId
                                + " (active: " + status.getActiveTripId() + ")");
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
        repository.clearAll();
        scheduleCache.clear();
        serviceDateCache.clear();
        pendingScheduleFetches.clear();
        mTripSubscribers.clear();
        mLastActiveTripId.clear();
        mPollTokens.clear();
        mPollHandler.removeCallbacksAndMessages(null);
        estimator.clearState();
    }
}
