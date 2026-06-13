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

import org.opentripplanner.api.model.Itinerary

/**
 * What the map is currently showing. The single input that replaces the old `MapModeController` set
 * (StopMapController / RouteMapController / DirectionsMapController): instead of swapping controller
 * objects, a consumer calls [MapViewModel.setMode] and the view model (re)launches the matching
 * reactive loaders on [androidx.lifecycle.viewModelScope].
 *
 * The bikeshare layer is *not* a mode here — it overlays every mode, so it's driven separately
 * ([MapViewModel.setBikeshareLayerVisible]); [Directions] only carries the itinerary's bike-station
 * filter forward.
 */
sealed interface MapMode {

    /** The legacy `MapParams.MODE_*` string, for parity with code that still speaks in mode strings. */
    val modeId: String

    /** Nearby stops in the current viewport — loads + accumulates stops as the camera pans/zooms. */
    object Stop : MapMode {
        override val modeId: String = MapParams.MODE_STOP
    }

    /**
     * A single route's shape + stops + polled real-time vehicles. [zoomToRoute] frames the shape once
     * after it loads; [zoomIncludeClosestVehicle] expands the frame to the nearest vehicle on the
     * first vehicle load (both consumed once, matching the legacy one-shot flags).
     */
    data class Route(
        val routeId: String,
        val zoomToRoute: Boolean = false,
        val zoomIncludeClosestVehicle: Boolean = false,
    ) : MapMode {
        override val modeId: String = MapParams.MODE_ROUTE
    }

    /** A trip-plan itinerary: each leg's polyline + start/end pins, framed to the whole itinerary. */
    data class Directions(val itinerary: Itinerary) : MapMode {
        override val modeId: String = MapParams.MODE_DIRECTIONS
    }
}

/**
 * A one-shot map event that needs an Activity to carry out (so it can't be plain state). The view
 * model emits these on [MapViewModel.effects]; the hosting Activity collects them while STARTED and
 * shows the corresponding UI. Grows one case per phase of the host dissolution; for now the only
 * loader-produced effect is the out-of-range prompt the stop loader used to raise via
 * `MapModeController.Callback.notifyOutOfRange`.
 */
sealed interface MapEffect {

    /** The viewport (or the device) is outside the current region — prompt the user to switch regions. */
    object OutOfRange : MapEffect
}
