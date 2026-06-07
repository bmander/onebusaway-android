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

/**
 * A transit agency as displayed on the supported agencies screen, decoupled from the
 * io/elements response types.
 *
 * @param url the agency's website, or null if it has none (never blank)
 */
data class AgencyItem(
    val id: String,
    val name: String,
    val url: String?
)

/** UI state for the supported agencies screen. */
sealed interface AgenciesUiState {

    data object Loading : AgenciesUiState

    data class Success(val agencies: List<AgencyItem>) : AgenciesUiState

    data object Error : AgenciesUiState
}
