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
package org.onebusaway.android.ui.tripinfo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.onebusaway.android.ui.TripInfoActivity

/**
 * State holder for the trip-reminder editor: loads the merged reminder data once, tracks the form
 * edits (name + reminder lead time), and runs the save through [TripInfoRepository], reporting the
 * outcome on [events].
 */
@HiltViewModel
class TripInfoViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: TripInfoRepository,
) : ViewModel() {

    // Launch args arrive via SavedStateHandle (seeded from the hosting Activity's intent extras; the
    // trip/stop ids are normalized from the data URI into extras by TripInfoActivity), keyed by the
    // TripInfoActivity extras. tripUri is recomputed from tripId/stopId by TripInfoArgs.
    private val args = TripInfoArgs(
        tripId = savedState.get<String>(TripInfoActivity.TRIP_ID).orEmpty(),
        stopId = savedState.get<String>(TripInfoActivity.STOP_ID).orEmpty(),
        routeId = savedState.get<String>(TripInfoActivity.ROUTE_ID),
        routeName = savedState.get<String>(TripInfoActivity.ROUTE_NAME),
        stopName = savedState.get<String>(TripInfoActivity.STOP_NAME),
        headsign = savedState.get<String>(TripInfoActivity.HEADSIGN),
        departTime = savedState.get<Long>(TripInfoActivity.DEPARTURE_TIME) ?: 0,
        stopSequence = savedState.get<Int>(TripInfoActivity.STOP_SEQUENCE) ?: 0,
        serviceDate = savedState.get<Long>(TripInfoActivity.SERVICE_DATE) ?: 0,
        vehicleId = savedState.get<String>(TripInfoActivity.VEHICLE_ID),
    )

    private val _state = MutableStateFlow<TripInfoUiState>(TripInfoUiState.Loading)
    val state: StateFlow<TripInfoUiState> = _state.asStateFlow()

    private val _events = Channel<TripInfoEvent>(Channel.BUFFERED)
    val events: Flow<TripInfoEvent> = _events.receiveAsFlow()

    private var data: TripInfoData? = null

    init {
        viewModelScope.launch {
            val loaded = repository.load(args)
            data = loaded
            _state.value = TripInfoUiState.Content(
                stopName = loaded.stopNameText,
                routeName = loaded.routeText,
                headsign = loaded.headsignText,
                departureText = loaded.departureText,
                reminderOptions = loaded.reminderOptions,
                reminderSelection = REMINDER_MINUTES.indexOf(loaded.reminderMinutes)
                    .coerceIn(0, loaded.reminderOptions.size - 1),
                tripName = loaded.tripName,
                isNewTrip = loaded.isNewTrip
            )
        }
    }

    /** The route of this trip, once loaded — for the "Show route" action. */
    fun routeId(): String? = data?.routeId

    /** The stop's display name, once loaded — passed along to the arrivals screen. */
    fun stopName(): String? = data?.stopNameText

    fun setTripName(name: String) = updateContent { it.copy(tripName = name) }

    fun setReminderSelection(index: Int) = updateContent { it.copy(reminderSelection = index) }

    fun save() {
        val content = _state.value as? TripInfoUiState.Content ?: return
        val loaded = data ?: return
        if (content.isSaving) return
        updateContent { it.copy(isSaving = true) }
        viewModelScope.launch {
            val saved = repository.save(
                args, loaded, REMINDER_MINUTES[content.reminderSelection], content.tripName
            )
            updateContent { it.copy(isSaving = false) }
            _events.send(if (saved) TripInfoEvent.Saved else TripInfoEvent.SaveFailed)
        }
    }

    private inline fun updateContent(transform: (TripInfoUiState.Content) -> TripInfoUiState.Content) {
        _state.update { (it as? TripInfoUiState.Content)?.let(transform) ?: it }
    }
}
