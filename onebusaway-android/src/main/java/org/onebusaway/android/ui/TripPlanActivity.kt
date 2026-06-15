/*
 * Copyright (C) 2016-2017 Cambridge Systematics, Inc., University of South Florida,
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
package org.onebusaway.android.ui

import android.content.Context
import org.onebusaway.android.ui.nav.NavRoutes

/**
 * Thin launcher facade for the trip-plan flow (Campaign C). The screen itself is now a NavHost
 * destination ([org.onebusaway.android.ui.tripplan.TripPlanDestination]) hosted by [HomeActivity];
 * this object only translates the in-app launch into the [NavRoutes.TRIP_PLAN] route. All of the
 * former Activity's Android glue lives in the destination.
 */
object TripPlanActivity {

    @JvmStatic
    fun start(context: Context) {
        context.startActivity(HomeActivity.navIntent(context, NavRoutes.TRIP_PLAN))
    }
}
