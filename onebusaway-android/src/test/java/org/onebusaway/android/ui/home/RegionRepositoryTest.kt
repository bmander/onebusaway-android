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
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.region.RegionState
import org.onebusaway.android.region.RegionStateHolder
import org.onebusaway.android.region.RegionStatus

/**
 * A controllable [RegionRepository] for ViewModel tests; shared across the package (see `region(id)`).
 * It drives both the observable [region]/[state] flows and the resolve action ([refresh]/[choose]) —
 * folding in the former `FakeRegionStatusRepository` now that resolution lives in the repository.
 */
internal class FakeRegionRepository(initial: ObaRegion? = null) : RegionRepository {
    private val _region = MutableStateFlow(initial)
    override val region: StateFlow<ObaRegion?> = _region
    private val _state = MutableStateFlow<RegionState>(RegionState.Active(initial))
    override val state: StateFlow<RegionState> = _state

    /** The outcome [refresh] returns; set per test (default mirrors a no-op refresh). */
    var refreshResult: RegionStatus = RegionStatus.Unchanged
    var refreshCount = 0
    val chosen = mutableListOf<ObaRegion>()

    override suspend fun refresh(): RegionStatus { refreshCount++; return refreshResult }
    override suspend fun choose(region: ObaRegion) { chosen.add(region); emit(region) }
    override fun clear() = emit(null)
    override fun syncActivated(region: ObaRegion?) = emit(region)

    fun emit(region: ObaRegion?) { _region.value = region; _state.value = RegionState.Active(region) }
    /** Drives the richer [state] flow directly (Resolving / NeedsManualChoice / Failed). */
    fun emitState(state: RegionState) { _state.value = state }
}

/** Unit tests for [RegionStateHolder] — the observable region-state holder the repository exposes. */
class RegionStateHolderTest {

    @Test
    fun `the seed region is the initial value and Active`() {
        // ObaRegion equality is id-based, so region(1) matches the seeded region(1).
        val holder = RegionStateHolder(region(1))
        assertEquals(region(1), holder.region.value)
        assertEquals(RegionState.Active(region(1)), holder.state.value)
    }

    @Test
    fun `a null seed yields a null Active`() {
        val holder = RegionStateHolder(null)
        assertNull(holder.region.value)
        assertEquals(RegionState.Active(null), holder.state.value)
    }

    @Test
    fun `activated updates region and state, including back to null`() {
        val holder = RegionStateHolder(region(1))
        holder.activated(region(2))
        assertEquals(region(2), holder.region.value)
        assertEquals(RegionState.Active(region(2)), holder.state.value)
        holder.activated(null)
        assertNull(holder.region.value)
        assertEquals(RegionState.Active(null), holder.state.value)
    }

    @Test
    fun `resolving and failed change state but keep the last region`() {
        val holder = RegionStateHolder(region(1))
        holder.resolving()
        assertEquals(region(1), holder.region.value)
        assertEquals(RegionState.Resolving, holder.state.value)
        holder.failed()
        assertEquals(region(1), holder.region.value)
        assertEquals(RegionState.Failed, holder.state.value)
    }

    @Test
    fun `needsChoice carries the regions and keeps the last region`() {
        val holder = RegionStateHolder(null)
        val regions = listOf(region(1), region(2))
        holder.needsChoice(regions)
        assertNull(holder.region.value)
        assertEquals(RegionState.NeedsManualChoice(regions), holder.state.value)
    }
}
