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
package org.onebusaway.android.ui.arrivals

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.onebusaway.android.testing.MainDispatcherRule

private class FakeArrivalsRepository(
    var result: Result<ArrivalsData>
) : ArrivalsRepository {

    val requestedMinutesAfter = mutableListOf<Int>()

    var lastFavoriteSet: Pair<String, Boolean>? = null

    override suspend fun getArrivals(
        stopId: String,
        minutesAfter: Int,
        routeFilter: Set<String>
    ): Result<ArrivalsData> {
        requestedMinutesAfter.add(minutesAfter)
        return result
    }

    override suspend fun setStopFavorite(stopId: String, favorite: Boolean) {
        lastFavoriteSet = stopId to favorite
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ArrivalsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun header(favorite: Boolean = false) =
        StopHeader("1_100", "Pine St & 3rd Ave", "S", favorite, routeCount = 4)

    private fun data(minutesAfter: Int = 65, isStale: Boolean = false, favorite: Boolean = false) =
        ArrivalsData(emptyList(), header(favorite), minutesAfter, style = 0, isStale = isStale)

    @Test
    fun `initial state is Loading`() = runTest {
        val viewModel = ArrivalsViewModel("1_100", FakeArrivalsRepository(Result.success(data())))

        assertEquals(ArrivalsUiState.Loading, viewModel.state.value)
    }

    @Test
    fun `refresh emits Content on success`() = runTest {
        val viewModel = ArrivalsViewModel("1_100", FakeArrivalsRepository(Result.success(data())))

        viewModel.refresh()

        val state = viewModel.state.value
        assertTrue(state is ArrivalsUiState.Content)
        assertEquals("Pine St & 3rd Ave", (state as ArrivalsUiState.Content).header.name)
    }

    @Test
    fun `refresh emits Error when there is no content and the load fails`() = runTest {
        val viewModel = ArrivalsViewModel(
            "1_100",
            FakeArrivalsRepository(Result.failure(IOException("No network")))
        )

        viewModel.refresh()

        assertEquals(ArrivalsUiState.Error("No network"), viewModel.state.value)
    }

    @Test
    fun `a failed poll keeps existing content instead of showing Error`() = runTest {
        val repository = FakeArrivalsRepository(Result.success(data()))
        val viewModel = ArrivalsViewModel("1_100", repository)
        viewModel.refresh()
        assertTrue(viewModel.state.value is ArrivalsUiState.Content)

        repository.result = Result.failure(IOException("blip"))
        viewModel.refresh()

        assertTrue(viewModel.state.value is ArrivalsUiState.Content)
    }

    @Test
    fun `the stale flag flows through to the Content state`() = runTest {
        val viewModel = ArrivalsViewModel(
            "1_100",
            FakeArrivalsRepository(Result.success(data(isStale = true)))
        )

        viewModel.refresh()

        assertTrue((viewModel.state.value as ArrivalsUiState.Content).isStale)
    }

    @Test
    fun `load more widens the time window on the next request`() = runTest {
        val repository = FakeArrivalsRepository(Result.success(data(minutesAfter = 65)))
        val viewModel = ArrivalsViewModel("1_100", repository)
        viewModel.refresh()

        viewModel.loadMore()
        advanceUntilIdle()

        // 65 (initial) then 125 (65 + 60 increment)
        assertEquals(listOf(65, 125), repository.requestedMinutesAfter)
    }

    @Test
    fun `toggle favorite optimistically updates the header and persists`() = runTest {
        val repository = FakeArrivalsRepository(Result.success(data(favorite = false)))
        val viewModel = ArrivalsViewModel("1_100", repository)
        viewModel.refresh()

        viewModel.toggleFavorite()

        assertTrue((viewModel.state.value as ArrivalsUiState.Content).header.isFavorite)
        advanceUntilIdle()
        assertEquals("1_100" to true, repository.lastFavoriteSet)
    }
}
