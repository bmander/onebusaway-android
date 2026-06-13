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
package org.onebusaway.android.map.compose

/**
 * An opaque handle to a flavor's ready map, handed from the [ObaComposeMapAdapter] back to its host
 * via [ObaMapReadyListener]. It carries no neutral map API on purpose: the host owns its raw map
 * (styling, camera, location, rendering) and downcasts this to its flavor handle (e.g.
 * `GoogleMapHandle` / `MapLibreMapHandle`) to pull the SDK map out. The downcast is always safe
 * because a flavor host only ever pairs with its own flavor's adapter.
 */
interface ObaMapHandle

/** Notified once, after the flavor map is ready, with the [ObaMapHandle] the host downcasts. */
fun interface ObaMapReadyListener {
    fun onMapReady(handle: ObaMapHandle)
}
