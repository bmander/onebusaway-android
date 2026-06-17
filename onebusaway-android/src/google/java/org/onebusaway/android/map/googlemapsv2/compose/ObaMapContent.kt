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

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.StrokeStyle
import com.google.android.gms.maps.model.StyleSpan
import com.google.android.gms.maps.model.TextureStyle
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerInfoWindow
import com.google.maps.android.compose.MarkerInfoWindowContent
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberUpdatedMarkerState
import org.onebusaway.android.R
import org.onebusaway.android.map.compose.BikeInfoWindow
import org.onebusaway.android.map.compose.ObaMapCallbacks
import org.onebusaway.android.map.compose.VehicleInfoWindow
import org.onebusaway.android.map.googlemapsv2.StopIconFactory
import org.onebusaway.android.map.googlemapsv2.VehicleIconFactory
import org.onebusaway.android.map.render.BikeBand
import org.onebusaway.android.map.render.MapRenderState
import org.onebusaway.android.map.render.bikeZoomBand
import org.onebusaway.android.map.render.shouldAnimateVehicle

/**
 * Declarative render of [MapRenderState] inside a `GoogleMap {}` content lambda. This is the
 * counterpart of the imperative overlay classes: the flavor host pushes overlay content into the
 * shared [MapRenderState] via its `ObaMapView` methods, and this composable draws whatever the
 * current snapshot holds: route / itinerary polylines, stops (+ focus), vehicles (+ info window),
 * bike stations (+ info window), and generic pins (trip-plan start/end, the report location picker).
 */
