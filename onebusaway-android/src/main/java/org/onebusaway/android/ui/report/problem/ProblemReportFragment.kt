/*
 * Copyright (C) 2012 Paul Watts (paulcwatts@gmail.com),
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
package org.onebusaway.android.ui.report.problem

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.io.elements.ObaArrivalInfo
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.report.ui.ReportProblemFragmentCallback
import org.onebusaway.android.ui.compose.composeFragmentView
import org.onebusaway.android.util.UIUtils

/**
 * Compose host for the stop/trip problem forms, replacing the AsyncTaskLoader-backed
 * ReportStopProblemFragment / ReportTripProblemFragment. Form state lives in
 * [ProblemReportViewModel]; this fragment owns the "send" menu, the user's location, analytics,
 * and the host [ReportProblemFragmentCallback].
 */
class ProblemReportFragment : Fragment(), MenuProvider {

    private val viewModel: ProblemReportViewModel by viewModels {
        viewModelFactory { initializer { createViewModel() } }
    }

    private var callback: ReportProblemFragmentCallback? = null

    private lateinit var firebaseAnalytics: FirebaseAnalytics

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? ReportProblemFragmentCallback
            ?: throw ClassCastException(
                "ReportProblemFragmentCallback should be implemented in parent activity"
            )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        firebaseAnalytics = FirebaseAnalytics.getInstance(requireContext())
        return composeFragmentView(inflater) { ProblemReportRoute(viewModel) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.submitState.collect(::onSubmitState)
            }
        }
    }

    private fun onSubmitState(state: SubmitState) {
        when (state) {
            SubmitState.Sent -> {
                callback?.onReportSent()
                viewModel.onSubmitResultHandled()
            }

            SubmitState.Error -> {
                Toast.makeText(requireContext(), R.string.report_problem_error, Toast.LENGTH_LONG)
                    .show()
                viewModel.onSubmitResultHandled()
            }

            else -> Unit
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.report_problem_options, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.report_problem_send) {
            onSendClicked()
            return true
        }
        return false
    }

    private fun onSendClicked() {
        hideKeyboard()
        val form = viewModel.formState.value
        if (!form.canSubmit) {
            Toast.makeText(
                requireContext(), R.string.report_problem_invalid_argument, Toast.LENGTH_LONG
            ).show()
            return
        }
        reportAnalytics(form.kind)
        viewModel.submit(Application.getLastKnownLocation(requireContext(), null))
    }

    private fun reportAnalytics(kind: ProblemKind) {
        val isTrip = kind == ProblemKind.TRIP
        ObaAnalytics.reportUiEvent(
            firebaseAnalytics,
            Application.get().plausibleInstance,
            if (isTrip) {
                PlausibleAnalytics.REPORT_VEHICLE_PROBLEM_EVENT_URL
            } else {
                PlausibleAnalytics.REPORT_STOP_PROBLEM_EVENT_URL
            },
            getString(R.string.analytics_problem),
            getString(
                if (isTrip) {
                    R.string.analytics_label_report_trip_problem
                } else {
                    R.string.analytics_label_report_stop_problem
                }
            )
        )
    }

    private fun hideKeyboard() {
        val imm = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)
    }

    private fun createViewModel(): ProblemReportViewModel {
        val args = requireArguments()
        val repository = DefaultProblemReportRepository(requireContext().applicationContext)
        return if (args.containsKey(ARG_TRIP_ID)) {
            ProblemReportViewModel(
                params = ProblemParams.Trip(
                    tripId = args.getString(ARG_TRIP_ID)!!,
                    stopId = args.getString(ARG_STOP_ID),
                    vehicleId = args.getString(ARG_VEHICLE_ID),
                    serviceDate = args.getLong(ARG_SERVICE_DATE)
                ),
                codes = ProblemCodes.trip(
                    resources.getStringArray(R.array.report_trip_problem_code_bus).toList()
                ),
                headsign = args.getString(ARG_HEADSIGN),
                repository = repository
            )
        } else {
            ProblemReportViewModel(
                params = ProblemParams.Stop(args.getString(ARG_STOP_ID)!!),
                codes = ProblemCodes.stop(
                    resources.getStringArray(R.array.report_stop_problem_code).toList()
                ),
                headsign = null,
                repository = repository
            )
        }
    }

    companion object {

        /** Fragment tags; kept distinct so the container can remove either form independently. */
        const val STOP_TAG = "ReportStopProblemFragment"
        const val TRIP_TAG = "ReportTripProblemFragment"

        private const val ARG_STOP_ID = "stopId"
        private const val ARG_TRIP_ID = "tripId"
        private const val ARG_VEHICLE_ID = "vehicleId"
        private const val ARG_SERVICE_DATE = "serviceDate"
        private const val ARG_HEADSIGN = "headsign"

        @JvmStatic
        fun showStop(activity: AppCompatActivity, stop: ObaStop, containerViewId: Int) {
            // We want the actual stop name here, not the stop-name map.
            show(activity, Bundle().apply { putString(ARG_STOP_ID, stop.id) }, STOP_TAG, containerViewId)
        }

        @JvmStatic
        fun showTrip(activity: AppCompatActivity, arrival: ObaArrivalInfo, containerViewId: Int) {
            val args = Bundle().apply {
                putString(ARG_TRIP_ID, arrival.tripId)
                putString(ARG_STOP_ID, arrival.stopId)
                putString(ARG_VEHICLE_ID, arrival.vehicleId)
                putLong(ARG_SERVICE_DATE, arrival.serviceDate)
                putString(ARG_HEADSIGN, UIUtils.formatDisplayText(arrival.headsign))
            }
            show(activity, args, TRIP_TAG, containerViewId)
        }

        private fun show(
            activity: AppCompatActivity,
            args: Bundle,
            tag: String,
            containerViewId: Int
        ) {
            val fragment = ProblemReportFragment().apply { arguments = args }
            try {
                activity.supportFragmentManager.beginTransaction()
                    .replace(containerViewId, fragment, tag)
                    .addToBackStack(null)
                    .commit()
            } catch (e: IllegalStateException) {
                Log.e(tag, "Cannot show ProblemReportFragment after onSaveInstanceState", e)
            }
        }
    }
}
