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
import org.onebusaway.android.speed.HistorySpeedEstimator;
import org.onebusaway.android.speed.ScheduleSpeedEstimator;
import org.onebusaway.android.speed.SpeedEstimator;
import org.onebusaway.android.speed.VehicleHistoryEntry;
import org.onebusaway.android.speed.VehicleSpeedTracker;
import org.onebusaway.android.speed.VehicleState;
import org.onebusaway.android.speed.WeightedSpeedEstimator;

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

    private VehicleSpeedTracker tracker;

    @Before
    public void setUp() {
        tracker = VehicleSpeedTracker.getInstance();
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

    // --- VehicleSpeedTracker tests ---

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
                                        VehicleSpeedTracker tracker) {
                return 42.0;
            }
        });

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        Double speed = tracker.getEstimatedSpeed("trip1", state);
        assertNotNull(speed);
        assertEquals(42.0, speed, 0.01);

        // Restore default
        tracker.setEstimator(new WeightedSpeedEstimator());
    }

    @Test
    public void testTrackerSetEstimatorIgnoresNull() {
        // Should not throw or change current estimator
        tracker.setEstimator(null);
    }

    // --- VehicleSpeedTracker schedule cache tests ---

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

    // --- HistorySpeedEstimator tests ---

    @Test
    public void testHistoryEstimatorInsufficientData() {
        HistorySpeedEstimator estimator = new HistorySpeedEstimator();

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);

        // Only one entry, need at least two
        tracker.recordState("trip1", state);
        assertNull(estimator.estimateSpeed("v1", state, tracker));
    }

    @Test
    public void testHistoryEstimatorWithDistanceAlongTrip() {
        HistorySpeedEstimator estimator = new HistorySpeedEstimator();

        // Vehicle moved 100m in 10 seconds = 10 m/s
        VehicleState state1 = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        VehicleState state2 = createState("v1", "trip1", 47.001, -122.0,
                200.0, 200.0, 5000.0, 11000L);

        tracker.recordState("trip1", state1);
        tracker.recordState("trip1", state2);

        Double speed = estimator.estimateSpeed("v1", state2, tracker);
        assertNotNull(speed);
        assertEquals(10.0, speed, 0.01);
    }

    @Test
    public void testHistoryEstimatorFallsBackToGeographic() {
        HistorySpeedEstimator estimator = new HistorySpeedEstimator();

        // Entries with no distanceAlongTrip - should fall back to geographic distance
        VehicleState state1 = createState("v1", "trip1", 47.0, -122.0,
                null, null, null, 1000L);
        VehicleState state2 = createState("v1", "trip1", 47.001, -122.0,
                null, null, null, 11000L);

        tracker.recordState("trip1", state1);
        tracker.recordState("trip1", state2);

        Double speed = estimator.estimateSpeed("v1", state2, tracker);
        assertNotNull(speed);
        assertTrue(speed > 0);
    }

    @Test
    public void testHistoryEstimatorTimeDeltaTooSmall() {
        HistorySpeedEstimator estimator = new HistorySpeedEstimator();

        // Two entries less than 1 second apart
        VehicleState state1 = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        VehicleState state2 = createState("v1", "trip1", 47.001, -122.0,
                200.0, 200.0, 5000.0, 1500L);

        tracker.recordState("trip1", state1);
        tracker.recordState("trip1", state2);

        Double speed = estimator.estimateSpeed("v1", state2, tracker);
        assertNull(speed);
    }

    @Test
    public void testHistoryEstimatorUsesLastTwoEntries() {
        HistorySpeedEstimator estimator = new HistorySpeedEstimator();

        // Three entries: speed should be calculated from last two only
        VehicleState state1 = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        VehicleState state2 = createState("v1", "trip1", 47.001, -122.0,
                200.0, 200.0, 5000.0, 11000L);
        VehicleState state3 = createState("v1", "trip1", 47.002, -122.0,
                500.0, 500.0, 5000.0, 21000L);

        tracker.recordState("trip1", state1);
        tracker.recordState("trip1", state2);
        tracker.recordState("trip1", state3);

        Double speed = estimator.estimateSpeed("v1", state3, tracker);
        assertNotNull(speed);
        // 300m in 10s = 30 m/s (uses entries 2 and 3)
        assertEquals(30.0, speed, 0.01);
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

        // Create a schedule with 3 stops:
        // Stop A: dist=0,    arrival=0,   departure=60
        // Stop B: dist=1000, arrival=120, departure=180
        // Stop C: dist=3000, arrival=300, departure=360
        ObaTripSchedule schedule = createSchedule(
                new double[]{0, 1000, 3000},
                new long[]{0, 120, 300},
                new long[]{60, 180, 360}
        );
        tracker.putSchedule("trip1", schedule);

        // Vehicle at scheduledDistance=500 (between A and B)
        // Segment speed = (1000 - 0) / (120 - 60) = 1000/60 ~= 16.67 m/s
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

        // Vehicle at scheduledDistance=1500 (between B and C)
        // Segment speed = (3000 - 1000) / (300 - 180) = 2000/120 ~= 16.67 m/s
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

        // Vehicle at scheduledDistance=50 (before first stop at 100)
        // Should use first segment: (1000 - 100) / (120 - 60) = 900/60 = 15.0 m/s
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

        // Vehicle at scheduledDistance=3500 (after last stop at 3000)
        // Should use last segment: (3000 - 1000) / (300 - 180) = 2000/120 ~= 16.67 m/s
        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                3500.0, 3500.0, 5000.0, 10000L);

        Double speed = estimator.estimateSpeed("v1", state, tracker);
        assertNotNull(speed);
        assertEquals(2000.0 / 120.0, speed, 0.01);
    }

    @Test
    public void testScheduleEstimatorTooFewStops() {
        ScheduleSpeedEstimator estimator = new ScheduleSpeedEstimator();

        // Schedule with only 1 stop - not enough to compute segment speed
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

        // Same departure and arrival time for adjacent stops
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

    // --- WeightedSpeedEstimator tests ---

    @Test
    public void testWeightedEstimatorHistoryOnly() {
        WeightedSpeedEstimator estimator = new WeightedSpeedEstimator();

        // No schedule cached -> schedule estimator returns null -> history only
        VehicleState state1 = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        VehicleState state2 = createState("v1", "trip1", 47.001, -122.0,
                200.0, 200.0, 5000.0, 11000L);

        tracker.recordState("trip1", state1);
        tracker.recordState("trip1", state2);

        Double speed = estimator.estimateSpeed("v1", state2, tracker);
        assertNotNull(speed);
        assertEquals(10.0, speed, 0.01);
    }

    @Test
    public void testWeightedEstimatorNoData() {
        WeightedSpeedEstimator estimator = new WeightedSpeedEstimator();

        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                null, null, null, 1000L);

        assertNull(estimator.estimateSpeed("v1", state, tracker));
    }

    @Test
    public void testWeightedEstimatorCombinesBothWithSchedule() {
        WeightedSpeedEstimator estimator = new WeightedSpeedEstimator();

        // Cache a schedule: segment speed = 1000/100 = 10 m/s
        ObaTripSchedule schedule = createSchedule(
                new double[]{0, 1000},
                new long[]{0, 200},
                new long[]{100, 300}
        );
        tracker.putSchedule("trip1", schedule);

        // History: 200m in 10s = 20 m/s
        VehicleState state1 = createState("v1", "trip1", 47.0, -122.0,
                100.0, 100.0, 5000.0, 1000L);
        VehicleState state2 = createState("v1", "trip1", 47.001, -122.0,
                300.0, 500.0, 5000.0, 11000L);

        tracker.recordState("trip1", state1);
        tracker.recordState("trip1", state2);

        Double speed = estimator.estimateSpeed("v1", state2, tracker);
        assertNotNull(speed);
        // weighted = 0.3 * 10 + 0.7 * 20 = 3 + 14 = 17
        assertEquals(17.0, speed, 0.01);
    }

    @Test
    public void testWeightedEstimatorScheduleOnly() {
        WeightedSpeedEstimator estimator = new WeightedSpeedEstimator();

        // Cache a schedule: segment speed = 1000/100 = 10 m/s
        ObaTripSchedule schedule = createSchedule(
                new double[]{0, 1000},
                new long[]{0, 200},
                new long[]{100, 300}
        );
        tracker.putSchedule("trip1", schedule);

        // Only one history entry (insufficient for history estimator)
        VehicleState state = createState("v1", "trip1", 47.0, -122.0,
                500.0, 500.0, 5000.0, 10000L);
        tracker.recordState("trip1", state);

        Double speed = estimator.estimateSpeed("v1", state, tracker);
        assertNotNull(speed);
        // Schedule-only fallback: 10.0 m/s
        assertEquals(10.0, speed, 0.01);
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
        Location pos = createLocation(lat, lng);
        return VehicleState.create(vehicleId, activeTripId, pos, pos,
                distanceAlongTrip, scheduledDistance, totalDistance,
                timestamp, timestamp, 0L, true, timestamp);
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
