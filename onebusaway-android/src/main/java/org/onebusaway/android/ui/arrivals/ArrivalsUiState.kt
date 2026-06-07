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

import org.onebusaway.android.ui.ArrivalInfo

/** The stop being viewed, for the arrivals screen header. */
data class StopHeader(
    val stopId: String,
    val name: String,
    val direction: String?,
    val isFavorite: Boolean,
    val routeCount: Int
)

/** UI state for the arrivals screen. */
sealed interface ArrivalsUiState {

    data object Loading : ArrivalsUiState

    /**
     * @param arrivals the existing [ArrivalInfo] display model, already filtered and sorted
     * @param style one of BuildFlavorUtils.ARRIVAL_INFO_STYLE_*
     * @param isStale true when showing the last good data after a refresh failed
     */
    data class Content(
        val header: StopHeader,
        val arrivals: List<ArrivalInfo>,
        val minutesAfter: Int,
        val style: Int,
        val isStale: Boolean
    ) : ArrivalsUiState

    data class Error(val message: String) : ArrivalsUiState
}
