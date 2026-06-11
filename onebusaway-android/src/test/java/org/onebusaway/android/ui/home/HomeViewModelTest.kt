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
    override suspend fun currentForecast(regionId: Long): Result<WeatherData> = result
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
    ) = HomeViewModel(FakeWeatherRepository(weather), FakeWideAlertsRepository(alerts))

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
    ) = buildState(selected, emptyList(), env, weather, HomeDialog.NONE, true)

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
    fun `starred menu groups follow the selected tab`() {
        assertTrue(state(HomeNavItem.STARRED_STOPS).showStarredStopsMenu)
        assertFalse(state(HomeNavItem.STARRED_STOPS).showStarredRoutesMenu)
        assertTrue(state(HomeNavItem.STARRED_ROUTES).showStarredRoutesMenu)
        assertFalse(state(HomeNavItem.NEARBY).showStarredStopsMenu)
    }
}
