/*
 * Copyright (C) 2011-2014 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com), and individual contributors.
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

import android.location.Location
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaStop
import org.opentripplanner.routing.bike_rental.BikeRentalStation

/**
 * Notified when map focus changes (a stop or bike station is selected/cleared). Implemented by the
 * screen that owns the map and registered via [ObaMapHost.setOnFocusChangeListener] /
 * [ObaMapFragment.setOnFocusChangeListener]. Lifted out of [ObaMapFragment] so the fragment-less
 * host can use it without referencing a fragment.
 */
interface OnFocusChangedListener {

    /**
     * Called when a stop on the map is clicked (i.e., tapped), which sets focus to a stop,
     * or when the user taps on an area away from the map for the first time after a stop
     * is already selected, which removes focus.
     *
     * @param stop     the ObaStop that obtained focus, or null if no stop is in focus
     * @param routes   a HashMap of all route display names that serve this stop - key is routeId
     * @param location the user touch location on the map
     */
    fun onFocusChanged(stop: ObaStop?, routes: HashMap<String, ObaRoute>?, location: Location?)

    fun onFocusChanged(bikeRentalStation: BikeRentalStation?)
}
