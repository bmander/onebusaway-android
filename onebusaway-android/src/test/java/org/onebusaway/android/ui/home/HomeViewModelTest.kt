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

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.map.MapCommand
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.testing.MainDispatcherRule

private class FakeWideAlertsRepository(private val alerts: List<WideAlert>) : WideAlertsRepository {
    override fun wideAlerts(regionId: String): Flow<WideAlert> = flow {
        alerts.forEach { emit(it) }
    }
}

private class FakeRegionStatusRepository(
    var result: RegionStatus = RegionStatus.Unchanged
) : RegionStatusRepository {
    val selected = mutableListOf<ObaRegion>()
    var refreshCount = 0
    override suspend fun refreshRegions(): RegionStatus { refreshCount++; return result }
    override suspend fun selectRegion(region: ObaRegion) {
        selected.add(region)
    }
}

private class FakeStartupPreferencesRepository(
    var initial: Boolean = false
) : StartupPreferencesRepository {
    var cleared = 0
    override fun isInitialStartup(): Boolean = initial
    override fun clearInitialStartup() { cleared++; initial = false }
}

/**
 * Unit tests for [HomeViewModel] and the pure [buildState] gate logic lifted out of HomeActivity.
 * Mirrors the established ViewModel test pattern (MainDispatcherRule + runTest + hand-written fakes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        alerts: List<WideAlert> = emptyList(),
        regionStatus: RegionStatus = RegionStatus.Unchanged,
        regionRepo: FakeRegionStatusRepository = FakeRegionStatusRepository(regionStatus),
        startupRepo: FakeStartupPreferencesRepository = FakeStartupPreferencesRepository(),
        savedState: SavedStateHandle = SavedStateHandle(),
        map: MapViewModel = MapViewModel(),
    ) = HomeViewModel(
        savedState, FakeWideAlertsRepository(alerts), regionRepo, startupRepo, map
    )

    // --- map loading + peek inputs ---

    @Test
    fun `map loading shows on nearby and is gated off other tabs`() = runTest {
        val vm = viewModel()
        vm.onMapLoading(true)
        assertTrue(vm.uiState.value.mapLoading)
        vm.onNavItemSelected(HomeNavItem.STARRED_STOPS)
        assertFalse(vm.uiState.value.mapLoading)
        vm.onMapLoading(false)
        vm.onNavItemSelected(HomeNavItem.NEARBY)
        assertFalse(vm.uiState.value.mapLoading)
    }

    @Test
    fun `onPreferredHeight stores the preview count and filter flag`() = runTest {
        val vm = viewModel()
        vm.onPreferredHeight(arrivalCount = 1, filtering = true)
        assertEquals(1, vm.uiState.value.peekArrivalCount)
        assertTrue(vm.uiState.value.routeFiltering)
    }

    // --- one-shot sheet / drawer commands ---

    @Test
    fun `the chevron tap emits ToggleSheet`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.requestToggleSheet()
        advanceUntilIdle()

        assertEquals(listOf<HomeEvent>(HomeEvent.ToggleSheet), events)
        job.cancel()
    }

    // --- GTFS wide alerts (events) ---

    @Test
    fun `wide alerts are emitted as ShowWideAlert events`() = runTest {
        val alert = WideAlert("Title", "Message", "https://example.org")
        val vm = viewModel(alerts = listOf(alert))
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle() // ensure the collector is subscribed before the region triggers alerts

        vm.onRegionValid(1L)
        advanceUntilIdle()

        assertEquals(listOf(HomeEvent.ShowWideAlert(alert)), events)
        job.cancel()
    }

    // --- arrivals sheet settled -> map padding / recenter ---

    @Test
    fun `the initial sheet reveal from hidden emits no map effects`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // previous == Hidden -> skip
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertEquals(ArrivalsSheetState.Collapsed, vm.lastSettledSheet)
        job.cancel()
    }

    @Test
    fun `expanding over a focused stop sets padding and recenters`() = runTest {
        val map = MapViewModel()
        val vm = viewModel(map = map)
        vm.onStopFocused(FocusedStop("1", "Main St", "100", 47.6, -122.3))
        val commands = mutableListOf<MapCommand>()
        val job = launch { map.mapCommands.collect { commands.add(it) } }
        advanceUntilIdle()

        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // reveal, skipped
        vm.onSheetSettled(ArrivalsSheetState.Expanded, 120)
        advanceUntilIdle()

        assertEquals(120, map.renderState.padding.value.bottomPx)
        assertEquals(listOf<MapCommand>(MapCommand.Recenter(47.6, -122.3)), commands)
        job.cancel()
    }

    @Test
    fun `expanding with no focused stop only sets padding`() = runTest {
        val map = MapViewModel()
        val vm = viewModel(map = map)
        val commands = mutableListOf<MapCommand>()
        val job = launch { map.mapCommands.collect { commands.add(it) } }
        advanceUntilIdle()

        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // reveal, skipped
        vm.onSheetSettled(ArrivalsSheetState.Expanded, 120)
        advanceUntilIdle()

        assertEquals(120, map.renderState.padding.value.bottomPx)
        assertTrue(commands.isEmpty())
        job.cancel()
    }

    @Test
    fun `collapsing and hiding set the map padding`() = runTest {
        val map = MapViewModel()
        val vm = viewModel(map = map)

        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // reveal, skipped
        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 80)
        assertEquals(80, map.renderState.padding.value.bottomPx)
        vm.onSheetSettled(ArrivalsSheetState.Hidden, 80)
        assertEquals(0, map.renderState.padding.value.bottomPx)
        assertEquals(ArrivalsSheetState.Hidden, vm.lastSettledSheet)
    }

    // --- pending map focus / route mode / clear focus ---

    @Test
    fun `a pending focus is reported once on arrivals load`() = runTest {
        val vm = viewModel()
        vm.markPendingMapFocus()
        // Pending -> returns the overlay-expanded decision (false: sheet not expanded); latch then clears.
        assertEquals(false, vm.onArrivalsLoaded())
        assertNull(vm.onArrivalsLoaded())          // latch cleared -> no longer pending
    }

    @Test
    fun `arrivals load with no pending focus returns null`() = runTest {
        val vm = viewModel()
        assertNull(vm.onArrivalsLoaded())
    }

    @Test
    fun `a pending focus reports overlay-expanded when the sheet is expanded`() = runTest {
        val vm = viewModel()
        vm.onStopFocused(FocusedStop("1", "Main St", "100", 47.6, -122.3))
        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // reveal, skipped
        vm.onSheetSettled(ArrivalsSheetState.Expanded, 120)

        vm.markPendingMapFocus()
        assertEquals(true, vm.onArrivalsLoaded())
    }

    @Test
    fun `show route on map collapses the sheet and dispatches ShowRoute`() = runTest {
        val map = MapViewModel()
        val vm = viewModel(map = map)
        val events = mutableListOf<HomeEvent>()
        val commands = mutableListOf<MapCommand>()
        val eventJob = launch { vm.events.collect { events.add(it) } }
        val commandJob = launch { map.mapCommands.collect { commands.add(it) } }
        advanceUntilIdle()

        vm.requestShowRouteOnMap("42")
        advanceUntilIdle()

        assertEquals(listOf<HomeEvent>(HomeEvent.CollapseSheet), events)
        assertEquals(listOf<MapCommand>(MapCommand.ShowRoute("42")), commands)
        eventJob.cancel()
        commandJob.cancel()
    }

    @Test
    fun `clear map focus clears the focused stop and dispatches ClearFocus`() = runTest {
        val map = MapViewModel()
        val vm = viewModel(map = map)
        vm.onStopFocused(FocusedStop("1", "Main St", "100", 47.6, -122.3))
        val commands = mutableListOf<MapCommand>()
        val job = launch { map.mapCommands.collect { commands.add(it) } }
        advanceUntilIdle()

        vm.requestClearMapFocus()
        advanceUntilIdle()

        assertNull(vm.uiState.value.focusedStop)
        assertEquals(listOf<MapCommand>(MapCommand.ClearFocus), commands)
        job.cancel()
    }

    // --- focus + SavedStateHandle ---

    @Test
    fun `onStopFocused sets and clears the focused stop`() = runTest {
        val vm = viewModel()
        val stop = FocusedStop("1", "Main St", "100", 47.6, -122.3)
        vm.onStopFocused(stop)
        assertEquals(stop, vm.uiState.value.focusedStop)
        vm.onStopFocused(null)
        assertNull(vm.uiState.value.focusedStop)
    }

    @Test
    fun `focusing a stop clears the focused bike station`() = runTest {
        val vm = viewModel()
        vm.onBikeStationFocused("bike-7")
        assertEquals("bike-7", vm.uiState.value.focusedBikeStationId)
        vm.onStopFocused(FocusedStop("1", null, null, 1.0, 2.0))
        assertNull(vm.uiState.value.focusedBikeStationId)
    }

    @Test
    fun `focused stop is restored from SavedStateHandle on recreation`() = runTest {
        val handle = SavedStateHandle()
        val stop = FocusedStop("42", "Pike St", "577", 47.61, -122.34)
        viewModel(savedState = handle).onStopFocused(stop)
        // A fresh ViewModel over the same handle simulates process-death recreation.
        assertEquals(stop, viewModel(savedState = handle).uiState.value.focusedStop)
    }

    // --- region refresh (events + manual-picker dialog) ---

    @Test
    fun `a changed region emits RegionResolved with the region name`() = runTest {
        val region = region(1)
        val vm = viewModel(regionStatus = RegionStatus.Changed(region))
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.refreshRegions()
        advanceUntilIdle()

        assertEquals(listOf(HomeEvent.RegionResolved(true, region.name)), events)
        job.cancel()
    }

    @Test
    fun `an unchanged region emits RegionResolved without a name`() = runTest {
        val vm = viewModel(regionStatus = RegionStatus.Unchanged)
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.refreshRegions()
        advanceUntilIdle()

        assertEquals(listOf(HomeEvent.RegionResolved(false, null)), events)
        job.cancel()
    }

    @Test
    fun `needing manual selection raises the chooser dialog and emits no event`() = runTest {
        val regions = listOf(region(1), region(2))
        val vm = viewModel(regionStatus = RegionStatus.NeedsManualSelection(regions))
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.refreshRegions()
        advanceUntilIdle()

        assertEquals(HomeDialog.ChooseRegion(regions), vm.uiState.value.dialog)
        assertTrue(events.isEmpty())
        job.cancel()
    }

    @Test
    fun `skipped, fixed, and failed statuses emit no event`() = runTest {
        val statuses = listOf(RegionStatus.Skipped, RegionStatus.Fixed(region(1)), RegionStatus.Failed)
        for (status in statuses) {
            val vm = viewModel(regionStatus = status)
            val events = mutableListOf<HomeEvent>()
            val job = launch { vm.events.collect { events.add(it) } }
            advanceUntilIdle()

            vm.refreshRegions()
            advanceUntilIdle()

            assertTrue("$status should emit no event", events.isEmpty())
            job.cancel()
        }
    }

    @Test
    fun `onRegionChosen selects the region, dismisses the dialog, and signals a change`() = runTest {
        val regions = listOf(region(1), region(2))
        val repo = FakeRegionStatusRepository(RegionStatus.NeedsManualSelection(regions))
        val vm = viewModel(regionRepo = repo)
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.refreshRegions()
        advanceUntilIdle()
        val chosen = regions[1]
        vm.onRegionChosen(chosen)
        advanceUntilIdle()

        assertEquals(listOf(chosen), repo.selected)
        assertEquals(HomeDialog.None, vm.uiState.value.dialog)
        // regionName null: the legacy manual-pick path logged no analytics.
        assertEquals(listOf<HomeEvent>(HomeEvent.RegionResolved(true, null)), events)
        job.cancel()
    }

    // --- startup region-check gate ---

    @Test
    fun `first launch without permission defers the region check`() = runTest {
        val region = FakeRegionStatusRepository()
        viewModel(regionRepo = region, startupRepo = FakeStartupPreferencesRepository(initial = true))
            .onHomeStarted(hasLocationPermission = false)
        advanceUntilIdle()
        assertEquals(0, region.refreshCount)
    }

    @Test
    fun `first launch with permission checks the region now`() = runTest {
        val region = FakeRegionStatusRepository()
        viewModel(regionRepo = region, startupRepo = FakeStartupPreferencesRepository(initial = true))
            .onHomeStarted(hasLocationPermission = true)
        advanceUntilIdle()
        assertEquals(1, region.refreshCount)
    }

    @Test
    fun `a later launch checks the region regardless of permission`() = runTest {
        val region = FakeRegionStatusRepository()
        viewModel(regionRepo = region, startupRepo = FakeStartupPreferencesRepository(initial = false))
            .onHomeStarted(hasLocationPermission = false)
        advanceUntilIdle()
        assertEquals(1, region.refreshCount)
    }

    @Test
    fun `the first-launch permission result clears the flag and checks the region`() = runTest {
        val region = FakeRegionStatusRepository()
        val startup = FakeStartupPreferencesRepository(initial = true)
        val vm = viewModel(regionRepo = region, startupRepo = startup)
        vm.onLocationPermissionResult()
        advanceUntilIdle()
        assertEquals(1, startup.cleared)
        assertEquals(1, region.refreshCount)
    }

    @Test
    fun `a permission result after the first launch does nothing`() = runTest {
        val region = FakeRegionStatusRepository()
        val startup = FakeStartupPreferencesRepository(initial = false)
        viewModel(regionRepo = region, startupRepo = startup).onLocationPermissionResult()
        advanceUntilIdle()
        assertEquals(0, startup.cleared)
        assertEquals(0, region.refreshCount)
    }

    // --- nav selection + SavedStateHandle ---

    @Test
    fun `selected nav item is restored from SavedStateHandle on recreation`() = runTest {
        val handle = SavedStateHandle()
        viewModel(savedState = handle).onNavItemSelected(HomeNavItem.STARRED_ROUTES)
        // A fresh ViewModel over the same handle simulates process-death recreation.
        assertEquals(HomeNavItem.STARRED_ROUTES, viewModel(savedState = handle).uiState.value.selectedItem)
    }

    @Test
    fun `selecting an activity-launching item neither changes nor persists the selection`() = runTest {
        val handle = SavedStateHandle()
        val vm = viewModel(savedState = handle)
        vm.onNavItemSelected(HomeNavItem.SETTINGS)
        assertEquals(HomeNavItem.NEARBY, vm.uiState.value.selectedItem)
        assertEquals(HomeNavItem.NEARBY, viewModel(savedState = handle).uiState.value.selectedItem)
    }
}

/** Pure tests for [persistedNavItem] — the enum-name pref read with the legacy int-position fallback. */
class NavPersistenceTest {

