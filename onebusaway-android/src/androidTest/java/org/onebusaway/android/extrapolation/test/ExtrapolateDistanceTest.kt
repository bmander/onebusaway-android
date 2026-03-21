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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.onebusaway.android.extrapolation.math.speed.VehicleTrajectoryTracker
import org.onebusaway.android.extrapolation.math.speed.extrapolateDistance
import org.onebusaway.android.io.elements.Occupancy
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.elements.Status

@RunWith(AndroidJUnit4::class)
class ExtrapolateDistanceTest {

    private val baseTime = 1_000_000_000L

    private fun makeStatus(
            lastLocationUpdateTime: Long = baseTime,
            lastKnownDistanceAlongTrip: Double? = 500.0,
            distanceAlongTrip: Double? = 500.0
    ): ObaTripStatus = StubTripStatus(
            lastLocationUpdateTime = lastLocationUpdateTime,
            lastKnownDistAlongTrip = lastKnownDistanceAlongTrip,
            distAlongTrip = distanceAlongTrip
    )

    @Test
    fun happyPath() {
        val status = makeStatus(lastLocationUpdateTime = baseTime, lastKnownDistanceAlongTrip = 500.0)
        // 10 m/s for 5 seconds = 50m beyond 500m
        val result = extrapolateDistance(status, 10.0, baseTime + 5000)
        assertEquals(550.0, result!!, 1e-9)
    }

    @Test
    fun zeroElapsedTimeReturnsLastDistance() {
        val status = makeStatus()
        val result = extrapolateDistance(status, 10.0, baseTime)
        assertEquals(500.0, result!!, 1e-9)
    }

    @Test
    fun nullNewestReturnsNull() {
        assertNull(extrapolateDistance(null, 10.0, baseTime))
    }

    @Test
    fun zeroSpeedReturnsNull() {
        assertNull(extrapolateDistance(makeStatus(), 0.0, baseTime + 5000))
    }

    @Test
    fun negativeSpeedReturnsNull() {
        assertNull(extrapolateDistance(makeStatus(), -5.0, baseTime + 5000))
    }

    @Test
    fun staleDataReturnsNull() {
        val staleTime = baseTime + VehicleTrajectoryTracker.MAX_EXTRAPOLATION_AGE_MS + 1
        assertNull(extrapolateDistance(makeStatus(), 10.0, staleTime))
    }

    @Test
    fun nullDistanceReturnsNull() {
        val status = makeStatus(lastKnownDistanceAlongTrip = null, distanceAlongTrip = null)
        assertNull(extrapolateDistance(status, 10.0, baseTime + 5000))
    }

    @Test
    fun fallsBackToDistanceAlongTrip() {
        // lastKnownDistanceAlongTrip is 0 (treated as absent), distanceAlongTrip is 300
        val status = makeStatus(lastKnownDistanceAlongTrip = 0.0, distanceAlongTrip = 300.0)
        val result = extrapolateDistance(status, 10.0, baseTime + 5000)
        assertEquals(350.0, result!!, 1e-9)
    }
}

/**
 * Minimal ObaTripStatus stub for testing extrapolateDistance.
 */
private class StubTripStatus(
        private val lastLocationUpdateTime: Long = 0L,
        private val lastKnownDistAlongTrip: Double? = null,
        private val distAlongTrip: Double? = null
) : ObaTripStatus {
    override fun getServiceDate(): Long = 0
    override fun isPredicted(): Boolean = true
    override fun getScheduleDeviation(): Long = 0
    override fun getVehicleId(): String? = null
    override fun getClosestStop(): String? = null
    override fun getClosestStopTimeOffset(): Long = 0
    override fun getPosition(): Location? = null
    override fun getActiveTripId(): String? = "trip1"
    override fun getDistanceAlongTrip(): Double? = distAlongTrip
    override fun getScheduledDistanceAlongTrip(): Double? = null
    override fun getTotalDistanceAlongTrip(): Double? = null
    override fun getOrientation(): Double? = null
    override fun getNextStop(): String? = null
    override fun getNextStopTimeOffset(): Long? = null
    override fun getPhase(): String? = null
    override fun getStatus(): Status? = null
    override fun getLastUpdateTime(): Long = lastLocationUpdateTime
    override fun getLastKnownLocation(): Location? = null
    override fun getLastLocationUpdateTime(): Long = lastLocationUpdateTime
    override fun getLastKnownDistanceAlongTrip(): Double? = lastKnownDistAlongTrip
    override fun getLastKnownOrientation(): Double? = null
    override fun getBlockTripSequence(): Int = 0
    override fun getOccupancyStatus(): Occupancy? = null
}
