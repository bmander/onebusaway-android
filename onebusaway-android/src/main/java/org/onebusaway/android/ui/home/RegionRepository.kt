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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.onebusaway.android.io.elements.ObaRegion

/**
 * The observable current region — the reactive replacement for reading `Application.currentRegion`
 * statically. Features that must react to a region change (weather, wide alerts, the survey/what's-new
 * gate, the nav-drawer items, the map's re-centering) collect [region] instead of being hand-fed by a
 * region-resolution fan-out. The *resolution action* lives separately in [RegionStatusRepository]; it
 * writes through `Application.setCurrentRegion`, which is the single point that publishes here.
 *
 * One process-singleton instance is held on `Application` (mirroring `getGtfsAlerts()`), so every view
 * model shares the same flow; tests substitute a fake exposing a `MutableStateFlow`.
 */
interface RegionRepository {

    /** The current region, or null when none is set (e.g. a custom API URL is configured). */
    val region: StateFlow<ObaRegion?>
}

/**
 * Default implementation: a thin holder around a [MutableStateFlow], seeded with the region loaded at
 * startup and updated only by `Application.setCurrentRegion` via [publish] (the single write choke point
 * every region write — modern, manual-pick, and legacy `ObaRegionsTask` — already funnels through).
 */
class DefaultRegionRepository(initial: ObaRegion?) : RegionRepository {

    private val _region = MutableStateFlow(initial)

    override val region: StateFlow<ObaRegion?> = _region.asStateFlow()

    /**
     * Publishes the new current region (or null). **Only `Application.setCurrentRegion` should call
     * this** — it is the one place all region writes converge. `ObaRegion` equality is id-based, so a
     * republish of the same region is deduplicated by the [MutableStateFlow] and by collectors that key
     * on the id.
     */
    fun publish(region: ObaRegion?) {
        _region.value = region
    }
}
