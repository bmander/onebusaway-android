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
package org.onebusaway.android.map.render

/**
 * If a vehicle moves less than this distance (in meters) between updates, the marker is animated to
 * the new position; otherwise it snaps (large animations look weird). Ported verbatim from the
 * legacy VehicleOverlay.MAX_VEHICLE_ANIMATION_DISTANCE.
 */
const val MAX_VEHICLE_ANIMATION_DISTANCE_M = 400.0

/** Whether a vehicle that moved [distanceMeters] should be animated (true) or snapped (false). */
fun shouldAnimateVehicle(distanceMeters: Double): Boolean =
    distanceMeters < MAX_VEHICLE_ANIMATION_DISTANCE_M
