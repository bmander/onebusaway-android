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

import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaStop

/**
 * A host-level map command. These are the operations HomeActivity used to perform imperatively in its
 * `viewModel.events` relay (`mapHost.setMapMode(...)`, `setFocusStop(...)`, `setMapCenter(...)`, the
 * `ObaRegionsTask.Callback` downcast). Now the view models dispatch one of these to
 * [MapViewModel.mapCommands] and [ObaMapHost.executeMapCommand] carries it out against the controllers
 * — so HomeActivity no longer translates events into map calls.
 *
 * Distinct from `render.CameraCommand`: those are low-level camera moves applied in the Compose layer;
 * these are higher-level controller/focus/mode operations the host owns (and some, like a recenter,
 * fan out into a CameraCommand inside the host).
 */
sealed interface MapCommand {

    /** Recenter (animated) on the focused stop, e.g. after the arrivals sheet expands over it. */
    data class Recenter(val lat: Double, val lon: Double) : MapCommand

    /**
     * Center on + focus a restored/deep-linked stop once its arrivals load. Carries the io/elements
     * payload (the view model is decoupled from those types; the activity supplies them). [overlayExpanded]
     * is the route-header-offset flag (true when the sheet settled expanded).
     */
    data class FocusStop(
        val stop: ObaStop,
        val routes: List<ObaRoute>?,
        val overlayExpanded: Boolean,
    ) : MapCommand

    /** Clear the focused stop (back-press from a peeking sheet; the sheet then hides). */
    object ClearFocus : MapCommand

    /** Switch the map to route mode for [routeId] ("show route/vehicles on map"). */
    data class ShowRoute(val routeId: String) : MapCommand

    /** Leave route mode back to stop mode, preserving the current camera (the route header's cancel). */
    object ExitRouteMode : MapCommand

    /** The region resolved — let the host re-zoom (the old `ObaRegionsTask.Callback.onRegionTaskFinished`). */
    data class RegionChanged(val changed: Boolean) : MapCommand
}
