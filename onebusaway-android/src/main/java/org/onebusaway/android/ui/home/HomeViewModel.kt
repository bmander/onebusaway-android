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
package org.onebusaway.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Compose home screen: the selected drawer item, the focused stop, and the arrivals
 * sheet state. Survives configuration changes, replacing HomeActivity's onSaveInstanceState
 * juggling of mFocusedStopId/mFocusedStop and the panel-state bookkeeping. The host bridges the
 * map/arrivals Fragment listeners into these setters and carries out [HomeEvent]s.
 */
class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    fun onNavItemSelected(item: HomeNavItem) {
        if (item.launchesActivity) {
            viewModelScope.launch { _events.emit(HomeEvent.LaunchNavItem(item)) }
        } else {
            _uiState.update { it.copy(selectedItem = item) }
        }
    }

    /** Map focus changed: a non-null stop peeks the sheet; null hides it. */
    fun onStopFocused(stop: FocusedStop?) = _uiState.update {
        it.copy(
            focusedStop = stop,
            sheetState = if (stop != null) ArrivalsSheetState.Collapsed else ArrivalsSheetState.Hidden
        )
    }

    fun onSheetStateChanged(state: ArrivalsSheetState) = _uiState.update { it.copy(sheetState = state) }

    /** Arrivals panel reported its preferred peek size (row count + whether route filtering is on). */
    fun onPreferredHeight(arrivalCount: Int, filtering: Boolean) = _uiState.update {
        it.copy(peekArrivalCount = arrivalCount, routeFiltering = filtering)
    }

    fun onMapLoading(loading: Boolean) = _uiState.update { it.copy(mapLoading = loading) }

    fun onShowRouteOnMap(routeId: String) {
        viewModelScope.launch { _events.emit(HomeEvent.ShowRouteOnMap(routeId)) }
    }
}
