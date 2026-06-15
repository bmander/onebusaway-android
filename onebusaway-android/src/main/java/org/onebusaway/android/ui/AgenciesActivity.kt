/*
 * Copyright (C) 2012-2013 Paul Watts (paulcwatts@gmail.com) and individual contributors.
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
 * Launches the agencies screen (the transit agencies supported in the current region).
 *
 * Campaign C: the agencies list is a NavHost destination hosted by [HomeActivity]; this is no longer
 * an Activity but a launcher facade. `start` builds an explicit [HomeActivity] intent carrying the
 * [NavRoutes.AGENCIES] route, which HomeActivity's translator navigates to. (Non-exported, launched
 * only in-app, so no activity-alias is needed.)
 */
object AgenciesActivity {

    @JvmStatic
    fun start(context: Context) {
        context.startActivity(HomeActivity.navIntent(context, NavRoutes.AGENCIES))
    }
}
