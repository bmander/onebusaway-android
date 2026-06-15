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
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.onebusaway.android.BuildConfig
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.report.constants.ReportConstants
import org.onebusaway.android.report.ui.dialog.RegionValidateDialog
import org.onebusaway.android.ui.HomeActivity
import org.onebusaway.android.ui.SettingsActivity
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.nav.NavRoutes
import org.onebusaway.android.ui.report.types.ReportAction
import org.onebusaway.android.ui.report.types.ReportTypeListRoute
import org.onebusaway.android.ui.report.types.ReportTypeListViewModel
import org.onebusaway.android.util.BuildFlavorUtils
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.UIUtils

/**
 * Entry point for the report flow: a Compose host showing the "Send feedback" type list (gated by
 * region/Open311 in [ReportTypeListViewModel]) and routing a tapped type to the customer-service,
 * infrastructure, or app-feedback path. The [RegionValidateDialog] is still shown over it on first
 * launch; extends [BaseReportActivity] so a submitted report closes the whole stack.
 */
@AndroidEntryPoint
class ReportActivity : BaseReportActivity() {

    private var showTypeList by mutableStateOf(false)

    private lateinit var firebaseAnalytics: FirebaseAnalytics

    private val viewModel: ReportTypeListViewModel by viewModels()

    @Inject
    lateinit var regionRepository: RegionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

        if (savedInstanceState == null) {
            if (showValidateRegionDialog()) {
                RegionValidateDialog().show(
                    supportFragmentManager, ReportConstants.TAG_REGION_VALIDATE_DIALOG
                )
            } else {
                showTypeList = true
            }
        } else {
            showTypeList = true
        }

        setContent {
            ObaTheme {
                if (showTypeList) {
                    ReportTypeListRoute(
                        viewModel = viewModel,
                        onBack = { finish() },
                        onActionSelected = ::onActionSelected
                    )
                }
            }
        }
    }

    private fun onActionSelected(action: ReportAction) {
        when (action) {
            ReportAction.CUSTOMER_SERVICE -> {
                CustomerServiceActivity.start(this, intent)
                reportEvent(PlausibleAnalytics.REPORT_MORE_EVENT_URL, R.string.analytics_label_customer_service)
            }

            ReportAction.STOP_PROBLEM -> {
                InfrastructureIssueActivity.startWithService(
                    this, intent, getString(R.string.ri_selected_service_stop)
                )
                reportEvent(PlausibleAnalytics.REPORT_STOP_PROBLEM_EVENT_URL, R.string.analytics_label_stop_problem)
            }

            ReportAction.ARRIVAL_PROBLEM -> {
                InfrastructureIssueActivity.startWithService(
                    this, intent, getString(R.string.ri_selected_service_trip)
                )
                reportEvent(PlausibleAnalytics.REPORT_VEHICLE_PROBLEM_EVENT_URL, R.string.analytics_label_trip_problem)
            }

            ReportAction.APP_FEEDBACK -> sendAppFeedback()
        }
    }

    private fun sendAppFeedback() {
        val locationString = intent.getStringExtra(LOCATION_STRING)
        UIUtils.sendEmail(this, getString(R.string.ri_app_feedback_email), locationString)
        reportEvent(PlausibleAnalytics.REPORT_MORE_EVENT_URL, R.string.analytics_label_app_feedback)
        if (locationString == null) {
            reportEvent(
                PlausibleAnalytics.REPORT_VEHICLE_PROBLEM_EVENT_URL,
                R.string.analytics_label_app_feedback_without_location
            )
        }
    }

    private fun reportEvent(eventUrl: String, labelRes: Int) {
        ObaAnalytics.reportUiEvent(
            firebaseAnalytics,
            Application.get().plausibleInstance,
            eventUrl,
            getString(R.string.analytics_problem),
            getString(labelRes)
        )
    }

    /** Don't re-validate a region the user already confirmed (skipped for the single-region brand). */
    private fun showValidateRegionDialog(): Boolean {
        val currentRegion: ObaRegion = regionRepository.region.value ?: return false
        val validatedRegionId = PreferenceUtils.getLong(ReportConstants.PREF_VALIDATED_REGION_ID, -1)
        val needsValidation = validatedRegionId == -1L || currentRegion.id != validatedRegionId
        if (!needsValidation) return false
        // Agency Y is locked to a single region, so there's nothing to validate.
        return !BuildConfig.FLAVOR_brand.equals(BuildFlavorUtils.AGENCYY_FLAVOR_BRAND, ignoreCase = true)
    }

    /** Called by [RegionValidateDialog] when the user confirms their region. */
    fun createIssueTypeListFragment() {
        showTypeList = true
    }

    /** Called by [RegionValidateDialog] when the user picks a different region. */
    fun createPreferencesActivity() {
        startActivity(
            HomeActivity.navIntent(this, NavRoutes.SETTINGS)
                .putExtra(SettingsActivity.SHOW_CHECK_REGION_DIALOG, true)
        )
    }

    companion object {

        @JvmStatic
        fun start(
            context: Context,
            focusId: String?,
            stopName: String?,
            stopCode: String?,
            lat: Double,
            lon: Double
        ) {
            context.startActivity(
                makeIntent(context, focusId, stopName, stopCode, lat, lon)
            )
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
            val intent = Intent(context, ReportActivity::class.java)
                .putExtra(MapParams.STOP_ID, focusId)
                .putExtra(MapParams.STOP_NAME, stopName)
                .putExtra(MapParams.STOP_CODE, stopCode)
                .putExtra(MapParams.CENTER_LAT, lat)
                .putExtra(MapParams.CENTER_LON, lon)
            Application.getLastKnownLocation(context)?.let {
                intent.putExtra(LOCATION_STRING, LocationUtils.printLocationDetails(it))
            }
            return intent
        }
    }
}
