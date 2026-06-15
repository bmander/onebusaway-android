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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.testing.MainDispatcherRule

private class FakeWeatherRepository(var result: Result<WeatherData>) : WeatherRepository {
    val requestedRegions = mutableListOf<Long>()
    override suspend fun currentForecast(regionId: Long): Result<WeatherData> {
        requestedRegions.add(regionId)
        return result
    }
}

/**
 * In-memory [PreferencesRepository] for JVM tests. Synchronous accessors read/write a backing map
 * keyed by the resource id (or string) so they round-trip; [observeBoolean] reports the constructor
 * [enabled] flag (the only reactive value WeatherViewModel cares about).
 */
private class FakePreferencesRepository(private val enabled: Boolean = true) : PreferencesRepository {
    private val values = mutableMapOf<Any, Any?>()

    override fun observeBoolean(keyRes: Int, default: Boolean): Flow<Boolean> = flowOf(enabled)

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: Any, default: T): T = (values[key] as T?) ?: default

    override fun getBoolean(keyRes: Int, default: Boolean) = read(keyRes, default)
    override fun getBoolean(key: String, default: Boolean) = read(key, default)
    override fun getString(keyRes: Int, default: String?) = read(keyRes, default)
    override fun getString(key: String, default: String?) = read(key, default)
    override fun getInt(keyRes: Int, default: Int) = read(keyRes, default)
    override fun getInt(key: String, default: Int) = read(key, default)
    override fun getLong(keyRes: Int, default: Long) = read(keyRes, default)
    override fun getLong(key: String, default: Long) = read(key, default)
    override fun getFloat(keyRes: Int, default: Float) = read(keyRes, default)
    override fun getFloat(key: String, default: Float) = read(key, default)

    override fun setBoolean(keyRes: Int, value: Boolean) { values[keyRes] = value }
    override fun setBoolean(key: String, value: Boolean) { values[key] = value }
    override fun setString(keyRes: Int, value: String?) { values[keyRes] = value }
    override fun setString(key: String, value: String?) { values[key] = value }
    override fun setInt(keyRes: Int, value: Int) { values[keyRes] = value }
    override fun setInt(key: String, value: Int) { values[key] = value }
    override fun setLong(keyRes: Int, value: Long) { values[keyRes] = value }
    override fun setLong(key: String, value: Long) { values[key] = value }
    override fun setFloat(keyRes: Int, value: Float) { values[keyRes] = value }
    override fun setFloat(key: String, value: Float) { values[key] = value }
}

/**
 * Unit tests for [WeatherViewModel]'s region-keyed forecast fetch (migrated from HomeViewModelTest when
 * weather became its own feature module). The hide-weather pref + the NEARBY-tab gate are Application /
 * Compose concerns, verified by equivalence rather than here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val forecast = WeatherData(icon = "clear-day", temperatureF = 70.0, summary = "Clear")

    @Test
    fun `forecast loads when a region is set`() = runTest {
        val regions = FakeRegionRepository()
        val vm = WeatherViewModel(FakeWeatherRepository(Result.success(forecast)), regions, FakePreferencesRepository())
        regions.emit(region(1))
        advanceUntilIdle()
        assertEquals(forecast, vm.state.value.data)
    }

    @Test
    fun `clearing the region clears the forecast`() = runTest {
        val regions = FakeRegionRepository(region(1))
        val vm = WeatherViewModel(FakeWeatherRepository(Result.success(forecast)), regions, FakePreferencesRepository())
        advanceUntilIdle()
        assertEquals(forecast, vm.state.value.data)
        regions.emit(null)
        advanceUntilIdle()
        assertNull(vm.state.value.data)
    }

    @Test
    fun `a fetch failure leaves the forecast null`() = runTest {
        val regions = FakeRegionRepository(region(1))
        val vm = WeatherViewModel(FakeWeatherRepository(Result.failure(IOException("boom"))), regions, FakePreferencesRepository())
        advanceUntilIdle()
        assertNull(vm.state.value.data)
    }

    @Test
    fun `the forecast is fetched once per region id, again when the region changes`() = runTest {
        val regions = FakeRegionRepository()
        val repo = FakeWeatherRepository(Result.success(forecast))
        val vm = WeatherViewModel(repo, regions, FakePreferencesRepository())

        regions.emit(region(1))
        advanceUntilIdle()
        regions.emit(region(1)) // same id: no refetch
        advanceUntilIdle()
        assertEquals(listOf(1L), repo.requestedRegions)

        regions.emit(region(2)) // new id: refetch
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), repo.requestedRegions)
    }
}