    @Test
    fun `a valid enum name wins over the legacy position`() {
        assertEquals(HomeNavItem.MY_REMINDERS, persistedNavItem("MY_REMINDERS", 0))
    }

    @Test
    fun `an unknown name falls back to the legacy position`() {
        assertEquals(HomeNavItem.STARRED_ROUTES, persistedNavItem("GARBAGE", 2))
    }

    @Test
    fun `a null name maps the legacy int positions`() {
        assertEquals(HomeNavItem.NEARBY, persistedNavItem(null, 0))
        assertEquals(HomeNavItem.STARRED_STOPS, persistedNavItem(null, 1))
        assertEquals(HomeNavItem.STARRED_ROUTES, persistedNavItem(null, 2))
        assertEquals(HomeNavItem.MY_REMINDERS, persistedNavItem(null, 3))
    }

    @Test
    fun `a null name with an out-of-range position is NEARBY`() {
        assertEquals(HomeNavItem.NEARBY, persistedNavItem(null, 7))
        assertEquals(HomeNavItem.NEARBY, persistedNavItem(null, -1))
    }
}

/** Pure tests for the [buildState] visibility-gating projection (no coroutines needed). */
class HomeStateTest {

    private fun state(
        selected: HomeNavItem,
        env: HomeEnvironment = HomeEnvironment(),
        mapLoading: Boolean = false,
        focusedStop: FocusedStop? = null,
    ) = buildState(
        selected, emptyList(), env, HomeDialog.None,
        focusedStop = focusedStop, mapLoading = mapLoading,
    )

