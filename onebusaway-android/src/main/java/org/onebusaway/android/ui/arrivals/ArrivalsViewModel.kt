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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the standalone arrivals screen. The 60-second polling loop lives in the screen
 * (driven by the activity lifecycle); this exposes [refresh] for it to call plus the user
 * actions. The current time window ([minutesAfter]) grows with "load more".
 */
class ArrivalsViewModel(
    private val stopId: String,
    private val repository: ArrivalsRepository
) : ViewModel() {

    private val _state = MutableStateFlow<ArrivalsUiState>(ArrivalsUiState.Loading)
    val state: StateFlow<ArrivalsUiState> = _state.asStateFlow()

    private var minutesAfter = DefaultArrivalsRepository.MINUTES_AFTER_DEFAULT

    private var routeFilter: Set<String> = emptySet()

    /** Wall-clock time of the last completed load, read by the screen's polling loop. */
    var lastResponseTimeMs: Long = 0L
        private set

    /**
     * Loads the arrivals once. Suspends until done so the screen's polling loop can measure the
     * 60s interval from completion. A failed refresh keeps any existing content (the repository
     * already returns the last good data as stale); it only surfaces [ArrivalsUiState.Error]
     * when there is nothing to show.
     */
    suspend fun refresh() {
        val result = repository.getArrivals(stopId, minutesAfter, routeFilter)
        lastResponseTimeMs = System.currentTimeMillis()
        result.fold(
            onSuccess = { data ->
                minutesAfter = data.minutesAfter
                _state.value = ArrivalsUiState.Content(
                    header = data.header,
                    arrivals = data.arrivals,
                    minutesAfter = data.minutesAfter,
                    style = data.style,
                    isStale = data.isStale
                )
            },
            onFailure = { error ->
                if (_state.value !is ArrivalsUiState.Content) {
                    _state.value = ArrivalsUiState.Error(error.message.orEmpty())
                }
            }
        )
    }

    /** Refreshes from a user action (the toolbar refresh button or Retry). */
    fun manualRefresh() {
        viewModelScope.launch { refresh() }
    }

    /** Widens the time window and reloads (the "load more arrivals" footer). */
    fun loadMore() {
        minutesAfter += DefaultArrivalsRepository.MINUTES_AFTER_INCREMENT
        viewModelScope.launch { refresh() }
    }

    /** Toggles the stop favorite, updating the header optimistically and persisting. */
    fun toggleFavorite() {
        val content = _state.value as? ArrivalsUiState.Content ?: return
        val newValue = !content.header.isFavorite
        _state.value = content.copy(header = content.header.copy(isFavorite = newValue))
        viewModelScope.launch { repository.setStopFavorite(content.header.stopId, newValue) }
    }
}
