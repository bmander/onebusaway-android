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
package org.onebusaway.android.io.client

import android.location.Location
import org.onebusaway.android.models.ObaStop
import org.onebusaway.android.util.LocationUtils

/**
 * Presents a [StopReference] DTO as the legacy [ObaStop] interface, so consumers that work through
 * the interface — the map stop overlay, search results — accept the modernized fetch unchanged
 * (the same one-DTO-implements-the-legacy-interface pattern as DtoRoute/DtoTripStatus).
 */
class DtoStop(private val ref: StopReference) : ObaStop {
    override val id: String get() = ref.id
    override val stopCode: String? get() = ref.code
    override val name: String? get() = ref.name
    override val location: Location get() = LocationUtils.makeLocation(ref.lat, ref.lon)
    override val latitude: Double get() = ref.lat
    override val longitude: Double get() = ref.lon
    override val direction: String? get() = ref.direction
    override val locationType: Int get() = ref.locationType
    override val routeIds: Array<String> get() = ref.routeIds.toTypedArray()
}