@Composable
@GoogleMapComposable
fun ObaMapContent(
    renderState: MapRenderState,
    cameraPositionState: CameraPositionState,
    callbacks: ObaMapCallbacks,
) {
    val snapshot by renderState.snapshot.collectAsState()
    val context = LocalContext.current

    // The chevron texture is color-independent, so decode it once; the per-polyline color is applied
    // by the StrokeStyle below. Safe to build here: the map SDK is initialized before this content
    // composes. (This is the same texture the legacy GoogleMapHost.setRouteOverlay built.)
    val arrow = remember {
        TextureStyle.newBuilder(
            BitmapDescriptorFactory.fromResource(R.drawable.ic_navigation_expand_more)
        ).build()
    }

    snapshot.routePolylines.forEach { polyline ->
        // Memoize the LatLng list + span so an unrelated snapshot change (e.g. the 10s vehicle poll)
        // doesn't re-map every route point or rebuild the span on each recomposition.
        val points = remember(polyline.points) {
            polyline.points.map { LatLng(it.latitude, it.longitude) }
        }
        val spans = remember(polyline.color) {
            listOf(StyleSpan(StrokeStyle.colorBuilder(polyline.color).stamp(arrow).build()))
        }
        Polyline(points = points, spans = spans)
    }

    // Stops. Each is a flat marker anchored per direction; the focused stop swaps to the 1.5x icon.
    // Tapping focuses the stop (the host animates the camera + notifies listeners). Drawn before
    // vehicles so vehicles (z-index 1) stay on top.
    val focusedStopId = snapshot.focusedStopId
    snapshot.stops.forEach { stop ->
        key(stop.id) {
            val markerState = rememberUpdatedMarkerState(
                position = LatLng(stop.point.latitude, stop.point.longitude)
            )
            val focused = stop.id == focusedStopId
            val icon = remember(stop.direction, stop.routeType, focused) {
                if (focused) {
                    StopIconFactory.focusedStopIcon(stop.direction, stop.routeType)
                } else {
                    StopIconFactory.stopIcon(stop.direction, stop.routeType)
                }
            }
            Marker(
                state = markerState,
                icon = icon,
                flat = true,
                anchor = Offset(
                    StopIconFactory.anchorX(stop.direction),
                    StopIconFactory.anchorY(stop.direction)
                ),
                onClick = { callbacks.onStopClick(stop.stop); true },
            )
        }
    }

    // Generic pins (trip-plan start/end, report location picker). Keyed by their stable id so a pin
    // keeps its node across recompositions; a null hue renders the SDK's default marker.
    snapshot.genericMarkers.forEach { (id, marker) ->
        key(id) {
            val markerState = rememberUpdatedMarkerState(
                position = LatLng(marker.point.latitude, marker.point.longitude)
            )
            Marker(
                state = markerState,
                icon = marker.hue?.let { remember(it) { BitmapDescriptorFactory.defaultMarker(it) } },
            )
        }
    }

    // Real-time vehicles. The marker animates to a new position when it moves a short distance and
    // snaps for large jumps (the legacy < 400m rule). Tapping shows a Compose MarkerInfoWindow; the
    // SDK renders it because GoogleMapHost's imperative marker-click listener returns false for these
    // maps-compose markers, leaving maps-compose's info-window adapter to draw the content.
    val vehicleResponse = snapshot.vehicleResponse
    if (vehicleResponse != null) {
        snapshot.vehicles.forEach { vehicle ->
            key(vehicle.activeTripId) {
                val target = LatLng(vehicle.point.latitude, vehicle.point.longitude)
                // Manually-driven state (NOT rememberUpdatedMarkerState): the LaunchedEffect below
                // animates markerState.position, which an auto-updating state would clobber.
                val markerState = remember { MarkerState(position = target) }
                LaunchedEffect(target) {
                    val start = markerState.position
                    if (start.latitude == target.latitude && start.longitude == target.longitude) {
                        return@LaunchedEffect
                    }
                    val dist = VehicleIconFactory.distanceMeters(
                        start.latitude, start.longitude, target.latitude, target.longitude
                    )
                    if (shouldAnimateVehicle(dist)) {
                        animate(0f, 1f, animationSpec = tween(durationMillis = 1000)) { f, _ ->
                            markerState.position = LatLng(
                                start.latitude + (target.latitude - start.latitude) * f,
                                start.longitude + (target.longitude - start.longitude) * f,
                            )
                        }
                    } else {
                        markerState.position = target
                    }
                }
                val icon = remember(vehicle.status) {
                    VehicleIconFactory.getVehicleIcon(
                        context, vehicle.isRealtime, vehicle.status, vehicleResponse
                    )
                }
                MarkerInfoWindow(
                    state = markerState,
                    icon = icon,
                    zIndex = 1f,
                    onInfoWindowClick = { callbacks.onVehicleInfoWindowClick(vehicle.status) },
                ) {
                    VehicleInfoWindow(vehicle.status, vehicleResponse)
                }
            }
        }
    }

    // Bike stations. The icon band (hidden / small dot / big station-or-floating) is chosen live from
    // the camera zoom via derivedStateOf, so it only recomposes when crossing a band boundary rather
    // than on every pan. Visibility also honors the layer/directions-mode gate.
    if (snapshot.bikeStations.isNotEmpty()) {
        val bikeIcons = remember { BikeIcons(context) }
        val band by remember {
            derivedStateOf { bikeZoomBand(cameraPositionState.position.zoom) }
        }
        snapshot.bikeStations.forEach { bike ->
            key(bike.id) {
                val markerState = rememberUpdatedMarkerState(
                    position = LatLng(bike.point.latitude, bike.point.longitude)
                )
                val icon = when {
                    band == BikeBand.BIG && bike.isFloatingBike -> bikeIcons.bigFloating
                    band == BikeBand.BIG -> bikeIcons.bigStation
                    else -> bikeIcons.small
                }
                MarkerInfoWindowContent(
                    state = markerState,
                    icon = icon,
                    visible = snapshot.bikeshareVisible && band != BikeBand.HIDDEN,
                    // false: also show the info window (the legacy markerClicked did both).
                    onClick = { callbacks.onBikeClick(bike.station); false },
                    onInfoWindowClick = { callbacks.onBikeInfoWindowClick(bike.station) },
                ) {
                    BikeInfoWindow(bike.station)
                }
            }
        }
    }
}
