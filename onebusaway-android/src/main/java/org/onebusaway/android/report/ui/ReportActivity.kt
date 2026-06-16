/*
 * Copyright (C) 2014 University of South Florida (sjbarbeau@gmail.com),
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

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.firebase.analytics.FirebaseAnalytics
import org.onebusaway.android.BuildConfig
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.app.di.LocationEntryPoint
import org.onebusaway.android.app.di.RegionEntryPoint
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.report.constants.ReportConstants
import org.onebusaway.android.report.ui.dialog.RegionValidateDialog
import org.onebusaway.android.ui.HomeActivity
import org.onebusaway.android.ui.compose.findActivity
import org.onebusaway.android.ui.nav.NavRoutes
import org.onebusaway.android.ui.report.types.ReportAction
import org.onebusaway.android.ui.report.types.ReportTypeListRoute
import org.onebusaway.android.util.BuildFlavorUtils
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.UIUtils

/**
 * Launcher facade for the report flow (Campaign C; former Activity). The screen is now the
 * [NavRoutes.REPORT] NavHost destination ([ReportDestination]); [start] builds a [HomeActivity]
 * intent carrying that route plus the stop/location context as [MapParams] / [LOCATION_STRING]
 * extras, which the destination + the infrastructure-issue destination read off the host intent.
 */
object ReportActivity {

    /** The location string forwarded to email reports (was on BaseReportActivity). */
    const val LOCATION_STRING = "locationString"

    @JvmStatic
    fun start(
        context: Context,
        focusId: String?,
        stopName: String?,
        stopCode: String?,
        lat: Double,
        lon: Double
    ) {
        context.startActivity(makeIntent(context, focusId, stopName, stopCode, lat, lon))
    }

    @JvmStatic
    fun start(context: Context, lat: Double, lon: Double) {
        context.startActivity(makeIntent(context, null, null, null, lat, lon))
    }

    @JvmStatic
    fun start(context: Context) {
        context.startActivity(makeIntent(context, null, null, null, 0.0, 0.0))
    }

    private fun makeIntent(
        context: Context,
        focusId: String?,
        stopName: String?,
        stopCode: String?,
        lat: Double,
        lon: Double
    ): Intent {
        val intent = HomeActivity.navIntent(context, NavRoutes.REPORT)
            .putExtra(MapParams.STOP_ID, focusId)
            .putExtra(MapParams.STOP_NAME, stopName)
            .putExtra(MapParams.STOP_CODE, stopCode)
            .putExtra(MapParams.CENTER_LAT, lat)
            .putExtra(MapParams.CENTER_LON, lon)
        LocationEntryPoint.get(context).lastKnownLocation()?.let {
            intent.putExtra(LOCATION_STRING, LocationUtils.printLocationDetails(it))
        }
        return intent
    }
}

/**
 * The report-type chooser NavHost destination (former ReportActivity content). On first composition
 * it shows the [RegionValidateDialog] (over the host activity's `supportFragmentManager`) when the
 * region needs validation; otherwise it hosts the [ReportTypeListRoute]. A tapped type navigates
 * in-NavHost to customer service or the infrastructure-issue screen, or sends app feedback.
 */
@Composable
fun ReportDestination(navController: NavController) {
    val activity = LocalContext.current.findActivity() as HomeActivity
    val firebaseAnalytics = remember { FirebaseAnalytics.getInstance(activity) }

    // Whether the region needs validating: false → show the type list straight away. Computed once.
    val needsValidation = remember { showValidateRegionDialog(activity) }

    // The validate dialog (hosted on the host activity's FragmentManager) flips this when confirmed.
    val regionValidated by activity.reportRegionValidated.collectAsStateWithLifecycle()
    val showTypeList = !needsValidation || regionValidated

    // Show the region-validate dialog once on enter (if needed); reset the latch on leave so a fresh
    // entry re-validates. RegionValidateDialog reaches the host (HomeActivity) via getActivity().
    DisposableEffect(Unit) {
        if (needsValidation && !regionValidated && !activity.supportFragmentManager.isStateSaved) {
            RegionValidateDialog().show(
                activity.supportFragmentManager, ReportConstants.TAG_REGION_VALIDATE_DIALOG
            )
        }
        onDispose { activity.reportRegionValidated.value = false }
    }

    if (showTypeList) {
        ReportTypeListRoute(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onActionSelected = { action ->
                onReportActionSelected(activity, firebaseAnalytics, navController, action)
            }
        )
    }
}

