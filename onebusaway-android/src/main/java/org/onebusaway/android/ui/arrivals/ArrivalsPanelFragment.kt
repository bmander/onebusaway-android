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
package org.onebusaway.android.ui.arrivals

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import org.onebusaway.android.io.request.ObaArrivalInfoResponse
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.ui.compose.composeFragmentView
import org.onebusaway.android.ui.createArrivalActionHandler

/**
 * Hosts the Compose arrivals [ArrivalsPanel] inside HomeActivity's map slide-up panel. A fresh
 * instance is created per focused stop (replacing the previous one), giving each stop its own
 * ViewModel + polling lifecycle. The host (HomeActivity) drives panel state in via
 * [setPanelCollapsed] and reads the list scroll position via [scrollableViewScrollPosition] for the
 * SlidingUpPanel drag coordination; the panel reports out through [Listener].
 */
class ArrivalsPanelFragment : Fragment() {

    /** Implemented by the host activity, which owns the map and the sliding panel. */
    interface Listener {
        /** A new load finished: recenter map, set focus marker, move FABs, fire tutorials. */
        fun onArrivalsLoaded(response: ObaArrivalInfoResponse)

        /** "Show vehicles on map": collapse the panel and drive the existing map to route mode. */
        fun onShowRouteOnMap(routeId: String)

        /** The header/chevron was tapped: toggle the panel between collapsed and anchored. */
        fun onToggleExpand()

        /** The preferred-arrival count / filter state changed: resize the collapsed peek. */
        fun onPreferredHeight(previewCount: Int, filtering: Boolean)

        /** The panel content view is ready: wire it as the SlidingUpPanel's scrollable view. */
        fun onPanelViewCreated(view: View)
    }

    private var listener: Listener? = null

    private val listState = LazyListState()

    private val panelCollapsed = mutableStateOf(true)

    private val stopId: String by lazy { requireArguments().getString(MapParams.STOP_ID).orEmpty() }

    private val initialTitle: String by lazy {
        requireArguments().getString(MapParams.STOP_NAME).orEmpty()
    }

    private val viewModel: ArrivalsViewModel by viewModels {
        viewModelFactory {
            initializer {
                ArrivalsViewModel(stopId, DefaultArrivalsRepository(requireContext().applicationContext))
            }
        }
    }

    private val actionHandler by lazy {
        createArrivalActionHandler(
            activity = requireActivity() as AppCompatActivity,
            viewModel = viewModel,
            currentContent = { viewModel.state.value as? ArrivalsUiState.Content },
            onShowRouteOnMap = { routeId -> listener?.onShowRouteOnMap(routeId) }
        )
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Driven by the host's panel-state callbacks; switches preview↔full list and the chevron. */
    fun setPanelCollapsed(collapsed: Boolean) {
        panelCollapsed.value = collapsed
    }

    /** The list scroll position for the SlidingUpPanel's ScrollableViewHelper (0 == at top). */
    fun scrollableViewScrollPosition(): Int =
        if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else 1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = composeFragmentView(inflater) {
        ArrivalsPanel(
            viewModel = viewModel,
            listState = listState,
            collapsed = panelCollapsed.value,
            initialTitle = initialTitle,
            handler = actionHandler,
            onToggleExpand = { listener?.onToggleExpand() },
            onPreferredHeight = { count, filtering -> listener?.onPreferredHeight(count, filtering) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listener?.onPanelViewCreated(view)
        // Forward each loaded response to the host (map recenter / FABs / tutorials)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.responses.collect { listener?.onArrivalsLoaded(it) }
            }
        }
    }

    override fun onDestroyView() {
        listener = null
        super.onDestroyView()
    }

    companion object {
        @JvmStatic
        fun newInstance(stopId: String, stopName: String?): ArrivalsPanelFragment =
            ArrivalsPanelFragment().apply {
                arguments = Bundle().apply {
                    putString(MapParams.STOP_ID, stopId)
                    putString(MapParams.STOP_NAME, stopName)
                }
            }
    }
}
