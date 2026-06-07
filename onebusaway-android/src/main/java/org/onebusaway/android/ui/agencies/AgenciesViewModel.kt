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
package org.onebusaway.android.ui.agencies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the supported agencies screen. Survives configuration changes, so an in-flight
 * load continues across rotation (unlike the AsyncTaskLoader it replaces).
 */
class AgenciesViewModel(private val repository: AgenciesRepository) : ViewModel() {

    private val _state = MutableStateFlow<AgenciesUiState>(AgenciesUiState.Loading)
    val state: StateFlow<AgenciesUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Starts (or restarts, e.g. for retry-after-error) loading the agency list. */
    fun load() {
        _state.value = AgenciesUiState.Loading
        viewModelScope.launch {
            _state.value = repository.getAgencies().fold(
                onSuccess = { AgenciesUiState.Success(it) },
                onFailure = { AgenciesUiState.Error }
            )
        }
    }
}