private fun onReportActionSelected(
    activity: HomeActivity,
    firebaseAnalytics: FirebaseAnalytics,
    navController: NavController,
    action: ReportAction
) {
    when (action) {
        ReportAction.CUSTOMER_SERVICE -> {
            navController.navigate(NavRoutes.CUSTOMER_SERVICE)
            reportEvent(
                activity, firebaseAnalytics,
                PlausibleAnalytics.REPORT_MORE_EVENT_URL, R.string.analytics_label_customer_service
            )
        }

        ReportAction.STOP_PROBLEM -> {
            navController.navigate(
                NavRoutes.infrastructureIssue(activity.getString(R.string.ri_selected_service_stop))
            )
            reportEvent(
                activity, firebaseAnalytics,
                PlausibleAnalytics.REPORT_STOP_PROBLEM_EVENT_URL, R.string.analytics_label_stop_problem
            )
        }

        ReportAction.ARRIVAL_PROBLEM -> {
            navController.navigate(
                NavRoutes.infrastructureIssue(activity.getString(R.string.ri_selected_service_trip))
            )
            reportEvent(
                activity, firebaseAnalytics,
                PlausibleAnalytics.REPORT_VEHICLE_PROBLEM_EVENT_URL, R.string.analytics_label_trip_problem
            )
        }

        ReportAction.APP_FEEDBACK -> sendAppFeedback(activity, firebaseAnalytics)
    }
}

private fun sendAppFeedback(activity: HomeActivity, firebaseAnalytics: FirebaseAnalytics) {
    val locationString = activity.intent.getStringExtra(ReportActivity.LOCATION_STRING)
    UIUtils.sendEmail(activity, activity.getString(R.string.ri_app_feedback_email), locationString)
    reportEvent(
        activity, firebaseAnalytics,
        PlausibleAnalytics.REPORT_MORE_EVENT_URL, R.string.analytics_label_app_feedback
    )
    if (locationString == null) {
        reportEvent(
            activity, firebaseAnalytics,
            PlausibleAnalytics.REPORT_VEHICLE_PROBLEM_EVENT_URL,
            R.string.analytics_label_app_feedback_without_location
        )
    }
}

private fun reportEvent(
    activity: HomeActivity,
    firebaseAnalytics: FirebaseAnalytics,
    eventUrl: String,
    labelRes: Int
) {
    ObaAnalytics.reportUiEvent(
        firebaseAnalytics,
        Application.get().plausibleInstance,
        eventUrl,
        activity.getString(R.string.analytics_problem),
        activity.getString(labelRes)
    )
}

/** Don't re-validate a region the user already confirmed (skipped for the single-region brand). */
private fun showValidateRegionDialog(activity: HomeActivity): Boolean {
    val currentRegion: ObaRegion = RegionEntryPoint.get(activity).region.value ?: return false
    val validatedRegionId = PreferenceUtils.getLong(ReportConstants.PREF_VALIDATED_REGION_ID, -1)
    val needsValidation = validatedRegionId == -1L || currentRegion.id != validatedRegionId
    if (!needsValidation) return false
    // Agency Y is locked to a single region, so there's nothing to validate.
    return !BuildConfig.FLAVOR_brand.equals(BuildFlavorUtils.AGENCYY_FLAVOR_BRAND, ignoreCase = true)
}
