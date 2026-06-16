/*
 * Copyright (C) 2010-2017 Brian Ferris (bdferris@onebusaway.org),
 * University of South Florida (sjbarbeau@gmail.com),
 * Microsoft Corporation
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
 * Launches the settings screen (preferences + the nested advanced screen).
 *
 * Campaign C: the settings screens are a NavHost destination hosted by [HomeActivity] (see
 * [SettingsRoute] / the [NavRoutes.SETTINGS] composable); this is no longer an Activity but a
 * launcher facade. [start] builds an explicit [HomeActivity] intent carrying the [NavRoutes.SETTINGS]
 * route, which HomeActivity's translator navigates to.
 *
 * The FQN `org.onebusaway.android.ui.SettingsActivity` is preserved so the few remaining Kotlin
 * callers compile with a minimal change.
 */
object SettingsActivity {

    /**
     * Extra (on the launching [HomeActivity] intent) requesting the settings destination show the
     * "check your region" dialog on first composition. Set by the report flow's region-validate
     * dialog when the user opts to change their region.
     */
    const val SHOW_CHECK_REGION_DIALOG = ".checkRegionDialog"

    @JvmStatic
    fun start(context: Context) {
        context.startActivity(HomeActivity.navIntent(context, NavRoutes.SETTINGS))
    }
}
