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
package org.onebusaway.android.ui.nightlight

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.onebusaway.android.ui.HomeActivity
import org.onebusaway.android.ui.nav.NavRoutes

/**
 * Launches the night-light flashing screen.
 *
 * The screen is a NavHost destination ([NightLightRoute]) hosted by HomeActivity; this is no longer an
 * Activity but a launcher facade. The frozen `NightLightActivity` component name is kept alive as an
 * activity-alias → HomeActivity (for old pinned launcher shortcuts). This [start] is the standalone-host
 * fallback (it re-enters HomeActivity with the route via [HomeActivity.navIntent]); in-app callers that
 * already hold a NavController navigate it directly instead.
 */
object NightLightLauncher {

    fun start(context: Context) {
        val intent = HomeActivity.navIntent(context, NavRoutes.NIGHT_LIGHT)
        // startActivity from a non-Activity context (e.g. a service/receiver) requires NEW_TASK; add it
        // only then so an Activity caller keeps the normal same-task behavior.
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
