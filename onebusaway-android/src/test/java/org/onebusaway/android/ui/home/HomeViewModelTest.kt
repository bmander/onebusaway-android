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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.map.MapCommand
import org.onebusaway.android.map.MapInteractionBus
import org.onebusaway.android.region.FakeRegionRepository
import org.onebusaway.android.region.RegionStatus
import org.onebusaway.android.region.region
import org.onebusaway.android.testing.MainDispatcherRule

private class FakeWideAlertsRepository(private val alerts: List<WideAlert>) : WideAlertsRepository {
    override fun wideAlerts(regionId: String): Flow<WideAlert> = flow {
        alerts.forEach { emit(it) }
    }
}


private class FakeStartupPreferencesRepository(
    var initial: Boolean = false
) : StartupPreferencesRepository {
    var cleared = 0
    override fun isInitialStartup(): Boolean = initial
    override fun clearInitialStartup() { cleared++; initial = false }
}

private class FakeNavItemsRepository(
    var availability: NavItemAvailability =
        NavItemAvailability(showReminders = false, planTripAvailable = false, payFareAvailable = false)
) : NavItemsRepository {
    override fun availability(): NavItemAvailability = availability
}

/** Records the map interactions HomeViewModel sends through the bus, so they can be asserted directly. */
private class FakeMapInteractionBus : MapInteractionBus {
    var lastBottomPadding: Int = -1
    val sent = mutableListOf<MapCommand>()

    val recenters get() = sent.filterIsInstance<MapCommand.RecenterOnFocusedStop>().map { it.lat to it.lon }
    val routesShown get() = sent.filterIsInstance<MapCommand.ShowRoute>().map { it.routeId }
    val clearFocusCount get() = sent.count { it is MapCommand.ClearFocus }

    private val _bottomPadding = MutableStateFlow(0)
    override val bottomPadding: StateFlow<Int> = _bottomPadding.asStateFlow()
    private val _commands = MutableSharedFlow<MapCommand>(extraBufferCapacity = 8)
    override val commands: SharedFlow<MapCommand> = _commands.asSharedFlow()

