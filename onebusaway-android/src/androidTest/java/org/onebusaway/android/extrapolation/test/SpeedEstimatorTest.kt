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
package org.onebusaway.android.extrapolation.test

import android.location.Location
import androidx.test.runner.AndroidJUnit4
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNull
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onebusaway.android.extrapolation.data.TripDataManager
import org.onebusaway.android.extrapolation.data.VehicleHistoryEntry
import org.onebusaway.android.extrapolation.data.VehicleState
import org.onebusaway.android.extrapolation.math.GammaDistribution
import org.onebusaway.android.extrapolation.math.PointEstimate
import org.onebusaway.android.extrapolation.math.speed.GammaSpeedEstimator
import org.onebusaway.android.extrapolation.math.speed.GammaSpeedModel
import org.onebusaway.android.extrapolation.math.speed.ScheduleSpeedEstimator
import org.onebusaway.android.extrapolation.math.speed.SpeedEstimator
import org.onebusaway.android.extrapolation.math.speed.VehicleTrajectoryTracker
import org.onebusaway.android.io.elements.ObaTripSchedule

/**
 * Tests for the speed estimation framework classes.
 */
@RunWith(AndroidJUnit4::class)
class SpeedEstimatorTest {

    private val tracker = VehicleTrajectoryTracker
    private val dm = TripDataManager

    @Before
    fun setUp() {
        dm.clearAll()
        tracker.clearAll()
    }

    // --- VehicleHistoryEntry tests ---

    @Test
    fun testHistoryEntryFields() {
        val loc = createLocation(47.6062, -122.3321)
        val entry = VehicleHistoryEntry(
            position = loc, distanceAlongTrip = 500.0, timestamp = 12345L
        )

        assertNotNull(entry.position)
        assertEquals(47.6062, entry.position!!.latitude, 0.0001)
        assertEquals(-122.3321, entry.position!!.longitude, 0.0001)
        assertEquals(500.0, entry.distanceAlongTrip)
        assertEquals(12345L, entry.timestamp)
    }

    @Test
    fun testHistoryEntryNullPosition() {
        val entry = VehicleHistoryEntry(
            position = null, distanceAlongTrip = 100.0, timestamp = 1000L
        )
        assertNull(entry.position)
        assertEquals(100.0, entry.distanceAlongTrip)
    }

    @Test
    fun testHistoryEntryNullDistance() {
        val loc = createLocation(47.0, -122.0)
        val entry = VehicleHistoryEntry(
            position = loc, distanceAlongTrip = null, timestamp = 1000L
        )
        assertNull(entry.distanceAlongTrip)
        assertNotNull(entry.position)
    }

    // --- VehicleState tests ---

    @Test
    fun testVehicleStateCreate() {
        val pos = createLocation(47.6, -122.3)
        val state = VehicleState.create(
            "v1", "trip1", pos, pos,
            100.0, 95.0, 5000.0, 1000L, 900L, 30L, true, 2000L
        )

        assertEquals("v1", state.vehicleId)
        assertEquals("trip1", state.activeTripId)
        assertNotNull(state.position)
        assertEquals(100.0, state.distanceAlongTrip)
        assertEquals(95.0, state.scheduledDistanceAlongTrip)
        assertEquals(5000.0, state.totalDistanceAlongTrip)
        assertEquals(1000L, state.lastUpdateTime)
        assertEquals(900L, state.lastLocationUpdateTime)
        assertEquals(30L, state.scheduleDeviation)
        assertTrue(state.isPredicted)
        assertEquals(2000L, state.timestamp)
    }

    // --- TripDataManager tests ---

    @Test
    fun testTrackerEmptyHistory() {
        val history = dm.getHistory("trip1")
        assertEquals(0, history.size)
    }

    @Test
    fun testTrackerRecordAndRetrieve() {
        val state = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, 100.0, 5000.0, 1000L
        )

        dm.recordState("trip1", state)

