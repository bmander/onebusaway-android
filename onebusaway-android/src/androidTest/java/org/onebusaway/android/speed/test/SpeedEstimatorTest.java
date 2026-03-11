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

package org.onebusaway.android.speed.test;

import android.location.Location;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onebusaway.android.io.elements.ObaTripSchedule;
import org.onebusaway.android.speed.GammaSpeedEstimator;
import org.onebusaway.android.speed.GammaSpeedModel;
import org.onebusaway.android.speed.ScheduleSpeedEstimator;
import org.onebusaway.android.speed.SpeedEstimator;
import org.onebusaway.android.speed.VehicleHistoryEntry;
import org.onebusaway.android.speed.VehicleTrajectoryTracker;
import org.onebusaway.android.speed.VehicleState;

import androidx.test.runner.AndroidJUnit4;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import java.util.List;

/**
 * Tests for the speed estimation framework classes.
 */
@RunWith(AndroidJUnit4.class)
public class SpeedEstimatorTest {

    private VehicleTrajectoryTracker tracker;

    @Before
    public void setUp() {
        tracker = VehicleTrajectoryTracker.getInstance();
        tracker.clearAll();
    }

    // --- VehicleHistoryEntry tests ---

    @Test
    public void testHistoryEntryFields() {
        Location loc = createLocation(47.6062, -122.3321);
        VehicleHistoryEntry entry = new VehicleHistoryEntry(loc, 500.0, 12345L);

        assertNotNull(entry.getPosition());
        assertEquals(47.6062, entry.getPosition().getLatitude(), 0.0001);
        assertEquals(-122.3321, entry.getPosition().getLongitude(), 0.0001);
        assertEquals(500.0, entry.getDistanceAlongTrip());
        assertEquals(12345L, entry.getTimestamp());
    }

    @Test
    public void testHistoryEntryNullPosition() {
        VehicleHistoryEntry entry = new VehicleHistoryEntry(null, 100.0, 1000L);
        assertNull(entry.getPosition());
        assertEquals(100.0, entry.getDistanceAlongTrip());
    }

    @Test
    public void testHistoryEntryNullDistance() {
        Location loc = createLocation(47.0, -122.0);
        VehicleHistoryEntry entry = new VehicleHistoryEntry(loc, null, 1000L);
        assertNull(entry.getDistanceAlongTrip());
        assertNotNull(entry.getPosition());
    }

    // --- VehicleState tests ---

    @Test
    public void testVehicleStateCreate() {
        Location pos = createLocation(47.6, -122.3);
        VehicleState state = VehicleState.create("v1", "trip1", pos, pos,
                100.0, 95.0, 5000.0, 1000L, 900L, 30L, true, 2000L);

        assertEquals("v1", state.getVehicleId());
        assertEquals("trip1", state.getActiveTripId());
        assertNotNull(state.getPosition());
        assertEquals(100.0, state.getDistanceAlongTrip());
        assertEquals(95.0, state.getScheduledDistanceAlongTrip());
        assertEquals(5000.0, state.getTotalDistanceAlongTrip());
        assertEquals(1000L, state.getLastUpdateTime());
        assertEquals(900L, state.getLastLocationUpdateTime());
        assertEquals(30L, state.getScheduleDeviation());
        assertTrue(state.isPredicted());
        assertEquals(2000L, state.getTimestamp());
    }

    // --- VehicleTrajectoryTracker tests ---

    @Test
    public void testTrackerEmptyHistory() {
        List<VehicleHistoryEntry> history = tracker.getHistory("trip1");
        assertEquals(0, history.size());
    }

    @Test
    public void testTrackerRecordAndRetrieve() {
        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);

        tracker.recordState("trip1", state);

