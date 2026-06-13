/*
 * Copyright (C) 2011-2014 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com), and individual contributors.
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
package org.onebusaway.android.map.bike

import android.os.Bundle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.onebusaway.android.app.Application
import org.onebusaway.android.map.BaseMapController
import org.onebusaway.android.map.MapModeController
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.util.LayerUtils
import org.opentripplanner.api.model.Itinerary
import org.opentripplanner.api.model.VertexType
import org.opentripplanner.routing.core.TraverseMode

/**
 * Loads and displays bike rental stations on the map. The old `BikeStationLoader` `AsyncTaskLoader`
 * + `BikeLoaderCallbacks` are replaced by [BikeStationsRepository] + a coroutine on the base
 * [scope]; the directions-mode station filter is preserved.
 */
class BikeshareMapController(callback: MapModeController.Callback) : BaseMapController(callback) {

    private val repository: BikeStationsRepository =
        DefaultBikeStationsRepository(mCallback.activity.applicationContext)

    private var selectedBikeStationIds: List<String>? = null

    private var mapMode: String? = null

    private var loadJob: Job? = null

    init {
        updateData()
    }

    fun showBikes(showBikes: Boolean) {
        if (!showBikes) {
            mCallback.clearBikeStations()
            loadJob?.cancel()
            loadJob = null
            return
        }
        val mode = mapMode ?: return
        // Bike stations load unless we're in directions mode with no itinerary stations to show.
        // (The legacy code's `ids != null || ids.size() > 0` NPEs on a null ids in directions
        // mode; ids is always set there in practice, so we just gate on non-null.)
        if (mode == MapParams.MODE_DIRECTIONS && selectedBikeStationIds == null) {
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val stations =
                repository.getStations(mCallback.southWest, mCallback.northEast).getOrNull()
                    ?: return@launch
            filterStations(stations, selectedBikeStationIds)?.let {
                mCallback.showBikeStations(it)
            }
        }
    }

    override fun getMode(): String? = mapMode

    fun setMode(mode: String?) {
        mapMode = mode
    }

    override fun onHidden(hidden: Boolean) {}

    override fun updateData() {
        if (!Application.isBikeshareEnabled()) {
            return
        }
        val mode = mapMode ?: return
        val ids = selectedBikeStationIds
        if (mode == MapParams.MODE_DIRECTIONS && ids != null && ids.isNotEmpty()) {
            showBikes(true)
        } else {
            showBikes(LayerUtils.isBikeshareLayerVisible())
        }
    }

    override fun setState(args: Bundle?) {
        // If the controller is being called when the map is displaying directions, get the bike
        // stations that are part of the directions to display only them.
        val itinerary = args?.getSerializable(MapParams.ITINERARY) as? Itinerary
        if (itinerary != null) {
            selectedBikeStationIds = getBikeStationIdsFromItinerary(itinerary)
        }

        // Do not call super: the map zoom and positioning are already handled by the paired
        // controller (the bike controller is used together with another controller).
    }

    private fun getBikeStationIdsFromItinerary(itinerary: Itinerary): List<String> {
        val bikeStationIds = ArrayList<String>()
        for (leg in itinerary.legs) {
            if (TraverseMode.BICYCLE.toString() == leg.mode) {
                if (VertexType.BIKESHARE == leg.from.vertexType) {
                    bikeStationIds.add(leg.from.bikeShareId)
                }
                if (VertexType.BIKESHARE == leg.to.vertexType) {
                    bikeStationIds.add(leg.to.bikeShareId)
                }
            }
        }
        return bikeStationIds
    }
}
