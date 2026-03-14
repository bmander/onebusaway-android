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

import android.location.Location;

/**
 * A single history record for a vehicle on a trip.
 */
public final class VehicleHistoryEntry {

    private final Location position;
    private final Double distanceAlongTrip;
    private final Double lastKnownDistanceAlongTrip;
    private final long lastLocationUpdateTime;
    private final long timestamp;
    private final String vehicleId;
    private final String tripId;
    private final String blockId;

    public VehicleHistoryEntry(Location position, Double distanceAlongTrip, long timestamp) {
        this(position, distanceAlongTrip, null, 0, timestamp);
    }

    public VehicleHistoryEntry(Location position, Double distanceAlongTrip,
                               Double lastKnownDistanceAlongTrip,
                               long lastLocationUpdateTime, long timestamp) {
        this(position, distanceAlongTrip, lastKnownDistanceAlongTrip,
                lastLocationUpdateTime, timestamp, null, null, null);
    }

    public VehicleHistoryEntry(Location position, Double distanceAlongTrip,
                               Double lastKnownDistanceAlongTrip,
                               long lastLocationUpdateTime, long timestamp,
                               String vehicleId, String tripId, String blockId) {
        this.position = position;
        this.distanceAlongTrip = distanceAlongTrip;
        this.lastKnownDistanceAlongTrip = lastKnownDistanceAlongTrip;
        this.lastLocationUpdateTime = lastLocationUpdateTime;
        this.timestamp = timestamp;
        this.vehicleId = vehicleId;
        this.tripId = tripId;
        this.blockId = blockId;
    }

    public Location getPosition() {
        return position;
    }

    /**
     * @return The server-extrapolated distance along trip, in meters.
     */
    public Double getDistanceAlongTrip() {
        return distanceAlongTrip;
    }

    /**
     * @return The raw distance along trip from the vehicle's AVL system (not extrapolated).
     * Can be null if the API doesn't provide it.
     */
    public Double getLastKnownDistanceAlongTrip() {
        return lastKnownDistanceAlongTrip;
    }

    /**
     * @return The best available distance: prefers lastKnownDistanceAlongTrip (raw),
     * falls back to distanceAlongTrip (extrapolated).
     */
    public Double getBestDistanceAlongTrip() {
        if (lastKnownDistanceAlongTrip != null && lastKnownDistanceAlongTrip != 0.0) {
            return lastKnownDistanceAlongTrip;
        }
        return distanceAlongTrip;
    }

    /**
     * @return The time of the last location update from the vehicle's AVL system.
     * Used to deduplicate entries — if this hasn't changed, the server just
     * re-extrapolated from the same underlying AVL report.
     */
    public long getLastLocationUpdateTime() {
        return lastLocationUpdateTime;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public String getTripId() {
        return tripId;
    }

    public String getBlockId() {
        return blockId;
    }
}
