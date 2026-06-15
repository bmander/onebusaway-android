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
package org.onebusaway.android.region

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.onebusaway.android.io.elements.ObaRegion

/**
 * The observable current region — the reactive replacement for reading `Application.currentRegion`
 * statically. Features that must react to a region change (weather, wide alerts, the survey/what's-new
 * gate, the nav-drawer items, the map's re-centering) collect [region] instead of being hand-fed by a
 * region-resolution fan-out. The *resolution action* lives separately in `RegionStatusRepository`; it
 * writes through `Application.setCurrentRegion`, which is the single point that publishes here.
 *
 * One process-singleton instance is held on `Application` (mirroring `getGtfsAlerts()`), so every view
 * model shares the same flow; tests substitute a fake exposing a `MutableStateFlow`. It lives in the
 * neutral `region` package (alongside the other region infrastructure) so both the map and the home UI
 * can depend on it without a backward dependency.
 */
interface RegionRepository {

    /** The current region, or null when none is set (e.g. a custom API URL is configured). */
    val region: StateFlow<ObaRegion?>

    /**
     * The richer resolution state (Campaign A) — what the UI can render directly: a refresh in flight,
     * an active region, a manual choice required, or a failure. [region] mirrors this flow's
     * [RegionState.Active] region. Today this only ever holds [RegionState.Active] (the holder is fed
     * by `Application.setCurrentRegion`); the [RegionState.Resolving]/[RegionState.NeedsManualChoice]/
     * [RegionState.Failed] cases come online when region resolution moves into the repository.
     */
    val state: StateFlow<RegionState>
}

/**
 * The reactive region resolution state, superseding the bare nullable [RegionRepository.region].
 * Mirrors (and will absorb) the legacy resolution outcomes in `RegionStatusRepository`.
 */
sealed interface RegionState {

    /** A region resolution is in flight and no result is available yet. */
    object Resolving : RegionState

    /** A region is set. [region] is null only when a custom API URL is configured (no region needed). */
    data class Active(val region: ObaRegion?) : RegionState

    /** No region could be auto-selected; the user must pick one from [regions] (usable, name-sorted). */
    data class NeedsManualChoice(val regions: List<ObaRegion>) : RegionState

    /** Region info could not be loaded from any source (catastrophic failure). */
    object Failed : RegionState
}

/**
 * Default implementation: a thin holder around a [MutableStateFlow], seeded with the region loaded at
 * startup and updated only by `Application.setCurrentRegion` via [publish] (the single write choke point
 * every region write — modern, manual-pick, and legacy `ObaRegionsTask` — already funnels through).
 */
class DefaultRegionRepository(initial: ObaRegion?) : RegionRepository {

    private val _region = MutableStateFlow(initial)

    override val region: StateFlow<ObaRegion?> = _region.asStateFlow()

    private val _state = MutableStateFlow<RegionState>(RegionState.Active(initial))

    override val state: StateFlow<RegionState> = _state.asStateFlow()

    /**
     * Publishes the new current region (or null). **Only `Application.setCurrentRegion` should call
     * this** — it is the one place all region writes converge. `ObaRegion` equality is id-based, so a
     * republish of the same region is deduplicated by the [MutableStateFlow] and by collectors that key
     * on the id. Keeps [state] in lockstep as [RegionState.Active].
     */
    fun publish(region: ObaRegion?) {
        _region.value = region
        _state.value = RegionState.Active(region)
    }
}