    @Test
    fun `chrome FABs show only on nearby`() {
        assertTrue(state(HomeNavItem.NEARBY).fabsVisible)
        assertFalse(state(HomeNavItem.STARRED_STOPS).fabsVisible)
        assertFalse(state(HomeNavItem.MY_REMINDERS).fabsVisible)
    }

    @Test
    fun `layers FAB needs nearby and bikeshare`() {
        assertTrue(state(HomeNavItem.NEARBY, HomeEnvironment(bikeshareEnabled = true)).layersFabVisible)
        assertFalse(state(HomeNavItem.NEARBY, HomeEnvironment(bikeshareEnabled = false)).layersFabVisible)
        assertFalse(
            state(HomeNavItem.STARRED_STOPS, HomeEnvironment(bikeshareEnabled = true)).layersFabVisible
        )
    }

    @Test
    fun `zoom controls need nearby and the preference`() {
        assertTrue(state(HomeNavItem.NEARBY, HomeEnvironment(zoomControlsPref = true)).zoomControlsVisible)
        assertFalse(state(HomeNavItem.NEARBY, HomeEnvironment(zoomControlsPref = false)).zoomControlsVisible)
        assertFalse(
            state(HomeNavItem.STARRED_STOPS, HomeEnvironment(zoomControlsPref = true)).zoomControlsVisible
        )
    }

