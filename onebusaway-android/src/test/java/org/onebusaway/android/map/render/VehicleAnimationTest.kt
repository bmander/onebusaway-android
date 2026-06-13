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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [shouldAnimateVehicle] — the animate-vs-snap decision a vehicle marker makes when
 * it receives a new position (the legacy VehicleOverlay `< MAX_VEHICLE_ANIMATION_DISTANCE` branch).
 */
class VehicleAnimationTest {

    @Test
    fun shortMove_animates() {
        assertTrue(shouldAnimateVehicle(0.0))
        assertTrue(shouldAnimateVehicle(399.9))
    }

    @Test
    fun longMove_snaps() {
        assertFalse(shouldAnimateVehicle(400.0))
        assertFalse(shouldAnimateVehicle(5000.0))
    }
}
