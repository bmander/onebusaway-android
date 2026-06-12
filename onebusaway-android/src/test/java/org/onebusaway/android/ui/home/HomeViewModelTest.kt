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
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = HomeViewModel(savedState, FakeWeatherRepository(weather), FakeWideAlertsRepository(alerts))

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
        val vm = HomeViewModel(SavedStateHandle(), repo, FakeWideAlertsRepository(emptyList()))

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
    fun `request commands emit the matching sheet and drawer events`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<HomeEvent>()
        val job = launch { vm.events.collect { events.add(it) } }
        advanceUntilIdle()

        vm.requestToggleSheet()
        vm.requestCollapseSheet()
        vm.requestOpenDrawer()
        advanceUntilIdle()

        assertEquals(
            listOf(HomeEvent.ToggleSheet, HomeEvent.CollapseSheet, HomeEvent.OpenDrawer),
            events
        )
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
        assertEquals(HomeDialog.HELP, vm.uiState.value.dialog)
        assertFalse(vm.uiState.value.helpShowContactUs)
        vm.dismissDialog()
        assertEquals(HomeDialog.NONE, vm.uiState.value.dialog)
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
    ) = buildState(
        selected, emptyList(), env, weather, HomeDialog.NONE, true,
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
