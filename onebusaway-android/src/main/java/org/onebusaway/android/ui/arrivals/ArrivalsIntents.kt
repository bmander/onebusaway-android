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
package org.onebusaway.android.ui.arrivals

import android.content.Context
import android.content.Intent
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.ui.HomeActivity

/**
 * Intent extra keys and builders for the arrivals screens, relocated here from the deleted legacy
 * ArrivalsListFragment. Values are preserved verbatim so existing intents stay compatible.
 */
object ArrivalsIntents {

    const val STOP_NAME = ".StopName"

    const val STOP_DIRECTION = ".StopDir"

    const val STOP_ROUTES = ".StopRoutes"

    /**
     * Builds the HomeActivity intent the report flow expects: the stop focused on the map. Mirrors
     * the legacy ArrivalsListFragment.makeIntent.
     */
    fun makeHomeIntent(
        context: Context,
        focusId: String,
        stopName: String?,
        stopCode: String?,
        lat: Double,
        lon: Double
    ): Intent = Intent(context, HomeActivity::class.java).apply {
        putExtra(MapParams.STOP_ID, focusId)
        putExtra(MapParams.STOP_NAME, stopName)
        putExtra(MapParams.STOP_CODE, stopCode)
        putExtra(MapParams.CENTER_LAT, lat)
        putExtra(MapParams.CENTER_LON, lon)
    }
}
