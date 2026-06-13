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
package org.onebusaway.android.map.maplibre.compose

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.onebusaway.android.map.compose.ObaMapHandle

/**
 * The maplibre flavor's [ObaMapHandle]: carries the ready [MapLibreMap] (for camera/styling/rendering)
 * and its owning [MapView] (so `MapLibreMapHost` can still drive `onSaveInstanceState`) from
 * [MapLibreComposeAdapter] back to the host, which downcasts to pull them out.
 */
class MapLibreMapHandle(val mapView: MapView, val map: MapLibreMap) : ObaMapHandle
