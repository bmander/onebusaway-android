/*
 * Copyright (C) 2012-2013 Paul Watts (paulcwatts@gmail.com),
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
package org.onebusaway.android.ui.tripresults

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.directions.realtime.RealtimeService
import org.onebusaway.android.directions.util.OTPConstants
import org.onebusaway.android.map.MapMode
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.map.compose.NoOpObaMapCallbacks
import org.onebusaway.android.map.compose.ObaMap
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.opentripplanner.api.model.Itinerary

/**
 * Shows the result of a trip plan: the itinerary option cards and directions are Jetpack Compose
 * ([TripResultsHeader]/[TripResultsList], driven by [TripResultsViewModel]); the map is the
 * declarative [ObaMap] driven by a [MapViewModel] in directions mode, added into the directions frame
 * and shown/hidden by the list/map tab. Itineraries are seeded via the fragment arguments
 * ([OTPConstants.ITINERARIES] / [OTPConstants.SELECTED_ITINERARY] / [OTPConstants.SHOW_MAP]).
 */
class TripResultsFragment : Fragment() {

    /** Lets the host (the sliding panel in the legacy activity) anchor to the results container. */
    interface Listener {
        fun onResultViewCreated(container: View)
    }

    private val viewModel: TripResultsViewModel by viewModels {
        viewModelFactory {
            initializer {
                TripResultsViewModel(DefaultTripResultsRepository(requireContext().applicationContext))
            }
        }
    }

    private var listener: Listener? = null

    private val mapViewModel: MapViewModel by viewModels()
    private var currentItinerary: Itinerary? = null
    private var mapAdded = false
    // Re-frame the itinerary on the next camera idle (set when the itinerary or the shown state changes).
    private var needsFraming = false

    private lateinit var directionsFrame: View
    private lateinit var mapFrame: View
    private lateinit var listComposeView: ComposeView

    private var itineraries: List<Itinerary> = emptyList()
    private var showingMap = false

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun isMapShowing(): Boolean = showingMap

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_trip_plan_results, container, false)
        directionsFrame = view.findViewById(R.id.directionsFrame)
        mapFrame = view.findViewById(R.id.mapFragment)
        listComposeView = view.findViewById<ComposeView>(R.id.results_list_compose).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ObaTheme {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    TripResultsList(state)
                }
            }
        }
        view.findViewById<ComposeView>(R.id.results_header_compose).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ObaTheme {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    TripResultsHeader(
                        state = state,
                        onSelectOption = viewModel::selectOption,
                        onTabSelected = ::setMapShown
                    )
                }
            }
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindResults()
        observeSelection()
        observeMapReady()
        listener?.onResultViewCreated(directionsFrame)
    }

    /**
     * Frames the itinerary once the map is ready + settled. The map view model publishes its camera on
     * each idle, so the first non-null camera while the map is shown is a reliable "map ready" signal —
     * the directions frame dispatched at setMode time is lost before the adapter subscribes.
     */
    private fun observeMapReady() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mapViewModel.camera.collect { camera ->
                    if (camera != null && needsFraming && showingMap) {
                        needsFraming = false
                        mapViewModel.frameDirections()
                    }
                }
            }
        }
    }

    /** Re-binds when the host updates the itineraries on an existing fragment instance. */
    fun displayNewResults() {
        if (view != null) bindResults()
    }

    private fun bindResults() {
        val args = requireArguments()
        itineraries = readItineraries(args)
        val rank = args.getInt(OTPConstants.SELECTED_ITINERARY)
        showingMap = args.getBoolean(OTPConstants.SHOW_MAP)

        viewModel.setItineraries(itineraries, rank, showingMap)
        setItinerary(itineraries.getOrNull(rank))
        setMapShown(showingMap)
        maybeStartRealtimeUpdates()
    }

    /** Observes the selected option so the map follows the selection. */
    private fun observeSelection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedItinerary.collect { (index, itinerary) ->
                    requireArguments().putInt(OTPConstants.SELECTED_ITINERARY, index)
                    setItinerary(itinerary)
                    maybeStartRealtimeUpdates()
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun readItineraries(args: Bundle): List<Itinerary> =
        (args.getSerializable(OTPConstants.ITINERARIES) as? List<Itinerary>).orEmpty()

    /** Shows [itinerary] on the map (directions mode); re-frames on the next camera idle. */
    private fun setItinerary(itinerary: Itinerary?) {
        itinerary ?: return
        currentItinerary = itinerary
        needsFraming = true
        mapViewModel.setMode(MapMode.Directions(itinerary))
    }

    /** Adds the declarative map into the directions frame once (deferred until the map tab is shown). */
    private fun ensureMapAdded() {
        if (mapAdded) {
            return
        }
        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ObaMap(
                    renderState = mapViewModel.renderState,
                    callbacks = NoOpObaMapCallbacks,
                    modifier = Modifier.fillMaxSize(),
                    mapViewModel = mapViewModel,
                )
            }
        }
        (mapFrame as ViewGroup).addView(composeView)
        mapAdded = true
    }

    /** Toggles between the directions list and the map frame. */
    private fun setMapShown(show: Boolean) {
        showingMap = show
        requireArguments().putBoolean(OTPConstants.SHOW_MAP, show)
        viewModel.toggleMap(show)
        if (show) {
            listComposeView.visibility = View.GONE
            mapFrame.visibility = View.VISIBLE
            ensureMapAdded()
            // Re-frame the itinerary once the map settles (mirrors the legacy zoom-to-itinerary on show).
            needsFraming = true
        } else {
            mapFrame.visibility = View.GONE
            listComposeView.visibility = View.VISIBLE
        }
    }

    /**
     * Starts the background trip-update poller for the selected itinerary when the user has
     * trip-update notifications enabled — ported verbatim from the legacy results fragment.
     */
    private fun maybeStartRealtimeUpdates() {
        val activity = activity ?: return
        val context = Application.get().applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(Application.CHANNEL_TRIP_PLAN_UPDATES_ID)
            if (channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE) {
                RealtimeService.start(activity, requireArguments())
            }
        } else if (Application.getPrefs()
                .getBoolean(getString(R.string.preference_key_trip_plan_notifications), true)
        ) {
            RealtimeService.start(activity, requireArguments())
        }
    }
}