    @Test
    fun `left-hand mode follows the preference regardless of tab`() {
        assertTrue(state(HomeNavItem.NEARBY, HomeEnvironment(leftHandMode = true)).leftHandMode)
        assertTrue(state(HomeNavItem.STARRED_STOPS, HomeEnvironment(leftHandMode = true)).leftHandMode)
    }

    @Test
    fun `list menu groups follow the selected tab`() {
        // Sort shows on all three list tabs; clear only on the two starred tabs.
        assertTrue(state(HomeNavItem.STARRED_STOPS).showListSortMenu)
        assertTrue(state(HomeNavItem.STARRED_ROUTES).showListSortMenu)
        assertTrue(state(HomeNavItem.MY_REMINDERS).showListSortMenu)
        assertFalse(state(HomeNavItem.NEARBY).showListSortMenu)

        assertTrue(state(HomeNavItem.STARRED_STOPS).showListClearMenu)
        assertTrue(state(HomeNavItem.STARRED_ROUTES).showListClearMenu)
        assertFalse(state(HomeNavItem.MY_REMINDERS).showListClearMenu)
        assertFalse(state(HomeNavItem.NEARBY).showListClearMenu)
    }

    @Test
    fun `the map loading indicator is gated to the nearby tab`() {
        assertTrue(state(HomeNavItem.NEARBY, mapLoading = true).mapLoading)
        assertFalse(state(HomeNavItem.NEARBY, mapLoading = false).mapLoading)
        assertFalse(state(HomeNavItem.STARRED_STOPS, mapLoading = true).mapLoading)
    }

    @Test
    fun `the focused stop passes through untouched`() {
        val stop = FocusedStop("1", "Main St", "100", 47.6, -122.3)
        assertEquals(stop, state(HomeNavItem.NEARBY, focusedStop = stop).focusedStop)
        assertNull(state(HomeNavItem.NEARBY, focusedStop = null).focusedStop)
    }
}
