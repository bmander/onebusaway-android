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

import org.onebusaway.android.io.elements.ObaTripStatus;

/**
 * Immutable snapshot of a vehicle's state derived from an ObaTripStatus.
 */
public final class VehicleState {

    private final String vehicleId;
    private final String activeTripId;
    private final Location position;
    private final Location lastKnownLocation;
    private final Double distanceAlongTrip;
    private final Double scheduledDistanceAlongTrip;
    private final Double totalDistanceAlongTrip;
    private final long lastUpdateTime;
    private final long lastLocationUpdateTime;
    private final long scheduleDeviation;
    private final boolean predicted;
    private final long timestamp;

    private VehicleState(String vehicleId, String activeTripId, Location position,
                         Location lastKnownLocation, Double distanceAlongTrip,
                         Double scheduledDistanceAlongTrip, Double totalDistanceAlongTrip,
                         long lastUpdateTime, long lastLocationUpdateTime,
                         long scheduleDeviation, boolean predicted, long timestamp) {
        this.vehicleId = vehicleId;
        this.activeTripId = activeTripId;
        this.position = position;
        this.lastKnownLocation = lastKnownLocation;
        this.distanceAlongTrip = distanceAlongTrip;
        this.scheduledDistanceAlongTrip = scheduledDistanceAlongTrip;
        this.totalDistanceAlongTrip = totalDistanceAlongTrip;
        this.lastUpdateTime = lastUpdateTime;
        this.lastLocationUpdateTime = lastLocationUpdateTime;
        this.scheduleDeviation = scheduleDeviation;
        this.predicted = predicted;
        this.timestamp = timestamp;
    }

    /**
     * Creates a VehicleState with explicit values. Useful for testing.
     */
    public static VehicleState create(String vehicleId, String activeTripId, Location position,
                                      Location lastKnownLocation, Double distanceAlongTrip,
                                      Double scheduledDistanceAlongTrip,
                                      Double totalDistanceAlongTrip, long lastUpdateTime,
                                      long lastLocationUpdateTime, long scheduleDeviation,
                                      boolean predicted, long timestamp) {
        return new VehicleState(vehicleId, activeTripId, position, lastKnownLocation,
                distanceAlongTrip, scheduledDistanceAlongTrip, totalDistanceAlongTrip,
                lastUpdateTime, lastLocationUpdateTime, scheduleDeviation, predicted, timestamp);
    }

    /**
     * Creates a VehicleState from an ObaTripStatus.
     */
    public static VehicleState fromTripStatus(ObaTripStatus status) {
        return new VehicleState(
                status.getVehicleId(),
                status.getActiveTripId(),
                status.getPosition(),
                status.getLastKnownLocation(),
                status.getDistanceAlongTrip(),
                status.getScheduledDistanceAlongTrip(),
                status.getTotalDistanceAlongTrip(),
                status.getLastUpdateTime(),
                status.getLastLocationUpdateTime(),
                status.getScheduleDeviation(),
                status.isPredicted(),
                System.currentTimeMillis()
        );
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public String getActiveTripId() {
        return activeTripId;
    }

    public Location getPosition() {
        return position;
    }

    public Location getLastKnownLocation() {
        return lastKnownLocation;
    }

    public Double getDistanceAlongTrip() {
        return distanceAlongTrip;
    }

    public Double getScheduledDistanceAlongTrip() {
        return scheduledDistanceAlongTrip;
    }

    public Double getTotalDistanceAlongTrip() {
        return totalDistanceAlongTrip;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public long getLastLocationUpdateTime() {
        return lastLocationUpdateTime;
    }

    public long getScheduleDeviation() {
        return scheduleDeviation;
    }

    public boolean isPredicted() {
        return predicted;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
