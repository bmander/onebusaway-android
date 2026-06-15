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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.region.DefaultRegionRepository
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.region.RegionState

/** A controllable [RegionRepository] for ViewModel tests; shared across the package (see `region(id)`). */
internal class FakeRegionRepository(initial: ObaRegion? = null) : RegionRepository {
    private val _region = MutableStateFlow(initial)
    override val region: StateFlow<ObaRegion?> = _region
    private val _state = MutableStateFlow<RegionState>(RegionState.Active(initial))
    override val state: StateFlow<RegionState> = _state
    fun emit(region: ObaRegion?) { _region.value = region; _state.value = RegionState.Active(region) }
    /** Drives the richer [state] flow directly (Resolving / NeedsManualChoice / Failed). */
    fun emitState(state: RegionState) { _state.value = state }
}

/** Unit tests for [DefaultRegionRepository] — the observable current-region holder. */
class RegionRepositoryTest {

    @Test
    fun `the seed region is the initial value`() {
        // ObaRegion equality is id-based, so region(1) matches the seeded region(1).
        assertEquals(region(1), DefaultRegionRepository(region(1)).region.value)
    }

    @Test
    fun `a null seed yields a null initial value`() {
        assertNull(DefaultRegionRepository(null).region.value)
    }

    @Test
    fun `publish updates the observable region, including back to null`() {
        val repo = DefaultRegionRepository(region(1))
        repo.publish(region(2))
        assertEquals(region(2), repo.region.value)
        repo.publish(null)
        assertNull(repo.region.value)
    }

    @Test
    fun `state mirrors the region as Active, including the seed and back to null`() {
        val repo = DefaultRegionRepository(region(1))
        assertEquals(RegionState.Active(region(1)), repo.state.value)
        repo.publish(region(2))
        assertEquals(RegionState.Active(region(2)), repo.state.value)
        repo.publish(null)
        assertEquals(RegionState.Active(null), repo.state.value)
    }
}
