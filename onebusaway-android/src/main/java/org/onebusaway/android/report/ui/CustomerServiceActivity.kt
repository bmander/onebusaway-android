/*
 * Copyright (C) 2015 University of South Florida (sjbarbeau@gmail.com),
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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.analytics.FirebaseAnalytics
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.report.customerservice.AgencyContact
import org.onebusaway.android.ui.report.customerservice.CustomerServiceRoute
import org.onebusaway.android.ui.report.customerservice.CustomerServiceViewModel
import org.onebusaway.android.ui.report.customerservice.DefaultCustomerServiceRepository
import org.onebusaway.android.util.UIUtils

/**
 * Lists the region's transit agencies with email/web/phone contact options. Compose + MVVM host:
 * state lives in [CustomerServiceViewModel]; the Activity owns the platform contact intents and
 * analytics. Replaces the legacy AgenciesLoader-backed Activity.
 */
class CustomerServiceActivity : AppCompatActivity() {

    private val viewModel: CustomerServiceViewModel by viewModels {
        viewModelFactory {
            initializer { CustomerServiceViewModel(DefaultCustomerServiceRepository(applicationContext)) }
        }
    }

    private lateinit var firebaseAnalytics: FirebaseAnalytics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)
        setContent {
            ObaTheme {
                CustomerServiceRoute(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onEmail = ::emailAgency,
                    onWeb = ::openAgencyWeb,
                    onPhone = ::callAgency
                )
            }
        }
    }

    private fun emailAgency(agency: AgencyContact) {
        val email = agency.email ?: return
        val locationString = intent.getStringExtra(BaseReportActivity.LOCATION_STRING)
        UIUtils.sendEmail(this, email, locationString)
        reportContactEvent(agency.name, R.string.analytics_label_customer_service_email)
        if (locationString == null) {
            reportContactEvent(agency.name, R.string.analytics_label_customer_service_email_without_location)
        }
    }

    private fun openAgencyWeb(agency: AgencyContact) {
        val url = agency.url ?: return
        UIUtils.goToUrl(this, url)
        reportContactEvent(agency.name, R.string.analytics_label_customer_service_web)
    }

    private fun callAgency(agency: AgencyContact) {
        val phone = agency.phone ?: return
        UIUtils.goToPhoneDialer(this, "tel:$phone")
        reportContactEvent(agency.name, R.string.analytics_label_customer_service_phone)
    }

    private fun reportContactEvent(agencyName: String, labelRes: Int) {
        ObaAnalytics.reportUiEvent(
            firebaseAnalytics,
            Application.get().plausibleInstance,
            PlausibleAnalytics.REPORT_MORE_EVENT_URL,
            agencyName + "_" + getString(R.string.analytics_customer_service),
            getString(labelRes)
        )
    }

    companion object {

        /** Launches the screen, forwarding the optional location string used in email reports. */
        @JvmStatic
        fun start(context: Context, sourceIntent: Intent?) {
            val intent = Intent(context, CustomerServiceActivity::class.java)
            sourceIntent?.getStringExtra(BaseReportActivity.LOCATION_STRING)?.let {
                intent.putExtra(BaseReportActivity.LOCATION_STRING, it)
            }
            context.startActivity(intent)
        }
    }
}
