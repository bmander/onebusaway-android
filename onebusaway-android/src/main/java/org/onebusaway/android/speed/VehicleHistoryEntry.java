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
    private final long timestamp;

    public VehicleHistoryEntry(Location position, Double distanceAlongTrip, long timestamp) {
        this.position = position;
        this.distanceAlongTrip = distanceAlongTrip;
        this.timestamp = timestamp;
    }

    public Location getPosition() {
        return position;
    }

    public Double getDistanceAlongTrip() {
        return distanceAlongTrip;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
