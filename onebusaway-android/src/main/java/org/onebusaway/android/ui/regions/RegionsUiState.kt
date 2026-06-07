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

/**
 * A region as displayed on the region picker screen, decoupled from the io/elements
 * response types.
 *
 * @param distanceMeters distance from the user's last known location, or null when no
 * location (or no distance) is available
 * @param isCurrent whether this is the currently selected region
 */
data class RegionItem(
    val id: Long,
    val name: String,
    val distanceMeters: Float?,
    val isCurrent: Boolean
)

/** UI state for the region picker screen. */
sealed interface RegionsUiState {

    data object Loading : RegionsUiState

    data class Success(val regions: List<RegionItem>) : RegionsUiState

    data object Error : RegionsUiState
}
