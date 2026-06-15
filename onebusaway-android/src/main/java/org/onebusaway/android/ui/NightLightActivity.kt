/*
 * Copyright 2013-2026 Colin McDonough, University of South Florida, Sean J. Barbeau,
 * Open Transit Software Foundation
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
package org.onebusaway.android.ui

import android.content.Context
import org.onebusaway.android.ui.nav.NavRoutes

/**
 * Launches the night-light flashing screen.
 *
 * Campaign C: the screen is a NavHost destination ([NightLightRoute]) hosted by [HomeActivity]; this
 * is no longer an Activity but a launcher facade. The frozen `NightLightActivity` component name is
 * kept alive as an activity-alias → HomeActivity (for old pinned launcher shortcuts, which HomeActivity's
 * translator maps to the [NavRoutes.NIGHT_LIGHT] route by component name); in-app callers build an
 * explicit [HomeActivity] intent carrying the route.
 */
object NightLightActivity {

    fun start(context: Context) {
        context.startActivity(HomeActivity.navIntent(context, NavRoutes.NIGHT_LIGHT))
    }
}
