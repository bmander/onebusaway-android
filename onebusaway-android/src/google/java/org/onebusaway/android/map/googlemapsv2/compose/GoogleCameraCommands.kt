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
package org.onebusaway.android.map.googlemapsv2.compose

import android.content.Context
import android.widget.Toast
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.map.googlemapsv2.MapHelpV2
import org.onebusaway.android.map.render.CameraCommand
import org.onebusaway.android.map.render.MapRenderState
import org.onebusaway.android.util.AndroidUtils
import kotlin.math.abs

// The same constants the imperative GoogleMapHost used for these camera moves.
private const val CAMERA_DEFAULT_ZOOM = 16.0f
private const val DEFAULT_MAP_PADDING_DP = 20.0f

/**
 * Applies one [CameraCommand] to the map's [CameraPositionState] — the declarative replacement for the
 * `GoogleMapHost` methods that used to call `mMap.animateCamera/moveCamera` directly. The math
 * (bounds, the route-header recenter bias, the closest-vehicle visibility short-circuit) is a faithful
 * port of those methods, now reading the live camera from [camera] and the route shape from
 * [renderState]. `animate(...)` suspends until the animation completes; commands are collected
 * sequentially, which is fine given how infrequently they fire.
 */
suspend fun applyCameraCommand(
    cmd: CameraCommand,
    camera: CameraPositionState,
    renderState: MapRenderState,
    context: Context,
) {
    when (cmd) {
        is CameraCommand.Recenter -> {
            val current = camera.position
            var target = LatLng(cmd.lat, cmd.lon)
            if (cmd.applyRouteBias) {
                // Map padding doesn't get the route-header offset quite right, so nudge the target up
                // by a fraction of the visible longitude span (the legacy setMapCenter bias).
                val span = camera.projection?.visibleRegion?.latLngBounds?.let {
                    abs(it.northeast.longitude - it.southwest.longitude)
                } ?: 0.0
                target = LatLng(cmd.lat - span * 0.2 / 2, cmd.lon)
            }
            val update = CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(target)
                    .zoom(current.zoom).bearing(current.bearing).tilt(current.tilt).build()
            )
            if (cmd.animate) camera.animate(update) else camera.move(update)
        }

        is CameraCommand.MoveToLocation -> {
            val zoom = if (cmd.useDefaultZoom) CAMERA_DEFAULT_ZOOM else camera.position.zoom
            val update = CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(cmd.lat, cmd.lon)).zoom(zoom).build()
            )
            if (cmd.animate) camera.animate(update) else camera.move(update)
        }

        is CameraCommand.CenterOnStopTap -> {
            val pos = LatLng(cmd.lat, cmd.lon)
            val update = if (camera.position.zoom < CAMERA_DEFAULT_ZOOM) {
                CameraUpdateFactory.newLatLngZoom(pos, CAMERA_DEFAULT_ZOOM)
            } else {
                CameraUpdateFactory.newLatLng(pos)
            }
            camera.animate(update)
        }

        is CameraCommand.SetZoom -> camera.move(CameraUpdateFactory.zoomTo(cmd.zoom))

        CameraCommand.FitToRoute -> {
            val bounds = routePolylineBounds(renderState)
            if (bounds == null) {
                Toast.makeText(context, R.string.route_info_no_shape_data, Toast.LENGTH_SHORT).show()
            } else {
                camera.move(
                    CameraUpdateFactory.newLatLngBounds(bounds, AndroidUtils.dpToPixels(context, DEFAULT_MAP_PADDING_DP))
                )
            }
        }

        CameraCommand.FitToItinerary -> {
            val bounds = routePolylineBounds(renderState) ?: return
            val dm = context.resources.displayMetrics
            camera.move(
                CameraUpdateFactory.newLatLngBounds(
                    bounds, dm.widthPixels, dm.heightPixels,
                    AndroidUtils.dpToPixels(context, DEFAULT_MAP_PADDING_DP)
                )
            )
        }

        CameraCommand.ZoomToRegion -> {
            val region = Application.get().currentRegion ?: return
            val bounds = MapHelpV2.getRegionBounds(region)
            // Use screen dimensions to avoid IllegalStateException (#581).
            val dm = context.resources.displayMetrics
            camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, dm.widthPixels, dm.heightPixels, 0))
        }

        is CameraCommand.IncludeClosestVehicle -> {
            val center = MapHelpV2.makeLocation(camera.position.target)
            val closest = MapHelpV2.getClosestVehicle(cmd.response, HashSet(cmd.routeIds), center) ?: return
            val visible = camera.projection?.visibleRegion?.latLngBounds ?: return
            if (visible.contains(closest)) {
                return
            }
            val bounds = LatLngBounds.Builder()
                .include(visible.northeast).include(visible.southwest).include(closest).build()
            camera.animate(
                CameraUpdateFactory.newLatLngBounds(bounds, AndroidUtils.dpToPixels(context, DEFAULT_MAP_PADDING_DP))
            )
        }

        CameraCommand.ZoomIn -> camera.animate(CameraUpdateFactory.zoomIn())

        CameraCommand.ZoomOut -> camera.animate(CameraUpdateFactory.zoomOut())

        CameraCommand.ResetTilt -> {
            val current = camera.position
            camera.move(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(current.target).zoom(current.zoom).tilt(0f).build()
                )
            )
        }
    }
}

/** Bounds enclosing the current route/itinerary polylines, or null if there are no points. */
private fun routePolylineBounds(renderState: MapRenderState): LatLngBounds? {
    val builder = LatLngBounds.Builder()
    var any = false
    for (polyline in renderState.snapshot.value.routePolylines) {
        for (point in polyline.points) {
            builder.include(LatLng(point.latitude, point.longitude))
            any = true
        }
    }
    return if (any) builder.build() else null
}
