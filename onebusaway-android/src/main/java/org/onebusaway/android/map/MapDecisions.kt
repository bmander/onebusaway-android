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

/**
 * The pure decision branches behind [MapViewModel]'s location/region behavior, split out from the
 * `Application`/`LocationUtils`/`PermissionUtils` reads (which stay at the call site) so the branch
 * logic — the historically error-prone permission/location flow ported from the flavor hosts — can be
 * unit-tested on the JVM. Same pattern as [zoomFulfills] / [stopRequestFulfilled] /
 * [org.onebusaway.android.map.bike.bikeAction] / [nextVehicleDelay].
 */

/** What the my-location request should do, given the current location/permission state. */
enum class MyLocationAction {
    /** Move the camera to the last-known location. */
    MoveToLocation,

    /** Location services are off — show the enable-location dialog. */
    ShowNoLocationDialog,

    /** No permission yet — show the rationale before asking. */
    ShowPermissionRationale,

    /** Permission + services but no fix yet — show the "waiting for location" toast. */
    ShowWaitingToast,

    /** Nothing to do (e.g. services off but the user opted out of the dialog, or permission denied). */
    None,
}

/**
 * The my-location decision, ported verbatim from the flavor hosts' `setMyLocation`:
 *  - services off → the no-location dialog, unless the user opted out of it
 *  - no fix yet + no permission → the rationale, unless the user already declined permission
 *  - no fix yet + permission → the "waiting" toast
 *  - otherwise → move to the last-known location
 */
fun myLocationAction(
    locationEnabled: Boolean,
    neverShowLocationDialog: Boolean,
    hasLastKnownLocation: Boolean,
    hasPermission: Boolean,
    userDeniedPermission: Boolean,
): MyLocationAction {
    if (!locationEnabled) {
        return if (neverShowLocationDialog) MyLocationAction.None else MyLocationAction.ShowNoLocationDialog
    }
    if (!hasLastKnownLocation) {
        if (!hasPermission) {
            return if (userDeniedPermission) MyLocationAction.None else MyLocationAction.ShowPermissionRationale
        }
        return MyLocationAction.ShowWaitingToast
    }
    return MyLocationAction.MoveToLocation
}

/** How a resolved region should (re)frame the camera. */
enum class RegionRezoom {
    /** Frame the user's location. */
    FrameMyLocation,

    /** Frame the region's bounds. */
    FrameRegion,

    /** Leave the camera where it is. */
    None,
}

/**
 * The region-resolved re-zoom decision, ported from the hosts' `onRegionTaskFinished`: only re-frame
 * when the region actually changed *and* we have no location or the camera is still at the (0,0) seed;
 * prefer framing the user's location when we have one, else the region.
 */
fun regionRezoom(changed: Boolean, hasLocation: Boolean, cameraAtSeed: Boolean): RegionRezoom {
    if (!changed) {
        return RegionRezoom.None
    }
    // Has a location but the camera has already moved off the seed: don't yank it back.
    if (hasLocation && !cameraAtSeed) {
        return RegionRezoom.None
    }
    return if (hasLocation) RegionRezoom.FrameMyLocation else RegionRezoom.FrameRegion
}