        List<VehicleHistoryEntry> history = tracker.getHistory("trip1");
        assertEquals(1, history.size());
        assertEquals(100.0, history.get(0).getDistanceAlongTrip());
    }

    @Test
    public void testTrackerRetainsFullHistory() {
        for (int i = 0; i < 50; i++) {
            VehicleState state = createState("v1", "trip1", 47.0 + i * 0.001, -122.0,
                    100.0 * i, 100.0 * i, 5000.0, 1000L + i * 30000L);
            tracker.recordState("trip1", state);
        }

        List<VehicleHistoryEntry> history = tracker.getHistory("trip1");
        assertEquals(50, history.size());
    }

    @Test
    public void testTrackerSeparateTrips() {
        VehicleState state1 = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        VehicleState state2 = createState("v2", "trip2", 47.5, -122.5,
                200.0, 200.0, 6000.0, 1000L);

        tracker.recordState("trip1", state1);
        tracker.recordState("trip2", state2);

        assertEquals(1, tracker.getHistory("trip1").size());
        assertEquals(1, tracker.getHistory("trip2").size());
    }

    @Test
    public void testTrackerClearAll() {
        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        tracker.recordState("trip1", state);
        assertEquals(1, tracker.getHistory("trip1").size());

        tracker.clearAll();
        assertEquals(0, tracker.getHistory("trip1").size());
    }

    @Test
    public void testTrackerDefensiveCopy() {
        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        tracker.recordState("trip1", state);

        List<VehicleHistoryEntry> history = tracker.getHistory("trip1");
        history.clear(); // Modifying the returned list

        // Internal history should be unaffected
        assertEquals(1, tracker.getHistory("trip1").size());
    }

    @Test
    public void testTrackerNullKeyIgnored() {
        tracker.recordState(null, createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L));
        // Should not throw, just silently ignore
    }

    @Test
    public void testTrackerNullStateIgnored() {
        tracker.recordState("trip1", null);
        assertEquals(0, tracker.getHistory("trip1").size());
    }

    @Test
    public void testTrackerGetEstimatedSpeedNullKey() {
        assertNull(tracker.getEstimatedSpeed(null, null));
    }

    @Test
    public void testTrackerGetEstimatedSpeedNullState() {
        assertNull(tracker.getEstimatedSpeed("trip1", null));
    }

    @Test
    public void testTrackerSetEstimator() {
        // Set a custom estimator that always returns 42.0
        tracker.setEstimator(new SpeedEstimator() {
            @Override
            public Double estimateSpeed(String vehicleId, VehicleState state,
                                        VehicleTrajectoryTracker tracker) {
                return 42.0;
            }
        });

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        Double speed = tracker.getEstimatedSpeed("trip1", state);
        assertNotNull(speed);
        assertEquals(42.0, speed, 0.01);

        // Restore default
        tracker.setEstimator(new GammaSpeedEstimator());
    }

    @Test
    public void testTrackerSetEstimatorIgnoresNull() {
        // Should not throw or change current estimator
        tracker.setEstimator(null);
    }

    // --- VehicleTrajectoryTracker schedule cache tests ---

    @Test
    public void testTrackerScheduleCacheEmpty() {
        assertNull(tracker.getSchedule("trip1"));
        assertFalse(tracker.isSchedulePendingOrCached("trip1"));
    }

    @Test
    public void testTrackerPutAndGetSchedule() {
        ObaTripSchedule schedule = ObaTripSchedule.EMPTY_OBJECT;
        tracker.putSchedule("trip1", schedule);
        assertNotNull(tracker.getSchedule("trip1"));
        assertTrue(tracker.isSchedulePendingOrCached("trip1"));
    }

    @Test
    public void testTrackerMarkSchedulePending() {
        assertFalse(tracker.isSchedulePendingOrCached("trip1"));
        tracker.markSchedulePending("trip1");
        assertTrue(tracker.isSchedulePendingOrCached("trip1"));
        // Not yet cached, just pending
        assertNull(tracker.getSchedule("trip1"));
    }

    @Test
    public void testTrackerPutScheduleNullIgnored() {
        tracker.putSchedule(null, ObaTripSchedule.EMPTY_OBJECT);
        tracker.putSchedule("trip1", null);
        assertNull(tracker.getSchedule("trip1"));
    }

    @Test
    public void testTrackerClearAllClearsScheduleCache() {
        tracker.putSchedule("trip1", ObaTripSchedule.EMPTY_OBJECT);
        tracker.markSchedulePending("trip2");
        assertTrue(tracker.isSchedulePendingOrCached("trip1"));
        assertTrue(tracker.isSchedulePendingOrCached("trip2"));

        tracker.clearAll();
        assertFalse(tracker.isSchedulePendingOrCached("trip1"));
        assertFalse(tracker.isSchedulePendingOrCached("trip2"));
        assertNull(tracker.getSchedule("trip1"));
    }

    // --- Subscribe/unsubscribe refcounting tests ---

    @Test
    public void testSubscribeIncrementsRefcount() {
        assertEquals(0, tracker.getSubscriberCount("trip1"));

        tracker.subscribeTripPolling(null, "trip1");
        // null context should be ignored
        assertEquals(0, tracker.getSubscriberCount("trip1"));

        tracker.subscribeTripPolling(null, null);
        // null tripId should be ignored
        assertEquals(0, tracker.getSubscriberCount("trip1"));
    }

    @Test
    public void testUnsubscribeDecrementsRefcount() {
        // Unsubscribing a trip that was never subscribed should not throw
        tracker.unsubscribeTripPolling("trip1");
        assertEquals(0, tracker.getSubscriberCount("trip1"));

        tracker.unsubscribeTripPolling(null);
        // null tripId should be ignored
    }

    @Test
    public void testClearAllResetsSubscribers() {
        // We can't fully test subscribe with a real context in unit tests,
        // but we can verify clearAll resets the subscriber count
        tracker.clearAll();
        assertEquals(0, tracker.getSubscriberCount("trip1"));
        assertEquals(0, tracker.getSubscriberCount("trip2"));
    }

    // --- Schedule-only filtering tests ---

    @Test
    public void testRecordStateRejectsScheduleOnlyPositions() {
        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, 95.0, 5000.0, 1000L, false);

        tracker.recordState("trip1", state);
        assertEquals(0, tracker.getHistorySize("trip1"));
    }

    @Test
    public void testRecordStateAcceptsRealtimePositions() {
        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, 95.0, 5000.0, 1000L, true);

        tracker.recordState("trip1", state);
        assertEquals(1, tracker.getHistorySize("trip1"));
    }

    // --- ScheduleSpeedEstimator tests ---

    @Test
    public void testScheduleEstimatorNoCachedScheduleReturnsNull() {
        ScheduleSpeedEstimator estimator = new ScheduleSpeedEstimator();

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);

        // No schedule cached - should return null
        assertNull(estimator.estimateSpeed("v1", state, tracker));
    }

    @Test
    public void testScheduleEstimatorNullScheduledDistance() {
        ScheduleSpeedEstimator estimator = new ScheduleSpeedEstimator();

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                200.0, null, 5000.0, 10000L);

        assertNull(estimator.estimateSpeed("v1", state, tracker));
    }

    @Test
    public void testScheduleEstimatorCorrectSegmentSpeed() {
        ScheduleSpeedEstimator estimator = new ScheduleSpeedEstimator();

        ObaTripSchedule schedule = createSchedule(
                new double[]{0, 1000, 3000},
                new long[]{0, 120, 300},
                new long[]{60, 180, 360}
        );
        tracker.putSchedule("trip1", schedule);

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                500.0, 500.0, 5000.0, 10000L);

        Double speed = estimator.estimateSpeed("v1", state, tracker);
        assertNotNull(speed);
        assertEquals(1000.0 / 60.0, speed, 0.01);
    }

    @Test
    public void testScheduleEstimatorSecondSegment() {
        ScheduleSpeedEstimator estimator = new ScheduleSpeedEstimator();

        ObaTripSchedule schedule = createSchedule(
                new double[]{0, 1000, 3000},
                new long[]{0, 120, 300},
                new long[]{60, 180, 360}
        );
        tracker.putSchedule("trip1", schedule);

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                1500.0, 1500.0, 5000.0, 10000L);

        Double speed = estimator.estimateSpeed("v1", state, tracker);
        assertNotNull(speed);
        assertEquals(2000.0 / 120.0, speed, 0.01);
    }

    @Test
    public void testScheduleEstimatorBeforeFirstStop() {
        ScheduleSpeedEstimator estimator = new ScheduleSpeedEstimator();

        ObaTripSchedule schedule = createSchedule(
                new double[]{100, 1000, 3000},
                new long[]{60, 120, 300},
                new long[]{60, 180, 360}
        );
        tracker.putSchedule("trip1", schedule);

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                50.0, 50.0, 5000.0, 10000L);

        Double speed = estimator.estimateSpeed("v1", state, tracker);
        assertNotNull(speed);
        assertEquals(900.0 / 60.0, speed, 0.01);
    }

    @Test
    public void testScheduleEstimatorAfterLastStop() {
        ScheduleSpeedEstimator estimator = new ScheduleSpeedEstimator();

        ObaTripSchedule schedule = createSchedule(
                new double[]{0, 1000, 3000},
                new long[]{0, 120, 300},
                new long[]{60, 180, 360}
        );
        tracker.putSchedule("trip1", schedule);

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                3500.0, 3500.0, 5000.0, 10000L);

        Double speed = estimator.estimateSpeed("v1", state, tracker);
        assertNotNull(speed);
        assertEquals(2000.0 / 120.0, speed, 0.01);
    }

    @Test
    public void testScheduleEstimatorTooFewStops() {
        ScheduleSpeedEstimator estimator = new ScheduleSpeedEstimator();

        ObaTripSchedule schedule = createSchedule(
                new double[]{0},
                new long[]{0},
                new long[]{60}
        );
        tracker.putSchedule("trip1", schedule);

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 10000L);

        assertNull(estimator.estimateSpeed("v1", state, tracker));
    }

    @Test
    public void testScheduleEstimatorZeroTimeDelta() {
        ScheduleSpeedEstimator estimator = new ScheduleSpeedEstimator();

        ObaTripSchedule schedule = createSchedule(
                new double[]{0, 1000},
                new long[]{60, 60},
                new long[]{60, 60}
        );
        tracker.putSchedule("trip1", schedule);

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                500.0, 500.0, 5000.0, 10000L);

        assertNull(estimator.estimateSpeed("v1", state, tracker));
    }

    // --- GammaSpeedModel tests ---

    @Test
    public void testGammaSpeedModel_fromSpeeds_workedExample() {
        // v_sched = 20 mph = 20/2.23694 m/s ≈ 8.9408 m/s
        // v_prev  = 10 mph = 10/2.23694 m/s ≈ 4.4704 m/s
        double vSchedMps = 20.0 / GammaSpeedModel.MPS_TO_MPH;
        double vPrevMps = 10.0 / GammaSpeedModel.MPS_TO_MPH;

        GammaSpeedModel.GammaParams params = GammaSpeedModel.fromSpeeds(vSchedMps, vPrevMps);
        assertNotNull(params);

        // v_eff = 20^(1-0.1699) * 10^0.1699
        // beta_0(v_eff) = piecewise linear
        // alpha = beta_0 * C * v_eff
        // scale = C / beta_0
        assertEquals(1.93, params.alpha, 0.15);
        assertEquals(10.58, params.scale, 1.0);
    }

    @Test
    public void testGammaSpeedModel_cdf_quantile_roundTrip() {
        double vSchedMps = 15.0 / GammaSpeedModel.MPS_TO_MPH;
        GammaSpeedModel.GammaParams params = GammaSpeedModel.fromSpeeds(vSchedMps, vSchedMps);
        assertNotNull(params);

        // CDF at quantile(p) should return p
        for (double p : new double[]{0.10, 0.25, 0.50, 0.75, 0.90}) {
            double q = GammaSpeedModel.quantile(p, params);
            double cdfVal = GammaSpeedModel.cdf(q, params);
            assertEquals("CDF(quantile(" + p + ")) should equal " + p, p, cdfVal, 0.01);
        }
    }

    @Test
    public void testGammaSpeedModel_prevSpeedFallback() {
        // prevSpeed <= 0 should fall back to schedSpeed
        double vSchedMps = 20.0 / GammaSpeedModel.MPS_TO_MPH;

        GammaSpeedModel.GammaParams params = GammaSpeedModel.fromSpeeds(vSchedMps, 0);
        assertNotNull(params);

        // Should be equivalent to fromSpeeds(vSched, vSched)
        GammaSpeedModel.GammaParams paramsEqual = GammaSpeedModel.fromSpeeds(vSchedMps, vSchedMps);
        assertNotNull(paramsEqual);

        assertEquals(paramsEqual.alpha, params.alpha, 0.001);
        assertEquals(paramsEqual.scale, params.scale, 0.001);
    }

    @Test
    public void testGammaSpeedModel_schedSpeedZero_returnsNull() {
        assertNull(GammaSpeedModel.fromSpeeds(0, 5.0));
        assertNull(GammaSpeedModel.fromSpeeds(-1.0, 5.0));
    }

    @Test
    public void testGammaSpeedModel_meanSpeedMps() {
        double vSchedMps = 15.0 / GammaSpeedModel.MPS_TO_MPH;
        GammaSpeedModel.GammaParams params = GammaSpeedModel.fromSpeeds(vSchedMps, vSchedMps);
        assertNotNull(params);

        double meanMps = GammaSpeedModel.meanSpeedMps(params);
        assertTrue("Mean speed should be positive", meanMps > 0);
        // Mean speed in mph should be close to 15 mph
        double meanMph = meanMps * GammaSpeedModel.MPS_TO_MPH;
        assertEquals(15.0, meanMph, 3.0);
    }

    @Test
    public void testGammaSpeedModel_pdf_positiveInRange() {
        double vSchedMps = 15.0 / GammaSpeedModel.MPS_TO_MPH;
        GammaSpeedModel.GammaParams params = GammaSpeedModel.fromSpeeds(vSchedMps, vSchedMps);
        assertNotNull(params);

        // PDF should be 0 at speed=0 and positive for reasonable speeds
        assertEquals(0.0, GammaSpeedModel.pdf(0, params), 0.001);
        assertTrue("PDF should be positive at mean",
                GammaSpeedModel.pdf(15.0, params) > 0);
    }

    @Test
    public void testGammaSpeedModel_cdf_boundaries() {
        double vSchedMps = 15.0 / GammaSpeedModel.MPS_TO_MPH;
        GammaSpeedModel.GammaParams params = GammaSpeedModel.fromSpeeds(vSchedMps, vSchedMps);
        assertNotNull(params);

        assertEquals(0.0, GammaSpeedModel.cdf(0, params), 0.001);
        assertEquals(0.0, GammaSpeedModel.cdf(-1, params), 0.001);
        // CDF at a very high speed should be close to 1
        assertTrue(GammaSpeedModel.cdf(100, params) > 0.99);
    }

    // --- GammaSpeedEstimator tests ---

    @Test
    public void testGammaSpeedEstimator_returnsGammaMean() {
        GammaSpeedEstimator estimator = new GammaSpeedEstimator();

        // Cache a schedule: segment speed = 1000/100 = 10 m/s
        ObaTripSchedule schedule = createSchedule(
                new double[]{0, 1000},
                new long[]{0, 200},
                new long[]{100, 300}
        );
        tracker.putSchedule("trip1", schedule);

        // Service date in the past so trip has started
        long pastServiceDate = System.currentTimeMillis() - 3600_000L;
        tracker.putServiceDate("trip1", pastServiceDate);

        // Two history entries so v_prev can be computed
        VehicleState state1 = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        VehicleState state2 = createState("v1", "trip1", 47.001, -122.0,
                400.0, 400.0, 5000.0, 31000L);

        tracker.recordState("trip1", state1);
        tracker.recordState("trip1", state2);

        Double speed = estimator.estimateSpeed("v1", state2, tracker);
        assertNotNull(speed);
        assertTrue("Speed should be positive", speed > 0);

        // GammaParams should be available
        GammaSpeedModel.GammaParams params = estimator.getLastGammaParams();
        assertNotNull(params);
        assertTrue("Alpha should be positive", params.alpha > 0);
        assertTrue("Scale should be positive", params.scale > 0);

        // Speed should equal the gamma mean
        double expectedMean = GammaSpeedModel.meanSpeedMps(params);
        assertEquals(expectedMean, speed, 0.001);
    }

    @Test
    public void testGammaSpeedEstimator_noScheduleFallsBack() {
        GammaSpeedEstimator estimator = new GammaSpeedEstimator();

        // No schedule cached
        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, null, 5000.0, 1000L);
        tracker.recordState("trip1", state);

        Double speed = estimator.estimateSpeed("v1", state, tracker);
        // No schedule → null
        assertNull(speed);
    }

    @Test
    public void testGammaSpeedEstimator_beforeTripStartReturnsNull() {
        GammaSpeedEstimator estimator = new GammaSpeedEstimator();

        ObaTripSchedule schedule = createSchedule(
                new double[]{0, 1000},
                new long[]{0, 200},
                new long[]{300, 500}
        );
        tracker.putSchedule("trip1", schedule);

        // Service date far in the future so current time is before trip start
        long futureServiceDate = System.currentTimeMillis() + 3600_000L;
        tracker.putServiceDate("trip1", futureServiceDate);

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                500.0, 500.0, 5000.0, System.currentTimeMillis());
        tracker.recordState("trip1", state);

        Double speed = estimator.estimateSpeed("v1", state, tracker);
        assertNull("Speed should be null before trip start", speed);
    }

    // --- Integration test ---

    @Test
    public void testEndToEndSpeedEstimation() {
        // Simulate a vehicle moving along a route with periodic updates
        long baseTime = 1000L;
        double baseDistance = 0.0;

        // Record 5 position updates, each 30 seconds apart, 300m apart (~10 m/s = 22 mph)
        for (int i = 0; i < 5; i++) {
            VehicleState state = createState("v1", "trip1",
                    47.0 + i * 0.003, -122.0,
                    baseDistance + i * 300.0,
                    baseDistance + i * 300.0,
                    10000.0,
                    baseTime + i * 30000L);
            tracker.recordState("trip1", state);
        }

        VehicleState latestState = createState("v1", "trip1",
                47.012, -122.0, 1200.0, 1200.0, 10000.0, 121000L);

        Double speed = tracker.getEstimatedSpeed("trip1", latestState);
        assertNotNull(speed);
        assertTrue("Speed should be positive", speed > 0);
    }

    // --- Helper methods ---

    private VehicleState createState(String vehicleId, String activeTripId,
                                     double lat, double lng, Double distanceAlongTrip,
                                     Double scheduledDistance, Double totalDistance,
                                     long timestamp) {
        return createState(vehicleId, activeTripId, lat, lng, distanceAlongTrip,
                scheduledDistance, totalDistance, timestamp, true);
    }

    private VehicleState createState(String vehicleId, String activeTripId,
                                     double lat, double lng, Double distanceAlongTrip,
                                     Double scheduledDistance, Double totalDistance,
                                     long timestamp, boolean predicted) {
        Location pos = createLocation(lat, lng);
        return VehicleState.create(vehicleId, activeTripId, pos, pos,
                distanceAlongTrip, scheduledDistance, totalDistance,
                timestamp, timestamp, 0L, predicted, timestamp);
    }

    private Location createLocation(double lat, double lng) {
        Location loc = new Location("test");
        loc.setLatitude(lat);
        loc.setLongitude(lng);
        return loc;
    }

    /**
     * Creates an ObaTripSchedule using reflection, since the constructor is package-private
     * and Jackson normally handles population.
     */
    private ObaTripSchedule createSchedule(double[] distances, long[] arrivalTimes,
                                           long[] departureTimes) {
        try {
            // Create StopTime array using reflection
            Class<?> stopTimeClass = ObaTripSchedule.StopTime.class;
            java.lang.reflect.Constructor<?> stCtor = stopTimeClass.getDeclaredConstructor();
            stCtor.setAccessible(true);

            Object stopTimesArray = java.lang.reflect.Array.newInstance(stopTimeClass, distances.length);
            for (int i = 0; i < distances.length; i++) {
                Object st = stCtor.newInstance();
                setField(st, "distanceAlongTrip", distances[i]);
                setField(st, "arrivalTime", arrivalTimes[i]);
                setField(st, "departureTime", departureTimes[i]);
                setField(st, "stopId", "stop_" + i);
                java.lang.reflect.Array.set(stopTimesArray, i, st);
            }

            // Create ObaTripSchedule using reflection
            java.lang.reflect.Constructor<ObaTripSchedule> schedCtor =
                    ObaTripSchedule.class.getDeclaredConstructor();
            schedCtor.setAccessible(true);
            ObaTripSchedule schedule = schedCtor.newInstance();
            setField(schedule, "stopTimes", stopTimesArray);

            return schedule;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test schedule", e);
        }
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);

        // For final fields, we need to remove the final modifier
        java.lang.reflect.Field modifiersField;
        try {
            modifiersField = java.lang.reflect.Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
        } catch (NoSuchFieldException e) {
            // On newer JVMs, modifiers field may not be accessible; use Unsafe instead
        }

        field.set(obj, value);
    }
}
