/*
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

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.onebusaway.android.io.elements.ObaArrivalInfo
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.ui.arrivals.DefaultArrivalsRepository
import org.onebusaway.android.ui.compose.composeFragmentView

/**
 * The Compose replacement for the legacy SimpleArrivalListFragment: hosts [SimpleArrivalsPicker] in
 * the report flow's container and reports the chosen arrival (plus its agency id and block id,
 * resolved from the response refs) back to [InfrastructureIssueActivity] via [Callback].
 */
class SimpleArrivalsPickerFragment : Fragment() {

    /** Same contract as the legacy fragment: agencyId/blockId resolved from the response refs. */
    interface Callback {
        fun onArrivalItemClicked(arrival: ObaArrivalInfo, agencyId: String?, blockId: String?)
    }

    private var callback: Callback? = null

    private val stopId: String by lazy { requireArguments().getString(MapParams.STOP_ID).orEmpty() }

    private val viewModel: ArrivalsViewModel by viewModels {
        viewModelFactory {
            initializer {
                ArrivalsViewModel(
                    stopId,
                    DefaultArrivalsRepository(requireContext().applicationContext),
                    ignorePersistedFilter = true
                )
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = composeFragmentView(inflater) {
        SimpleArrivalsPicker(viewModel) { arrival ->
            val info = arrival.info
            val refs = viewModel.lastResponse()?.refs
            callback?.onArrivalItemClicked(
                info,
                refs?.getRoute(info.routeId)?.agencyId,
                refs?.getTrip(info.tripId)?.blockId
            )
        }
    }

    override fun onDestroyView() {
        callback = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "SimpleArrivalsPicker"

        @JvmStatic
        fun show(activity: AppCompatActivity, containerViewId: Int, stop: ObaStop, callback: Callback) {
            val fragment = SimpleArrivalsPickerFragment().apply {
                arguments = Bundle().apply { putString(MapParams.STOP_ID, stop.id) }
                this.callback = callback
            }
            try {
                activity.supportFragmentManager.beginTransaction()
                    .replace(containerViewId, fragment, TAG)
                    .addToBackStack(null)
                    .commit()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Cannot show picker after onSaveInstanceState has been called")
            }
        }
    }
}
