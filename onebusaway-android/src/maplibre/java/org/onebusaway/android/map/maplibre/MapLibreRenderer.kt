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
package org.onebusaway.android.map.maplibre

import android.content.Context
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.onebusaway.android.map.render.BikeBand
import org.onebusaway.android.map.render.BikeBitmaps
import org.onebusaway.android.map.render.BikeMarker
import org.onebusaway.android.map.render.MapRenderState
import org.onebusaway.android.map.render.StopMarker
import org.onebusaway.android.map.render.VehicleBitmaps
import org.onebusaway.android.map.render.VehicleMarker
import org.onebusaway.android.map.render.bikeZoomBand
import org.onebusaway.android.util.UIUtils

/**
 * The maplibre counterpart of the Google `ObaMapContent`: it draws the shared [MapRenderState] onto
 * the map imperatively, using the classic maplibre annotation API (the same one the old
 * `StopOverlay` used). On each [render] it clears the annotations and redraws stops, route
 * polylines, vehicles, bikes, and generic pins from the current snapshot, keeping marker→data maps
 * so the host can route taps back to focus/info-window handlers.
 *
 * This is intentionally a simple clear-and-redraw (correctness over churn) — the snapshot only
 * changes on viewport loads, the 10s vehicle poll, and focus, so the redraw cost is bounded.
 * maplibre markers have no per-marker anchor and the classic info window is title/snippet, so the
 * rich Google Compose info windows degrade to a title + snippet here (a deliberate flavor gap).
 */
class MapLibreRenderer(
    private val map: MapLibreMap,
    private val context: Context,
    private val renderState: MapRenderState,
) {
    private val stopByMarker = HashMap<Marker, StopMarker>()

    private val bikeByMarker = HashMap<Marker, BikeMarker>()

    private val vehicleByMarker = HashMap<Marker, VehicleMarker>()

    private val iconFactory = IconFactory.getInstance(context)

    fun render() {
        val snapshot = renderState.snapshot.value
        // Classic annotations have no diffing, so clear + redraw the lot from the snapshot.
        map.clear()
        stopByMarker.clear()
        bikeByMarker.clear()
        vehicleByMarker.clear()

        for (polyline in snapshot.routePolylines) {
            val options = PolylineOptions().color(polyline.color).width(ROUTE_WIDTH_DP)
            for (point in polyline.points) {
                options.add(LatLng(point.latitude, point.longitude))
            }
            map.addPolyline(options)
        }

        for (stop in snapshot.stops) {
            val icon = if (stop.id == snapshot.focusedStopId) {
                MapLibreStopIcons.focusedIconForDirection(context, stop.direction)
            } else {
                MapLibreStopIcons.iconForDirection(context, stop.direction)
            }
            val marker = map.addMarker(
                MarkerOptions().position(LatLng(stop.point.latitude, stop.point.longitude)).icon(icon)
            )
            stopByMarker[marker] = stop
        }

        val response = snapshot.vehicleResponse
        if (response != null) {
            for (vehicle in snapshot.vehicles) {
                val icon = iconFactory.fromBitmap(
                    VehicleBitmaps.vehicleBitmap(context, vehicle.isRealtime, vehicle.status, response)
                )
                val trip = response.getTrip(vehicle.status.activeTripId)
                val route = response.getRoute(trip.routeId)
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(LatLng(vehicle.point.latitude, vehicle.point.longitude))
                        .icon(icon)
                        .title(
                            UIUtils.getRouteDisplayName(route) + " - " +
                                UIUtils.formatDisplayText(trip.headsign)
                        )
                )
                vehicleByMarker[marker] = vehicle
            }
        }

        if (snapshot.bikeshareVisible) {
            val band = bikeZoomBand(map.cameraPosition.zoom.toFloat())
            if (band != BikeBand.HIDDEN) {
                for (bike in snapshot.bikeStations) {
                    val bitmap = when {
                        band == BikeBand.BIG && bike.isFloatingBike -> BikeBitmaps.bigFloating(context)
                        band == BikeBand.BIG -> BikeBitmaps.bigStation(context)
                        else -> BikeBitmaps.small(context)
                    }
                    val station = bike.station
                    val snippet = if (bike.isFloatingBike) {
                        ""
                    } else {
                        "${station.bikesAvailable} bikes, ${station.spacesAvailable} spaces"
                    }
                    val marker = map.addMarker(
                        MarkerOptions()
                            .position(LatLng(bike.point.latitude, bike.point.longitude))
                            .icon(iconFactory.fromBitmap(bitmap))
                            .title(station.name)
                            .snippet(snippet)
                    )
                    bikeByMarker[marker] = bike
                }
            }
        }

        for ((_, generic) in snapshot.genericMarkers) {
            // The classic default marker has no hue, so the green/red start/end distinction is lost
            // on maplibre (a minor flavor gap vs. the Google pins).
            map.addMarker(
                MarkerOptions().position(LatLng(generic.point.latitude, generic.point.longitude))
            )
        }
    }

    fun stopForMarker(marker: Marker): StopMarker? = stopByMarker[marker]

    fun bikeForMarker(marker: Marker): BikeMarker? = bikeByMarker[marker]

    fun vehicleForMarker(marker: Marker): VehicleMarker? = vehicleByMarker[marker]

    companion object {
        private const val ROUTE_WIDTH_DP = 3f
    }
}
