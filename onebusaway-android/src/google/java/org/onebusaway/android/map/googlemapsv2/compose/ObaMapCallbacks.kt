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

import com.google.android.gms.maps.model.LatLng
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.io.elements.ObaTripStatus
import org.opentripplanner.routing.bike_rental.BikeRentalStation

/**
 * Map interaction the host ([org.onebusaway.android.map.googlemapsv2.GoogleMapHost]) handles. Once
 * all markers are declarative, maps-compose owns the click dispatch, so these replace the imperative
 * `MapClickListeners` + the overlay focus listeners: a stop tap focuses the stop, a map tap clears
 * focus, a bike tap reports bike focus.
 */
interface ObaMapCallbacks {
    fun onStopClick(stop: ObaStop)

    fun onMapClick(latLng: LatLng)

    fun onBikeClick(station: BikeRentalStation)

    /** The vehicle info-window "more info" tap — the host navigates (e.g. to TripDetails). */
    fun onVehicleInfoWindowClick(status: ObaTripStatus)

    /** The bike info-window "more info" tap — the host navigates (e.g. the bikeshare deep link). */
    fun onBikeInfoWindowClick(station: BikeRentalStation)
}
