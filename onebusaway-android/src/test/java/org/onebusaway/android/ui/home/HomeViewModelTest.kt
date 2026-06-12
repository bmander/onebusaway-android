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
import java.io.IOException
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
import org.onebusaway.android.testing.MainDispatcherRule

private class FakeWeatherRepository(var result: Result<WeatherData>) : WeatherRepository {
    val requestedRegions = mutableListOf<Long>()
    override suspend fun currentForecast(regionId: Long): Result<WeatherData> {
        requestedRegions.add(regionId)
        return result
    }
}

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

    private val forecast = WeatherData(icon = "clear-day", temperatureF = 70.0, summary = "Clear")

    private fun viewModel(
        weather: Result<WeatherData> = Result.success(forecast),
        alerts: List<WideAlert> = emptyList(),
        regionStatus: RegionStatus = RegionStatus.Unchanged,
        regionRepo: FakeRegionStatusRepository = FakeRegionStatusRepository(regionStatus),
        startupRepo: FakeStartupPreferencesRepository = FakeStartupPreferencesRepository(),
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = HomeViewModel(
        savedState, FakeWeatherRepository(weather), FakeWideAlertsRepository(alerts), regionRepo,
        startupRepo
    )

    // --- weather (async) ---

    @Test
    fun `weather shows on nearby after the region becomes valid`() = runTest {
        val vm = viewModel()
        vm.onRegionValid(1L)
        advanceUntilIdle()
        assertEquals(forecast, vm.uiState.value.weather)
    }

    @Test
    fun `weather stays hidden when the preference hides it`() = runTest {
        val vm = viewModel()
        vm.onEnvironmentRefreshed(HomeEnvironment(weatherHidden = true))
        vm.onRegionValid(1L)
        advanceUntilIdle()
        assertNull(vm.uiState.value.weather)
    }

    @Test
    fun `weather is hidden off the nearby tab`() = runTest {
        val vm = viewModel()
        vm.onRegionValid(1L)
        advanceUntilIdle()
        vm.onNavItemSelected(HomeNavItem.STARRED_STOPS)
        assertNull(vm.uiState.value.weather)
    }

    @Test
    fun `an invalid region clears the weather`() = runTest {
        val vm = viewModel()
        vm.onRegionValid(1L)
        advanceUntilIdle()
        vm.onRegionValid(null)
        assertNull(vm.uiState.value.weather)
    }

    @Test
    fun `a weather fetch failure leaves the chip hidden`() = runTest {
        val vm = viewModel(weather = Result.failure(IOException("boom")))
        vm.onRegionValid(1L)
        advanceUntilIdle()
        assertNull(vm.uiState.value.weather)
    }

    @Test
    fun `weather is fetched once per region, again when the region changes`() = runTest {
        val repo = FakeWeatherRepository(Result.success(forecast))
        val vm = HomeViewModel(
            SavedStateHandle(), repo, FakeWideAlertsRepository(emptyList()),
            FakeRegionStatusRepository(), FakeStartupPreferencesRepository()
        )

        vm.onRegionValid(1L)
        advanceUntilIdle()
        vm.onRegionValid(1L) // same region: no refetch
        advanceUntilIdle()
        assertEquals(listOf(1L), repo.requestedRegions)

        vm.onRegionValid(2L) // new region: refetch
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), repo.requestedRegions)
    }

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
    fun `request commands emit the matching sheet events`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.requestToggleSheet()
        vm.requestCollapseSheet()
        advanceUntilIdle()

        assertEquals(listOf(HomeEvent.ToggleSheet, HomeEvent.CollapseSheet), events)
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
        assertEquals(ArrivalsSheetState.Collapsed, vm.uiState.value.settledSheet)
        job.cancel()
    }

    @Test
    fun `expanding over a focused stop sets padding and recenters`() = runTest {
        val vm = viewModel()
        vm.onStopFocused(FocusedStop("1", "Main St", "100", 47.6, -122.3))
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // reveal, skipped
        vm.onSheetSettled(ArrivalsSheetState.Expanded, 120)
        advanceUntilIdle()

        assertEquals(
            listOf(HomeEvent.SetMapPadding(120), HomeEvent.RecenterOnFocusedStop(47.6, -122.3)),
            events
        )
        job.cancel()
    }

    @Test
    fun `expanding with no focused stop only sets padding`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // reveal, skipped
        vm.onSheetSettled(ArrivalsSheetState.Expanded, 120)
        advanceUntilIdle()

        assertEquals(listOf<HomeEvent>(HomeEvent.SetMapPadding(120)), events)
        job.cancel()
    }

    @Test
    fun `collapsing and hiding set the map padding`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 120) // reveal, skipped
        vm.onSheetSettled(ArrivalsSheetState.Collapsed, 80)
        vm.onSheetSettled(ArrivalsSheetState.Hidden, 80)
        advanceUntilIdle()

        assertEquals(listOf(HomeEvent.SetMapPadding(80), HomeEvent.SetMapPadding(0)), events)
        assertEquals(ArrivalsSheetState.Hidden, vm.uiState.value.settledSheet)
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

    // --- dialogs ---

    @Test
    fun `showing and dismissing the help dialog updates state`() = runTest {
        val vm = viewModel()
        vm.showHelp(showContactUs = false)
        assertEquals(HomeDialog.Help, vm.uiState.value.dialog)
        assertFalse(vm.uiState.value.helpShowContactUs)
        vm.dismissDialog()
        assertEquals(HomeDialog.None, vm.uiState.value.dialog)
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
        weather: WeatherData? = WeatherData("clear-day", 70.0, null),
        mapLoading: Boolean = false,
        focusedStop: FocusedStop? = null,
        settledSheet: ArrivalsSheetState = ArrivalsSheetState.Hidden,
    ) = buildState(
        selected, emptyList(), env, weather, HomeDialog.None, true,
        focusedStop = focusedStop, mapLoading = mapLoading, settledSheet = settledSheet,
    )

    @Test
    fun `the settled sheet position passes through untouched`() {
        assertEquals(
            ArrivalsSheetState.Expanded,
            state(HomeNavItem.NEARBY, settledSheet = ArrivalsSheetState.Expanded).settledSheet
        )
    }

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
    fun `donation card needs nearby and availability`() {
        assertTrue(state(HomeNavItem.NEARBY, HomeEnvironment(donationAvailable = true)).donationVisible)
        assertFalse(state(HomeNavItem.NEARBY, HomeEnvironment(donationAvailable = false)).donationVisible)
        assertFalse(
            state(HomeNavItem.STARRED_STOPS, HomeEnvironment(donationAvailable = true)).donationVisible
        )
    }

    @Test
    fun `weather chip needs nearby data and a non-hidden preference`() {
        assertEquals("clear-day", state(HomeNavItem.NEARBY).weather?.icon)
        assertNull(state(HomeNavItem.NEARBY, HomeEnvironment(weatherHidden = true)).weather)
        assertNull(state(HomeNavItem.STARRED_STOPS).weather)
        assertNull(state(HomeNavItem.NEARBY, weather = null).weather)
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
