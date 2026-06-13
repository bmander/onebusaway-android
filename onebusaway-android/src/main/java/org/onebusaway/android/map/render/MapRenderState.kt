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

/** A geographic point, flavor-neutral (carries no Google/maplibre `LatLng` dependency). */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * One route/itinerary polyline: a colored, ordered list of points. The directional-arrow stamp is a
 * rendering detail added by the flavor renderer, so it isn't part of the state.
 */
data class RoutePolyline(val color: Int, val points: List<GeoPoint>)

/** Immutable snapshot of everything the map should render. Grows one overlay per phase. */
data class MapRenderSnapshot(
    val routePolylines: List<RoutePolyline> = emptyList(),
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
}
