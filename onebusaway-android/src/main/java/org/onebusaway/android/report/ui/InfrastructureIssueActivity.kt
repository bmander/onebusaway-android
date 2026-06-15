/*
 * Copyright (C) 2014-2015 University of South Florida (sjbarbeau@gmail.com),
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
package org.onebusaway.android.report.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.onebusaway.android.io.elements.ObaArrivalInfo
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.ui.HomeActivity
import org.onebusaway.android.ui.nav.NavRoutes

/**
 * Launcher facade for the infrastructure-issue (stop/trip problem) screen (Campaign C; former
 * Activity). The screen is now the [NavRoutes.INFRASTRUCTURE_ISSUE] NavHost destination
 * ([InfrastructureIssueDestination]); [startWithService] builds a [HomeActivity] intent carrying that
 * route (with the selected-service keyword as the nav-arg) plus the stop/location context, the opaque
 * [ObaArrivalInfo] (TRIP_INFO), and agency/block ids as host-intent extras — exactly the values the
 * destination's hand-built ViewModel factory reads. Reached from [ReportDestination] (in-NavHost
 * navigate) and from the arrivals "report problem" actions (this facade → HomeActivity → translator).
 */
object InfrastructureIssueActivity {

    @JvmStatic
    @JvmOverloads
    fun startWithService(
        activity: Activity,
        intent: Intent,
        serviceKeyword: String,
        arrivalInfo: ObaArrivalInfo? = null,
        agencyName: String? = null,
        blockId: String? = null
    ) {
        val target = makeIntent(activity, intent, serviceKeyword).apply {
            arrivalInfo?.let { putExtra(EXTRA_TRIP_INFO, it) }
            putExtra(EXTRA_AGENCY_NAME, agencyName)
            putExtra(EXTRA_BLOCK_ID, blockId)
        }
        activity.startActivity(target)
    }

    /**
     * Builds the [HomeActivity] intent for the infrastructure-issue route, copying the stop/location
     * context from [source]. The selected-service keyword rides on the route nav-arg (so it survives
     * process death via the back-stack args); the rest stay as host-intent extras.
     */
    @JvmStatic
    private fun makeIntent(context: Context, source: Intent, serviceKeyword: String?): Intent =
        HomeActivity.navIntent(context, NavRoutes.infrastructureIssue(serviceKeyword)).apply {
            putExtra(MapParams.STOP_ID, source.getStringExtra(MapParams.STOP_ID))
            putExtra(MapParams.STOP_NAME, source.getStringExtra(MapParams.STOP_NAME))
            putExtra(MapParams.STOP_CODE, source.getStringExtra(MapParams.STOP_CODE))
            putExtra(MapParams.CENTER_LAT, source.getDoubleExtra(MapParams.CENTER_LAT, 0.0))
            putExtra(MapParams.CENTER_LON, source.getDoubleExtra(MapParams.CENTER_LON, 0.0))
        }
}
