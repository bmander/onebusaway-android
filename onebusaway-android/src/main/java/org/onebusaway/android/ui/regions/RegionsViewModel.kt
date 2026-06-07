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
package org.onebusaway.android.ui.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** ViewModel for the region picker screen. */
class RegionsViewModel(private val repository: RegionsRepository) : ViewModel() {

    private val _state = MutableStateFlow<RegionsUiState>(RegionsUiState.Loading)
    val state: StateFlow<RegionsUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * Loads the region list. Used for the initial load, retry-after-error (both with
     * [refresh] = false, which reads the local provider first), and the explicit refresh
     * action ([refresh] = true, which forces a server fetch).
     */
    fun load(refresh: Boolean = false) {
        _state.value = RegionsUiState.Loading
        viewModelScope.launch {
            _state.value = repository.getRegions(refresh).fold(
                onSuccess = { RegionsUiState.Success(it) },
                onFailure = { RegionsUiState.Error }
            )
        }
    }

    /**
     * Makes [item] the current region.
     *
     * @return true if this selection disabled automatic region selection
     */
    fun selectRegion(item: RegionItem): Boolean = repository.selectRegion(item.id)
}
