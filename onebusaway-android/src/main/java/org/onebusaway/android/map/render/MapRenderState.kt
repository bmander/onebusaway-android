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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.io.request.ObaTripsForRouteResponse
import org.opentripplanner.routing.bike_rental.BikeRentalStation

/** A geographic point, flavor-neutral (carries no Google/maplibre `LatLng` dependency). */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * One route/itinerary polyline: a colored, ordered list of points. The directional-arrow stamp is a
 * rendering detail added by the flavor renderer, so it isn't part of the state.
 */
data class RoutePolyline(val color: Int, val points: List<GeoPoint>)

/**
 * A generic pin added by code outside the map package (trip-plan start/end, the report location
 * picker). [hue] is a Google `BitmapDescriptorFactory` hue in [0, 360), or null for the default pin.
 */
data class GenericMarker(val point: GeoPoint, val hue: Float?)

/**
 * One real-time vehicle marker. [status] is the raw io/elements status (the renderer derives the
 * icon, color, and info-window text from it, paired with the shared [MapRenderSnapshot.vehicleResponse]);
 * [activeTripId] is the stable key used for marker identity + animation; [isRealtime] is the
 * populate-time decision (last-known location present + predicted) that selects the live-vs-scheduled icon.
 */
data class VehicleMarker(
    val activeTripId: String,
    val point: GeoPoint,
    val isRealtime: Boolean,
    val status: ObaTripStatus,
)

/**
 * One bike-rental marker. [station] is the raw OTP pojo (the renderer reads its name/availability for
 * the info window and its floating-vs-station flag for the icon). [bikeshareVisible] on the snapshot
 * is the layer/directions-mode gate; the per-zoom icon band is chosen live by the renderer.
 */
data class BikeMarker(
    val id: String,
    val point: GeoPoint,
    val isFloatingBike: Boolean,
    val station: BikeRentalStation,
)

/** Immutable snapshot of everything the map should render. Grows one overlay per phase. */
data class MapRenderSnapshot(
    val routePolylines: List<RoutePolyline> = emptyList(),
    val genericMarkers: Map<Int, GenericMarker> = emptyMap(),
    val vehicles: List<VehicleMarker> = emptyList(),
    val vehicleResponse: ObaTripsForRouteResponse? = null,
    val bikeStations: List<BikeMarker> = emptyList(),
    val bikeshareVisible: Boolean = false,
    // The currently focused stop id, couriered so the vehicle info-window's "more info" tap can deep
    // link into TripDetails scoped to that stop (the legacy VehicleOverlay.Controller hook).
    val focusedStopId: String? = null,
)

/**
 * Flavor-neutral, declarative model of the map's overlay content. The imperative
 * [org.onebusaway.android.map.MapModeController.ObaMapView] methods on a flavor host mutate this
 * model instead of touching the map SDK directly; a renderer observes [snapshot] and draws it
 * (a Compose `GoogleMap {}` content lambda on the Google flavor, symbol/line layers on maplibre).
 *
 * It lives in `src/main` so both flavors share a single model. Mutators are plain methods (rather
 * than Kotlin properties) so the Java hosts can call them idiomatically.
 */
class MapRenderState {

    private val _snapshot = MutableStateFlow(MapRenderSnapshot())

    val snapshot: StateFlow<MapRenderSnapshot> = _snapshot.asStateFlow()

    fun getRoutePolylines(): List<RoutePolyline> = _snapshot.value.routePolylines

    fun setRoutePolylines(polylines: List<RoutePolyline>) {
        _snapshot.update { it.copy(routePolylines = polylines) }
    }

    fun clearRoutePolylines() = setRoutePolylines(emptyList())

    // --- Generic markers (the old SimpleMarkerOverlay): monotonic int IDs the caller keeps to ---
    // --- remove the marker later. Unlike the old overlay, these survive until the map renders, ---
    // --- so addMarker never has to return a "not ready" sentinel. ---

    private var nextMarkerId = 0

    @Synchronized
    fun addMarker(point: GeoPoint, hue: Float?): Int {
        val id = nextMarkerId++
        _snapshot.update { it.copy(genericMarkers = it.genericMarkers + (id to GenericMarker(point, hue))) }
        return id
    }

    fun removeMarker(id: Int) {
        _snapshot.update { it.copy(genericMarkers = it.genericMarkers - id) }
    }

    // --- Vehicles (the old VehicleOverlay): all vehicles share one response for route/trip lookups. ---

    fun setVehicles(vehicles: List<VehicleMarker>, response: ObaTripsForRouteResponse?) {
        _snapshot.update { it.copy(vehicles = vehicles, vehicleResponse = response) }
    }

    fun clearVehicles() {
        _snapshot.update { it.copy(vehicles = emptyList(), vehicleResponse = null) }
    }

    // --- Bike stations (the old BikeStationOverlay): the per-zoom icon band is chosen by the ---
    // --- renderer; [bikeshareVisible] carries the layer/directions-mode gate. ---

    fun setBikeStations(stations: List<BikeMarker>, bikeshareVisible: Boolean) {
        _snapshot.update { it.copy(bikeStations = stations, bikeshareVisible = bikeshareVisible) }
    }

    fun clearBikeStations() {
        _snapshot.update { it.copy(bikeStations = emptyList()) }
    }

    fun setFocusedStopId(stopId: String?) {
        _snapshot.update { it.copy(focusedStopId = stopId) }
    }
}
