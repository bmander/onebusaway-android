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
package org.onebusaway.android.extrapolation.data

import android.location.Location

/** A single history record for a vehicle on a trip. */
data class VehicleHistoryEntry(
        val position: Location?,
        /** The server-extrapolated distance along trip, in meters. */
        val distanceAlongTrip: Double?,
        /**
         * The raw distance along trip from the vehicle's AVL system (not extrapolated). Can be null
         * if the API doesn't provide it.
         */
        val lastKnownDistanceAlongTrip: Double? = null,
        /**
         * The time of the last location update from the vehicle's AVL system. Used to deduplicate
         * entries — if this hasn't changed, the server just re-extrapolated from the same
         * underlying AVL report.
         */
        val lastLocationUpdateTime: Long = 0,
        val timestamp: Long,
        val vehicleId: String? = null
) {
    /**
     * The best available distance: prefers lastKnownDistanceAlongTrip (raw), falls back to
     * distanceAlongTrip (extrapolated).
     */
    val bestDistanceAlongTrip: Double?
        get() =
                if (lastKnownDistanceAlongTrip != null && lastKnownDistanceAlongTrip != 0.0) {
                    lastKnownDistanceAlongTrip
                } else {
                    distanceAlongTrip
                }

    companion object {
        /** Returns the newest history entry with a valid distance and timestamp, or null. */
        @JvmStatic
        fun findNewestValid(history: List<VehicleHistoryEntry>?): VehicleHistoryEntry? {
            if (history == null) return null
            for (i in history.indices.reversed()) {
                val e = history[i]
                if (e.bestDistanceAlongTrip != null && e.lastLocationUpdateTime > 0) {
                    return e
                }
            }
            return null
        }
    }
}
