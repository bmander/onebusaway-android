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

import androidx.lifecycle.ViewModel
import org.onebusaway.android.io.elements.ObaReferences
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaShape
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.io.elements.Status
import org.onebusaway.android.io.request.ObaTripsForRouteResponse
import org.onebusaway.android.map.render.BikeMarker
import org.onebusaway.android.map.render.GeoPoint
import org.onebusaway.android.map.render.MapRenderState
import org.onebusaway.android.map.render.RoutePolyline
import org.onebusaway.android.map.render.StopMarker
import org.onebusaway.android.map.render.VehicleMarker
import org.onebusaway.android.map.render.primaryRouteType
import org.opentripplanner.routing.bike_rental.BikeRentalStation

/**
 * The map's view model: the single owner of the flavor-neutral [MapRenderState] and of the logic
 * that shapes the raw `io/elements` responses into render markers. This used to be duplicated in
 * both flavor hosts (`GoogleMapHost` + `MapLibreMapHost`) — accumulating stops with the 200-cap,
 * resolving icon route types, filtering vehicles, building bike markers; folding it here both
 * de-duplicates the hosts and (as an androidx [ViewModel]) lets the rendered state survive a
 * configuration change. The coroutine controllers (StopMapController, RouteMapController, …) stay as
 * the loaders that drive these methods; the hosts become thin renderers of [renderState].
 *
 * Scoped to the hosting Activity (the host obtains it via `ViewModelProvider`), so all map screens in
 * an Activity share one render model.
 */
class MapViewModel : ViewModel() {

    val renderState = MapRenderState()

    // Stop accumulation across pans (capped, keeping the focused stop) + the routes cache used to
    // resolve a stop's icon route type and to report a stop's routes to focus listeners.
    private val stopAccum = LinkedHashMap<String, StopMarker>()

    private val cachedRoutes = HashMap<String, ObaRoute>()

    // ----- Stops -----

    fun showStops(stops: List<ObaStop>, refs: ObaReferences) {
        for (route in refs.routes) {
            cachedRoutes[route.id] = route
        }
        val typeByRouteId = cachedRoutes.values.associate { it.id to it.type }
        if (stopAccum.size >= FUZZY_MAX_STOP_COUNT) {
            val focused = renderState.snapshot.value.focusedStopId?.let { stopAccum[it] }
            stopAccum.clear()
            if (focused != null) {
                stopAccum[focused.id] = focused
            }
        }
        for (stop in stops) {
            if (!stopAccum.containsKey(stop.id)) {
                stopAccum[stop.id] = toStopMarker(stop, typeByRouteId)
            }
        }
        renderState.setStops(ArrayList(stopAccum.values))
    }

    /** Clears accumulated stops; keeps the focused one unless [clearFocusedStop]. */
    fun clearStops(clearFocusedStop: Boolean) {
        val focusedId = renderState.snapshot.value.focusedStopId
        val focused = if (!clearFocusedStop && focusedId != null) stopAccum[focusedId] else null
        stopAccum.clear()
        if (focused != null) {
            stopAccum[focused.id] = focused
        } else if (clearFocusedStop) {
            renderState.setFocusedStopId(null)
        }
        renderState.setStops(ArrayList(stopAccum.values))
    }

    /** Programmatic focus (intent/rotation): ensures the stop is on the map, then focuses it. */
    fun setFocusStop(stop: ObaStop?, routes: List<ObaRoute>?) {
        if (stop == null) {
            renderState.setFocusedStopId(null)
            return
        }
        if (!stopAccum.containsKey(stop.id)) {
            routes?.forEach { cachedRoutes[it.id] = it }
            val typeByRouteId = cachedRoutes.values.associate { it.id to it.type }
            stopAccum[stop.id] = toStopMarker(stop, typeByRouteId)
            renderState.setStops(ArrayList(stopAccum.values))
        }
        renderState.setFocusedStopId(stop.id)
    }

    fun setFocusedStopId(stopId: String?) = renderState.setFocusedStopId(stopId)

    /** A snapshot copy of the cached routes, for reporting a stop's routes to focus listeners. */
    fun cachedRoutes(): HashMap<String, ObaRoute> = HashMap(cachedRoutes)

    private fun toStopMarker(stop: ObaStop, typeByRouteId: Map<String, Int>): StopMarker {
        val routeType = primaryRouteType(stop.routeIds, typeByRouteId)
        val loc = stop.location
        // ObaStop.getDirection() is "N".."NW" or the literal "null" string for no direction.
        val direction = stop.direction ?: "null"
        return StopMarker(stop.id, GeoPoint(loc.latitude, loc.longitude), direction, routeType, stop)
    }

    // ----- Route polylines -----

    fun setRoute(color: Int, shapes: Array<ObaShape>, clear: Boolean) {
        val polylines = ArrayList<RoutePolyline>(if (clear) emptyList() else renderState.getRoutePolylines())
        for (shape in shapes) {
            val points = shape.points.map { GeoPoint(it.latitude, it.longitude) }
            polylines.add(RoutePolyline(color, points))
        }
        renderState.setRoutePolylines(polylines)
    }

    fun clearRoute() = renderState.clearRoutePolylines()

    /** The route/itinerary point lists, for the host to compute camera bounds. */
    fun routePoints(): List<List<GeoPoint>> = renderState.getRoutePolylines().map { it.points }

    // ----- Vehicles -----

    fun updateVehicles(routeIds: HashSet<String>, response: ObaTripsForRouteResponse) {
        val markers = ArrayList<VehicleMarker>()
        for (trip in response.trips) {
            val status = trip.status ?: continue
            val activeRoute = response.getTrip(status.activeTripId).routeId
            if (!routeIds.contains(activeRoute) || Status.CANCELED == status.status) {
                continue
            }
            var location = status.lastKnownLocation
            var isRealtime = true
            if (location == null) {
                location = status.position
                isRealtime = false
            }
            if (!status.isPredicted) {
                isRealtime = false
            }
            markers.add(
                VehicleMarker(
                    status.activeTripId,
                    GeoPoint(location.latitude, location.longitude),
                    isRealtime,
                    status,
                )
            )
        }
        renderState.setVehicles(markers, response)
    }

    fun clearVehicles() = renderState.clearVehicles()

    // ----- Bikes -----

    fun showBikeStations(stations: List<BikeRentalStation>, bikeshareVisible: Boolean) {
        val markers = stations.map {
            BikeMarker(it.id, GeoPoint(it.y, it.x), it.isFloatingBike, it)
        }
        renderState.setBikeStations(markers, bikeshareVisible)
    }

    fun clearBikeStations() = renderState.clearBikeStations()

    // ----- Generic markers -----

    fun addMarker(latitude: Double, longitude: Double, hue: Float?): Int =
        renderState.addMarker(GeoPoint(latitude, longitude), hue)

    fun removeMarker(id: Int) = renderState.removeMarker(id)

    companion object {
        private const val FUZZY_MAX_STOP_COUNT = 200
    }
}
