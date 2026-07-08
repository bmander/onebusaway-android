/*
 * Copyright (C) 2026 Open Transit Software Foundation
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
package org.onebusaway.android.directions.realtime

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onebusaway.android.directions.model.ItineraryDescription
import org.opentripplanner.api.model.Itinerary
import org.opentripplanner.api.model.Leg
import java.util.concurrent.TimeUnit

/**
 * JVM unit tests for [TripMonitorDecider] — the itinerary-diff decision extracted from the legacy
 * `RealtimeService.checkForItineraryChange`.
 */
class TripMonitorDeciderTest {

    private val thresholdSeconds = TimeUnit.MINUTES.toSeconds(2)

    /** Builds a single-transit-leg itinerary with the given trip id and end time (epoch millis). */
    private fun itinerary(tripId: String, endTimeMillis: Long): Itinerary {
        val leg = Leg().apply {
            mode = "BUS"
            realTime = true
            this.tripId = tripId
            endTime = endTimeMillis.toString()
        }
        return Itinerary().apply { legs = arrayListOf(leg) }
    }

    /** An [ItineraryDescription] for a trip that ends [minutesFromNow] minutes out. */
    private fun monitoring(tripId: String, minutesFromNow: Long): ItineraryDescription {
        val end = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutesFromNow)
        return ItineraryDescription(itinerary(tripId, end))
    }

    @Test
    fun emptyResults_stops() {
        val result = TripMonitorDecider.decide(monitoring("t1", 30), emptyList(), thresholdSeconds)
        assertEquals(MonitorResult.Stop, result)
    }

    @Test
    fun matchingItinerary_withinThreshold_keepsMonitoring() {
        val current = monitoring("t1", 30)
        val end = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30) +
            TimeUnit.SECONDS.toMillis(30) // 30s later — under the 2 min threshold
        val result = TripMonitorDecider.decide(
            current, listOf(itinerary("t1", end)), thresholdSeconds
        )
        assertEquals(MonitorResult.KeepMonitoring, result)
    }

    @Test
    fun matchingItinerary_delayedBeyondThreshold_reportsPositiveDeviation() {
        val currentEnd = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30)
        val current = ItineraryDescription(itinerary("t1", currentEnd))
        val newEnd = currentEnd + TimeUnit.MINUTES.toMillis(5) // 5 min late
        val result = TripMonitorDecider.decide(
            current, listOf(itinerary("t1", newEnd)), thresholdSeconds
        )
        assertEquals(MonitorResult.Deviation(TimeUnit.MINUTES.toSeconds(5)), result)
    }

    @Test
    fun matchingItinerary_earlyBeyondThreshold_reportsNegativeDeviation() {
        val currentEnd = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30)
        val current = ItineraryDescription(itinerary("t1", currentEnd))
        val newEnd = currentEnd - TimeUnit.MINUTES.toMillis(5) // 5 min early
        val result = TripMonitorDecider.decide(
            current, listOf(itinerary("t1", newEnd)), thresholdSeconds
        )
        assertEquals(MonitorResult.Deviation(-TimeUnit.MINUTES.toSeconds(5)), result)
    }

    @Test
    fun noMatchingItinerary_reportsChanged() {
        val current = monitoring("t1", 30)
        val result = TripMonitorDecider.decide(
            current, listOf(itinerary("t2", System.currentTimeMillis())), thresholdSeconds
        )
        assertEquals(MonitorResult.ItineraryChanged, result)
    }

    @Test
    fun matchingItinerary_alreadyExpired_stops() {
        // Trip ended a minute ago; a still-matching result should stop monitoring, not keep polling.
        val current = monitoring("t1", -1)
        val end = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)
        val result = TripMonitorDecider.decide(
            current, listOf(itinerary("t1", end)), thresholdSeconds
        )
        assertEquals(MonitorResult.Stop, result)
    }
}
