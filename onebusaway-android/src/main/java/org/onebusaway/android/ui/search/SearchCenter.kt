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
package org.onebusaway.android.ui.search

import android.content.Context
import android.location.Location
import org.onebusaway.android.app.Application
import org.onebusaway.android.util.LocationUtils

/**
 * The location to center route/stop searches on: the device's last known location, falling
 * back to the current region's center, or null when neither is available.
 */
internal fun searchCenter(context: Context): Location? =
    Application.getLastKnownLocation(context, null) ?: LocationUtils.getDefaultSearchCenter()