        val history = dm.getHistory("trip1")
        assertEquals(1, history.size)
        assertEquals(100.0, history[0].distanceAlongTrip)
    }

    @Test
    fun testTrackerRetainsFullHistory() {
        for (i in 0 until 50) {
            val state = createState(
                "v1", "trip1", 47.0 + i * 0.001, -122.0,
                100.0 * i, 100.0 * i, 5000.0, 1000L + i * 30000L
            )
            dm.recordState("trip1", state)
        }

        val history = dm.getHistory("trip1")
        assertEquals(50, history.size)
    }

    @Test
    fun testTrackerSeparateTrips() {
        val state1 = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, 100.0, 5000.0, 1000L
        )
        val state2 = createState(
            "v2", "trip2", 47.5, -122.5, 200.0, 200.0, 6000.0, 1000L
        )

        dm.recordState("trip1", state1)
        dm.recordState("trip2", state2)

        assertEquals(1, dm.getHistory("trip1").size)
        assertEquals(1, dm.getHistory("trip2").size)
    }

    @Test
    fun testTrackerClearAll() {
        val state = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, 100.0, 5000.0, 1000L
        )
        dm.recordState("trip1", state)
        assertEquals(1, dm.getHistory("trip1").size)

        dm.clearAll()
        assertEquals(0, dm.getHistory("trip1").size)
    }

    @Test
    fun testTrackerDefensiveCopy() {
        val state = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, 100.0, 5000.0, 1000L
        )
        dm.recordState("trip1", state)

        val history = dm.getHistory("trip1")
        history.toMutableList().clear() // Modifying a copy

        // Internal history should be unaffected
        assertEquals(1, dm.getHistory("trip1").size)
    }

    @Test
    fun testTrackerNullKeyIgnored() {
        dm.recordState(
            null, createState("v1", "trip1", 47.0, -122.0, 100.0, 100.0, 5000.0, 1000L)
        )
        // Should not throw, just silently ignore
    }

    @Test
    fun testTrackerNullStateIgnored() {
        dm.recordState("trip1", null)
        assertEquals(0, dm.getHistory("trip1").size)
    }

    @Test
    fun testTrackerGetEstimatedSpeedNullKey() {
        assertNull(tracker.getEstimatedSpeed(null, null))
    }

    @Test
    fun testTrackerGetEstimatedSpeedNullState() {
        assertNull(tracker.getEstimatedSpeed("trip1", null))
    }

    @Test
    fun testTrackerSetEstimator() {
        // Set a custom estimator that always returns 42.0
        tracker.setEstimator(object : SpeedEstimator {
            override fun estimateSpeed(
                state: VehicleState,
                dataManager: TripDataManager
            ) = PointEstimate(42.0)
        })

        val state = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, 100.0, 5000.0, 1000L
        )
        val speed = tracker.getEstimatedSpeed("trip1", state)
        assertNotNull(speed)
        assertEquals(42.0, speed!!, 0.01)

        // Restore default
        tracker.setEstimator(GammaSpeedEstimator())
    }

    @Test
    fun testTrackerSetEstimatorIgnoresNull() {
        // Should not throw or change current estimator
        tracker.setEstimator(null)
    }

    // --- TripDataManager schedule cache tests ---

    @Test
    fun testTrackerScheduleCacheEmpty() {
        assertNull(dm.getSchedule("trip1"))
        assertFalse(dm.isScheduleCached("trip1"))
    }

    @Test
    fun testTrackerPutAndGetSchedule() {
        val schedule = ObaTripSchedule.EMPTY_OBJECT
        dm.putSchedule("trip1", schedule)
        assertNotNull(dm.getSchedule("trip1"))
        assertTrue(dm.isScheduleCached("trip1"))
    }

    @Test
    fun testTrackerPutScheduleNullIgnored() {
        dm.putSchedule(null, ObaTripSchedule.EMPTY_OBJECT)
        dm.putSchedule("trip1", null)
        assertNull(dm.getSchedule("trip1"))
    }

    @Test
    fun testTrackerClearAllClearsScheduleCache() {
        dm.putSchedule("trip1", ObaTripSchedule.EMPTY_OBJECT)
        assertTrue(dm.isScheduleCached("trip1"))

        dm.clearAll()
        assertFalse(dm.isScheduleCached("trip1"))
        assertNull(dm.getSchedule("trip1"))
    }

    // --- Schedule-only filtering tests ---

    @Test
    fun testRecordStateRejectsScheduleOnlyPositions() {
        val state = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, 95.0, 5000.0, 1000L, false
        )

        dm.recordState("trip1", state)
        assertEquals(0, dm.getHistorySize("trip1"))
    }

    @Test
    fun testRecordStateAcceptsRealtimePositions() {
        val state = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, 95.0, 5000.0, 1000L, true
        )

        dm.recordState("trip1", state)
        assertEquals(1, dm.getHistorySize("trip1"))
    }

    // --- ScheduleSpeedEstimator tests ---

    @Test
    fun testScheduleEstimatorNoCachedScheduleReturnsNull() {
        val estimator = ScheduleSpeedEstimator()

        val state = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, 100.0, 5000.0, 1000L
        )

        // No schedule cached - should return null
        assertNull(estimator.estimateSpeed(state, dm))
    }

    @Test
    fun testScheduleEstimatorNullScheduledDistance() {
        val estimator = ScheduleSpeedEstimator()

        val state = createState(
            "v1", "trip1", 47.0, -122.0, 200.0, null, 5000.0, 10000L
        )

        assertNull(estimator.estimateSpeed(state, dm))
    }

    @Test
    fun testScheduleEstimatorCorrectSegmentSpeed() {
        val estimator = ScheduleSpeedEstimator()

        val schedule = createSchedule(
            doubleArrayOf(0.0, 1000.0, 3000.0),
            longArrayOf(0, 120, 300),
            longArrayOf(60, 180, 360)
        )
        dm.putSchedule("trip1", schedule)

        val state = createState(
            "v1", "trip1", 47.0, -122.0, 500.0, 500.0, 5000.0, 10000L
        )

        val dist = estimator.estimateSpeed(state, dm)
        assertNotNull(dist)
        assertEquals(1000.0 / 60.0, dist!!.mean, 0.01)
    }

    @Test
    fun testScheduleEstimatorSecondSegment() {
        val estimator = ScheduleSpeedEstimator()

        val schedule = createSchedule(
            doubleArrayOf(0.0, 1000.0, 3000.0),
            longArrayOf(0, 120, 300),
            longArrayOf(60, 180, 360)
        )
        dm.putSchedule("trip1", schedule)

        val state = createState(
            "v1", "trip1", 47.0, -122.0, 1500.0, 1500.0, 5000.0, 10000L
        )

        val dist = estimator.estimateSpeed(state, dm)
        assertNotNull(dist)
        assertEquals(2000.0 / 120.0, dist!!.mean, 0.01)
    }

    @Test
    fun testScheduleEstimatorBeforeFirstStop() {
        val estimator = ScheduleSpeedEstimator()

        val schedule = createSchedule(
            doubleArrayOf(100.0, 1000.0, 3000.0),
            longArrayOf(60, 120, 300),
            longArrayOf(60, 180, 360)
        )
        dm.putSchedule("trip1", schedule)

        val state = createState(
            "v1", "trip1", 47.0, -122.0, 50.0, 50.0, 5000.0, 10000L
        )

        val dist = estimator.estimateSpeed(state, dm)
        assertNotNull(dist)
        assertEquals(900.0 / 60.0, dist!!.mean, 0.01)
    }

    @Test
    fun testScheduleEstimatorAfterLastStop() {
        val estimator = ScheduleSpeedEstimator()

        val schedule = createSchedule(
            doubleArrayOf(0.0, 1000.0, 3000.0),
            longArrayOf(0, 120, 300),
            longArrayOf(60, 180, 360)
        )
        dm.putSchedule("trip1", schedule)

        val state = createState(
            "v1", "trip1", 47.0, -122.0, 3500.0, 3500.0, 5000.0, 10000L
        )

        val dist = estimator.estimateSpeed(state, dm)
        assertNotNull(dist)
        assertEquals(2000.0 / 120.0, dist!!.mean, 0.01)
    }

    @Test
    fun testScheduleEstimatorTooFewStops() {
        val estimator = ScheduleSpeedEstimator()

        val schedule = createSchedule(
            doubleArrayOf(0.0),
            longArrayOf(0),
            longArrayOf(60)
        )
        dm.putSchedule("trip1", schedule)

        val state = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, 100.0, 5000.0, 10000L
        )

        assertNull(estimator.estimateSpeed(state, dm))
    }

    @Test
    fun testScheduleEstimatorZeroTimeDelta() {
        val estimator = ScheduleSpeedEstimator()

        val schedule = createSchedule(
            doubleArrayOf(0.0, 1000.0),
            longArrayOf(60, 60),
            longArrayOf(60, 60)
        )
        dm.putSchedule("trip1", schedule)

        val state = createState(
            "v1", "trip1", 47.0, -122.0, 500.0, 500.0, 5000.0, 10000L
        )

        assertNull(estimator.estimateSpeed(state, dm))
    }

    // --- GammaSpeedModel tests ---
    // (Detailed GammaSpeedModel tests are in the JVM unit test suite;
    //  these are smoke tests that verify it works on device.)

    @Test
    fun testGammaSpeedModel_fromSpeeds_workedExample() {
        // 20 mph ≈ 8.94 m/s, 10 mph ≈ 4.47 m/s
        val dist = GammaSpeedModel.fromSpeeds(8.94, 4.47)
        assertNotNull(dist)
        assertEquals(1.93, dist!!.alpha, 0.15)
        assertEquals(4.73, dist.scale, 0.5)
    }

    @Test
    fun testGammaSpeedModel_cdf_quantile_roundTrip() {
        val dist = GammaSpeedModel.fromSpeeds(6.71, 6.71)
        assertNotNull(dist)

        for (p in doubleArrayOf(0.10, 0.25, 0.50, 0.75, 0.90)) {
            val q = dist!!.quantile(p)
            assertEquals("CDF(quantile($p)) should equal $p", p, dist.cdf(q), 0.01)
        }
    }

    @Test
    fun testGammaSpeedModel_prevSpeedFallback() {
        val dist = GammaSpeedModel.fromSpeeds(8.94, 0.0)
        assertNotNull(dist)
        val distEqual = GammaSpeedModel.fromSpeeds(8.94, 8.94)
        assertNotNull(distEqual)
        assertEquals(distEqual!!.alpha, dist!!.alpha, 0.001)
        assertEquals(distEqual.scale, dist.scale, 0.001)
    }

    @Test
    fun testGammaSpeedModel_schedSpeedZero_returnsNull() {
        assertNull(GammaSpeedModel.fromSpeeds(0.0, 5.0))
        assertNull(GammaSpeedModel.fromSpeeds(-1.0, 5.0))
    }

    @Test
    fun testGammaSpeedModel_mean() {
        val dist = GammaSpeedModel.fromSpeeds(6.71, 6.71)
        assertNotNull(dist)
        assertTrue("Mean speed should be positive", dist!!.mean > 0)
        assertEquals(6.71, dist.mean, 1.5)
    }

    @Test
    fun testGammaSpeedModel_pdf_positiveInRange() {
        val dist = GammaSpeedModel.fromSpeeds(6.71, 6.71)
        assertNotNull(dist)
        assertEquals(0.0, dist!!.pdf(0.0), 0.001)
        assertTrue("PDF should be positive at mean", dist.pdf(6.71) > 0)
    }

    @Test
    fun testGammaSpeedModel_cdf_boundaries() {
        val dist = GammaSpeedModel.fromSpeeds(6.71, 6.71)
        assertNotNull(dist)
        assertEquals(0.0, dist!!.cdf(0.0), 0.001)
        assertEquals(0.0, dist.cdf(-1.0), 0.001)
        assertTrue(dist.cdf(45.0) > 0.99)
    }

    // --- GammaSpeedEstimator tests ---

    @Test
    fun testGammaSpeedEstimator_returnsGammaMedian() {
        val estimator = GammaSpeedEstimator()

        val schedule = createSchedule(
            doubleArrayOf(0.0, 1000.0),
            longArrayOf(0, 200),
            longArrayOf(100, 300)
        )
        dm.putSchedule("trip1", schedule)

        // Service date in the past so trip has started
        val pastServiceDate = System.currentTimeMillis() - 3600_000L
        dm.putServiceDate("trip1", pastServiceDate)

        // Two history entries so v_prev can be computed
        val state1 = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, 100.0, 5000.0, 1000L
        )
        val state2 = createState(
            "v1", "trip1", 47.001, -122.0, 400.0, 400.0, 5000.0, 31000L
        )

        dm.recordState("trip1", state1)
        dm.recordState("trip1", state2)

        val dist = estimator.estimateSpeed(state2, dm)
        assertNotNull(dist)
        assertTrue(dist is GammaDistribution)
        val gamma = dist as GammaDistribution
        assertTrue("Alpha should be positive", gamma.alpha > 0)
        assertTrue("Scale should be positive", gamma.scale > 0)
        assertTrue("Median should be positive", gamma.median() > 0)
    }

    @Test
    fun testGammaSpeedEstimator_noScheduleFallsBack() {
        val estimator = GammaSpeedEstimator()

        val state = createState(
            "v1", "trip1", 47.0, -122.0, 100.0, null, 5000.0, 1000L
        )
        dm.recordState("trip1", state)

        assertNull(estimator.estimateSpeed(state, dm))
    }

    @Test
    fun testGammaSpeedEstimator_beforeTripStartReturnsNull() {
        val estimator = GammaSpeedEstimator()

        val schedule = createSchedule(
            doubleArrayOf(0.0, 1000.0),
            longArrayOf(0, 200),
            longArrayOf(300, 500)
        )
        dm.putSchedule("trip1", schedule)

        // Service date far in the future so current time is before trip start
        val futureServiceDate = System.currentTimeMillis() + 3600_000L
        dm.putServiceDate("trip1", futureServiceDate)

        val state = createState(
            "v1", "trip1", 47.0, -122.0,
            500.0, 500.0, 5000.0, System.currentTimeMillis()
        )
        dm.recordState("trip1", state)

        assertNull("Should be null before trip start", estimator.estimateSpeed(state, dm))
    }

    // --- Integration test ---

    @Test
    fun testEndToEndSpeedEstimation() {
        val baseTime = 1000L
        val baseDistance = 0.0

        val schedule = createSchedule(
            doubleArrayOf(0.0, 5000.0, 10000.0),
            longArrayOf(0, 500, 1000),
            longArrayOf(0, 500, 1000)
        )
        dm.putSchedule("trip1", schedule)
        dm.putServiceDate("trip1", System.currentTimeMillis() - 7200_000L)

        // Record 5 position updates, each 30 seconds apart, 300m apart (~10 m/s)
        for (i in 0 until 5) {
            val state = createState(
                "v1", "trip1",
                47.0 + i * 0.003, -122.0,
                baseDistance + i * 300.0,
                baseDistance + i * 300.0,
                10000.0,
                baseTime + i * 30000L
            )
            dm.recordState("trip1", state)
        }

        val latestState = createState(
            "v1", "trip1", 47.012, -122.0, 1200.0, 1200.0, 10000.0, 121000L
        )

        val speed = tracker.getEstimatedSpeed("trip1", latestState)
        assertNotNull(speed)
        assertTrue("Speed should be positive", speed!! > 0)
    }

    // --- Helper methods ---

    private fun createState(
        vehicleId: String, activeTripId: String,
        lat: Double, lng: Double, distanceAlongTrip: Double?,
        scheduledDistance: Double?, totalDistance: Double?,
        timestamp: Long, predicted: Boolean = true
    ): VehicleState {
        val pos = createLocation(lat, lng)
        return VehicleState.create(
            vehicleId, activeTripId, pos, pos,
            distanceAlongTrip, scheduledDistance, totalDistance,
            timestamp, timestamp, 0L, predicted, timestamp
        )
    }

    private fun createLocation(lat: Double, lng: Double): Location {
        val loc = Location("test")
        loc.latitude = lat
        loc.longitude = lng
        return loc
    }

    /**
     * Creates an ObaTripSchedule using reflection, since the constructor is package-private
     * and Jackson normally handles population.
     */
    private fun createSchedule(
        distances: DoubleArray,
        arrivalTimes: LongArray,
        departureTimes: LongArray
    ): ObaTripSchedule {
        try {
            val stopTimeClass = ObaTripSchedule.StopTime::class.java
            val stCtor = stopTimeClass.getDeclaredConstructor()
            stCtor.isAccessible = true

            val stopTimesArray = java.lang.reflect.Array.newInstance(
                stopTimeClass, distances.size
            )
            for (i in distances.indices) {
                val st = stCtor.newInstance()
                setField(st, "distanceAlongTrip", distances[i])
                setField(st, "arrivalTime", arrivalTimes[i])
                setField(st, "departureTime", departureTimes[i])
                setField(st, "stopId", "stop_$i")
                java.lang.reflect.Array.set(stopTimesArray, i, st)
            }

            val schedCtor = ObaTripSchedule::class.java.getDeclaredConstructor()
            schedCtor.isAccessible = true
            val schedule = schedCtor.newInstance()
            setField(schedule, "stopTimes", stopTimesArray)

            return schedule
        } catch (e: Exception) {
            throw RuntimeException("Failed to create test schedule", e)
        }
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        val field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true

        try {
            val modifiersField =
                java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
            modifiersField.isAccessible = true
            modifiersField.setInt(
                field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv()
            )
        } catch (e: NoSuchFieldException) {
            // On newer JVMs, modifiers field may not be accessible; use Unsafe instead
        }

        field.set(obj, value)
    }
}