    override fun setBottomPadding(px: Int) { lastBottomPadding = px; _bottomPadding.value = px }
    override fun send(command: MapCommand) { sent.add(command) }
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
        startupRepo: FakeStartupPreferencesRepository = FakeStartupPreferencesRepository(),
        navItemsRepo: FakeNavItemsRepository = FakeNavItemsRepository(),
        regionRepo: FakeRegionRepository = FakeRegionRepository().apply { refreshResult = regionStatus },
        savedState: SavedStateHandle = SavedStateHandle(),
        bus: MapInteractionBus = FakeMapInteractionBus(),
    ) = HomeViewModel(
        savedState, FakeWideAlertsRepository(alerts), startupRepo, navItemsRepo, regionRepo, bus
    )

    // --- map loading + peek inputs ---

    @Test
    fun `map loading shows on nearby and is gated off other tabs`() = runTest {
        val vm = viewModel()
        vm.onMapLoading(true)
        assertTrue(vm.uiState.value.mapLoading)
        vm.selectNav(HomeNavItem.STARRED_STOPS)
        assertFalse(vm.uiState.value.mapLoading)
        vm.onMapLoading(false)
        vm.selectNav(HomeNavItem.NEARBY)
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

    // --- GTFS wide alerts (state) ---

    @Test
    fun `a wide alert surfaces as state and is cleared on dismiss`() = runTest {
        val alert = WideAlert("Title", "Message", "https://example.org")
        val regions = FakeRegionRepository()
        val vm = viewModel(alerts = listOf(alert), regionRepo = regions)
        assertNull(vm.uiState.value.wideAlert)

        regions.emit(region(1)) // a current region streams its wide alerts
        advanceUntilIdle()
        assertEquals(alert, vm.uiState.value.wideAlert)

        vm.dismissWideAlert()
        assertNull(vm.uiState.value.wideAlert)
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
        val bus = FakeMapInteractionBus()
        val vm = viewModel(bus = bus)
        vm.onStopFocused(FocusedStop("1", "Main St", "100", 47.6, -122.3))

        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // reveal, skipped
        vm.onSheetSettled(ArrivalsSheetState.Expanded, 120)

        assertEquals(120, bus.lastBottomPadding)
        assertEquals(listOf(47.6 to -122.3), bus.recenters)
    }

    @Test
    fun `expanding with no focused stop only sets padding`() = runTest {
        val bus = FakeMapInteractionBus()
        val vm = viewModel(bus = bus)

        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // reveal, skipped
        vm.onSheetSettled(ArrivalsSheetState.Expanded, 120)

        assertEquals(120, bus.lastBottomPadding)
        assertTrue(bus.recenters.isEmpty())
    }

    @Test
    fun `collapsing and hiding set the map padding`() = runTest {
        val bus = FakeMapInteractionBus()
        val vm = viewModel(bus = bus)

        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // reveal, skipped
        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 80)
        assertEquals(80, bus.lastBottomPadding)
        vm.onSheetSettled(ArrivalsSheetState.Hidden, 80)
        assertEquals(0, bus.lastBottomPadding)
        assertEquals(ArrivalsSheetState.Hidden, vm.lastSettledSheet)
    }

    // --- initial focus (restored vs intent deep-link) ---

    @Test
    fun `applyInitialFocus adopts an intent stop and marks it pending`() = runTest {
        val vm = viewModel()
        val stop = FocusedStop("1", "Main St", "100", 47.6, -122.3)
        vm.applyInitialFocus(stop)
        assertEquals(stop, vm.uiState.value.focusedStop)
        assertNotNull(vm.onArrivalsLoaded()) // pending was marked
    }

    @Test
    fun `applyInitialFocus keeps a restored focus and marks it pending`() = runTest {
        val handle = SavedStateHandle()
        val restored = FocusedStop("42", "Pike St", "577", 47.61, -122.34)
        viewModel(savedState = handle).onStopFocused(restored)
        val vm = viewModel(savedState = handle) // recreation: focus restored from the handle
        vm.applyInitialFocus(null) // intent carries no stop
        assertEquals(restored, vm.uiState.value.focusedStop) // unchanged
        assertNotNull(vm.onArrivalsLoaded()) // pending was marked
    }

    @Test
    fun `applyInitialFocus with no restored or intent focus does nothing`() = runTest {
        val vm = viewModel()
        vm.applyInitialFocus(null)
        assertNull(vm.uiState.value.focusedStop)
        assertNull(vm.onArrivalsLoaded()) // not pending
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
    fun `show route on map collapses the sheet and shows the route`() = runTest {
        val bus = FakeMapInteractionBus()
        val vm = viewModel(bus = bus)
        val events = mutableListOf<HomeEvent>()
        val eventJob = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.requestShowRouteOnMap("42")
        advanceUntilIdle()

        assertEquals(listOf<HomeEvent>(HomeEvent.CollapseSheet), events)
        assertEquals(listOf("42"), bus.routesShown)
        eventJob.cancel()
    }

    @Test
    fun `clear map focus clears the focused stop and the map focus`() = runTest {
        val bus = FakeMapInteractionBus()
        val vm = viewModel(bus = bus)
        vm.onStopFocused(FocusedStop("1", "Main St", "100", 47.6, -122.3))

        vm.requestClearMapFocus()

        assertNull(vm.uiState.value.focusedStop)
        assertEquals(1, bus.clearFocusCount)
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
    fun `a current region surfaces regionReady for the survey trigger`() = runTest {
        val regions = FakeRegionRepository()
        val vm = viewModel(regionRepo = regions)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.regionReady)

        regions.emit(region(1))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.regionReady)

        regions.emit(null) // region cleared (e.g. custom API URL) -> no longer ready
        advanceUntilIdle()
        assertFalse(vm.uiState.value.regionReady)
    }

    @Test
    fun `an auto-selected region is announced via regionFoundName, then cleared`() = runTest {
        val region = region(1)
        val vm = viewModel(regionStatus = RegionStatus.Changed(region))
        assertNull(vm.uiState.value.regionFoundName)

        vm.refreshRegions()
        advanceUntilIdle()
        assertEquals(region.name, vm.uiState.value.regionFoundName)

        vm.onRegionFoundShown()
        assertNull(vm.uiState.value.regionFoundName)
    }

    @Test
    fun `an unchanged region is not announced`() = runTest {
        val vm = viewModel(regionStatus = RegionStatus.Unchanged)
        vm.refreshRegions()
        advanceUntilIdle()
        assertNull(vm.uiState.value.regionFoundName)
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
        val repo = FakeRegionRepository().apply {
            refreshResult = RegionStatus.NeedsManualSelection(regions)
        }
        val vm = viewModel(regionRepo = repo)
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.refreshRegions()
        advanceUntilIdle()
        val chosen = regions[1]
        vm.onRegionChosen(chosen)
        advanceUntilIdle()

        assertEquals(listOf(chosen), repo.chosen)
        assertEquals(HomeDialog.None, vm.uiState.value.dialog)
        // regionName null: the legacy manual-pick path logged no analytics.
        assertEquals(listOf<HomeEvent>(HomeEvent.RegionResolved(true, null)), events)
        job.cancel()
    }

    // --- experimental-regions toggle + restore completion effects ---

    @Test
    fun `the experimental-regions toggle signals RegionToggleChanged on a real change`() = runTest {
        val vm = viewModel(regionStatus = RegionStatus.Changed(region(1)))
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.onExperimentalRegionsToggled()
        advanceUntilIdle()

        assertTrue(events.contains(HomeEvent.RegionToggleChanged))
        job.cancel()
    }

    @Test
    fun `the experimental-regions toggle signals nothing when the region is unchanged`() = runTest {
        val vm = viewModel(regionStatus = RegionStatus.Unchanged)
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.onExperimentalRegionsToggled()
        advanceUntilIdle()

        assertFalse(events.contains(HomeEvent.RegionToggleChanged))
        job.cancel()
    }

    // --- startup region-check gate ---

    @Test
    fun `first launch without permission defers the region check`() = runTest {
        val region = FakeRegionRepository()
        viewModel(regionRepo = region, startupRepo = FakeStartupPreferencesRepository(initial = true))
            .onHomeStarted(hasLocationPermission = false)
        advanceUntilIdle()
        assertEquals(0, region.refreshCount)
    }

    @Test
    fun `first launch with permission checks the region now`() = runTest {
        val region = FakeRegionRepository()
        viewModel(regionRepo = region, startupRepo = FakeStartupPreferencesRepository(initial = true))
            .onHomeStarted(hasLocationPermission = true)
        advanceUntilIdle()
        assertEquals(1, region.refreshCount)
    }

    @Test
    fun `a later launch checks the region regardless of permission`() = runTest {
        val region = FakeRegionRepository()
        viewModel(regionRepo = region, startupRepo = FakeStartupPreferencesRepository(initial = false))
            .onHomeStarted(hasLocationPermission = false)
        advanceUntilIdle()
        assertEquals(1, region.refreshCount)
    }

    @Test
    fun `the first-launch permission result clears the flag and checks the region`() = runTest {
        val region = FakeRegionRepository()
        val startup = FakeStartupPreferencesRepository(initial = true)
        val vm = viewModel(regionRepo = region, startupRepo = startup)
        vm.onLocationPermissionResult()
        advanceUntilIdle()
        assertEquals(1, startup.cleared)
        assertEquals(1, region.refreshCount)
    }

    @Test
    fun `a permission result after the first launch does nothing`() = runTest {
        val region = FakeRegionRepository()
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
        viewModel(savedState = handle).selectNav(HomeNavItem.STARRED_ROUTES)
        // A fresh ViewModel over the same handle simulates process-death recreation.
        assertEquals(HomeNavItem.STARRED_ROUTES, viewModel(savedState = handle).uiState.value.selectedItem)
    }

    @Test
    fun `selecting an activity-launching item neither changes nor persists the selection`() = runTest {
        val handle = SavedStateHandle()
        val vm = viewModel(savedState = handle)
        vm.selectNav(HomeNavItem.SETTINGS)
        assertEquals(HomeNavItem.NEARBY, vm.uiState.value.selectedItem)
        assertEquals(HomeNavItem.NEARBY, viewModel(savedState = handle).uiState.value.selectedItem)
    }

    @Test
    fun `the first selection is fresh even when it matches the default tab`() = runTest {
        val vm = viewModel() // selectedItem defaults to NEARBY
        assertTrue(vm.selectNav(HomeNavItem.NEARBY))
    }

    @Test
    fun `re-tapping the active in-place tab is not fresh, a different tab is`() = runTest {
        val vm = viewModel()
        assertTrue(vm.selectNav(HomeNavItem.STARRED_STOPS)) // first selection -> fresh
        assertFalse(vm.selectNav(HomeNavItem.STARRED_STOPS)) // re-tap -> suppressed
        assertTrue(vm.selectNav(HomeNavItem.STARRED_ROUTES)) // different tab -> fresh
    }

    @Test
    fun `activity-launching items are always fresh`() = runTest {
        val vm = viewModel()
        vm.selectNav(HomeNavItem.STARRED_STOPS)
        assertTrue(vm.selectNav(HomeNavItem.SETTINGS)) // launcher -> fresh (runs every tap)
        assertTrue(vm.selectNav(HomeNavItem.SETTINGS)) // and again
    }

    // --- nav items (built by the VM from the repository) ---

    @Test
    fun `nav items are built from the repository availability at init`() = runTest {
        val repo = FakeNavItemsRepository(
            NavItemAvailability(showReminders = true, planTripAvailable = true, payFareAvailable = false)
        )
        val items = viewModel(navItemsRepo = repo).uiState.value.navItems
        assertTrue(items.contains(HomeNavItem.MY_REMINDERS))
        assertTrue(items.contains(HomeNavItem.PLAN_TRIP))
        assertFalse(items.contains(HomeNavItem.PAY_FARE))
    }

    @Test
    fun `a region change rebuilds the nav items from current availability`() = runTest {
        val repo = FakeNavItemsRepository(NavItemAvailability(false, false, false))
        val regions = FakeRegionRepository()
        val vm = viewModel(navItemsRepo = repo, regionRepo = regions)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.navItems.contains(HomeNavItem.PAY_FARE))

        // The region now supports fare payment; a region change picks it up without a host push.
        repo.availability = NavItemAvailability(
            showReminders = false, planTripAvailable = false, payFareAvailable = true
        )
        regions.emit(region(1))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.navItems.contains(HomeNavItem.PAY_FARE))
    }

    @Test
    fun `onMapShown latches mapComposed`() = runTest {
        val vm = viewModel()
        assertFalse(vm.uiState.value.mapComposed)
        vm.onMapShown()
        assertTrue(vm.uiState.value.mapComposed)
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

/** Pure tests for the launch-resolution helpers: [initialNavItem] + [focusedStopFromExtras]. */
class LaunchResolutionTest {

    @Test
    fun `a deep link into the map forces the NEARBY tab`() {
        // Even with a remembered non-NEARBY tab, a route/stop deep link opens NEARBY to show it.
        assertEquals(HomeNavItem.NEARBY, initialNavItem("STARRED_ROUTES", 2, deepLinksToMap = true))
    }

    @Test
    fun `without a deep link the remembered tab wins`() {
        assertEquals(HomeNavItem.STARRED_ROUTES, initialNavItem("STARRED_ROUTES", 0, deepLinksToMap = false))
        assertEquals(HomeNavItem.MY_REMINDERS, initialNavItem(null, 3, deepLinksToMap = false))
    }

    @Test
    fun `a full set of stop extras builds a FocusedStop`() {
        assertEquals(
            FocusedStop("1_75403", "Pike St", "577", 47.61, -122.34),
            focusedStopFromExtras("1_75403", "Pike St", "577", 47.61, -122.34),
        )
    }

    @Test
    fun `extras without an id or with a zero location yield no focus`() {
        assertNull(focusedStopFromExtras(null, "Pike St", "577", 47.61, -122.34))
        assertNull(focusedStopFromExtras("1_75403", "Pike St", "577", 0.0, 0.0))
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
