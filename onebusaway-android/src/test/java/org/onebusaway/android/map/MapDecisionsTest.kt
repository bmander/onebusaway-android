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
package org.onebusaway.android.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure location/region decisions [myLocationAction] + [regionRezoom] that the map
 * view model uses (the branchy permission/location/region flow ported from the flavor hosts'
 * setMyLocation + onRegionTaskFinished, split out from the Android reads so it can be tested here).
 */
class MapDecisionsTest {

    // --- myLocationAction ---

    @Test
    fun `services off shows the no-location dialog unless opted out`() {
        assertEquals(
            MyLocationAction.ShowNoLocationDialog,
            myLocationAction(
                locationEnabled = false,
                neverShowLocationDialog = false,
                hasLastKnownLocation = true,
                hasPermission = true,
                userDeniedPermission = false,
            )
        )
        assertEquals(
            MyLocationAction.None,
            myLocationAction(
                locationEnabled = false,
                neverShowLocationDialog = true,
                hasLastKnownLocation = true,
                hasPermission = true,
                userDeniedPermission = false,
            )
        )
    }

    @Test
    fun `no fix and no permission shows the rationale unless already declined`() {
        assertEquals(
            MyLocationAction.ShowPermissionRationale,
            myLocationAction(
                locationEnabled = true,
                neverShowLocationDialog = false,
                hasLastKnownLocation = false,
                hasPermission = false,
                userDeniedPermission = false,
            )
        )
        assertEquals(
            MyLocationAction.None,
            myLocationAction(
                locationEnabled = true,
                neverShowLocationDialog = false,
                hasLastKnownLocation = false,
                hasPermission = false,
                userDeniedPermission = true,
            )
        )
    }

    @Test
    fun `no fix but permission shows the waiting toast`() {
        assertEquals(
            MyLocationAction.ShowWaitingToast,
            myLocationAction(
                locationEnabled = true,
                neverShowLocationDialog = false,
                hasLastKnownLocation = false,
                hasPermission = true,
                userDeniedPermission = false,
            )
        )
    }

    @Test
    fun `services on with a fix moves the camera`() {
        assertEquals(
            MyLocationAction.MoveToLocation,
            myLocationAction(
                locationEnabled = true,
                neverShowLocationDialog = false,
                hasLastKnownLocation = true,
                hasPermission = false, // a cached fix moves us even without a live permission
                userDeniedPermission = true,
            )
        )
    }

    // --- regionRezoom ---

    @Test
    fun `an unchanged region never re-zooms`() {
        assertEquals(RegionRezoom.None, regionRezoom(changed = false, hasLocation = false, cameraAtSeed = true))
        assertEquals(RegionRezoom.None, regionRezoom(changed = false, hasLocation = true, cameraAtSeed = true))
    }

    @Test
    fun `a changed region with no location frames the region`() {
        assertEquals(RegionRezoom.FrameRegion, regionRezoom(changed = true, hasLocation = false, cameraAtSeed = true))
        assertEquals(RegionRezoom.FrameRegion, regionRezoom(changed = true, hasLocation = false, cameraAtSeed = false))
    }

    @Test
    fun `a changed region with a location frames it only while the camera is at the seed`() {
        assertEquals(
            RegionRezoom.FrameMyLocation,
            regionRezoom(changed = true, hasLocation = true, cameraAtSeed = true)
        )
        // Camera already moved off the seed: leave it where the user put it.
        assertEquals(
            RegionRezoom.None,
            regionRezoom(changed = true, hasLocation = true, cameraAtSeed = false)
        )
    }
}
