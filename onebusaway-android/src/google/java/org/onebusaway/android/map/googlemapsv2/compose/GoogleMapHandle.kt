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

import com.google.android.gms.maps.GoogleMap
import org.onebusaway.android.map.compose.ObaMapHandle

/**
 * The Google flavor's [ObaMapHandle]: carries the ready [GoogleMap] from [GoogleComposeAdapter] back
 * to `GoogleMapHost`, which downcasts to pull the raw map out for styling/camera/location setup.
 */
class GoogleMapHandle(val map: GoogleMap) : ObaMapHandle
