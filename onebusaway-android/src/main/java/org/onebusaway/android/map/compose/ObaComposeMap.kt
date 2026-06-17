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
package org.onebusaway.android.map.compose

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.onebusaway.android.BuildConfig
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.map.render.MapRenderState

/**
 * The flavor-neutral, declarative map surface. A flavor adapter (the Google `GoogleMap {}` content,
 * or the maplibre `MapView` wrapped in an `AndroidView`) implements [Content]; `src/main` selects the
 * implementation by reflection on `BuildConfig.MAP_COMPOSE_ADAPTER_CLASS`. The adapter renders the
 * shared [MapRenderState], reports taps through [ObaMapCallbacks], and drives the [MapViewModel] (camera
 * read-back, styling, location) — there is no imperative host any more.
 */
interface ObaComposeMapAdapter {

    @Composable
    fun Content(
        renderState: MapRenderState,
        callbacks: ObaMapCallbacks?,
        mapViewModel: MapViewModel,
        modifier: Modifier,
        initialLatitude: Double,
        initialLongitude: Double,
        initialZoom: Float,
        savedInstanceState: Bundle?,
    )

    companion object {
        /** Reflectively builds the flavor adapter named by `BuildConfig.MAP_COMPOSE_ADAPTER_CLASS`. */
        fun newInstance(): ObaComposeMapAdapter =
            Class.forName(BuildConfig.MAP_COMPOSE_ADAPTER_CLASS)
                .getDeclaredConstructor()
                .newInstance() as ObaComposeMapAdapter
    }
}

/** The neutral map composable: resolves the flavor adapter once and renders its [ObaComposeMapAdapter.Content]. */
@Composable
fun ObaMap(
    renderState: MapRenderState,
    callbacks: ObaMapCallbacks?,
    mapViewModel: MapViewModel,
    modifier: Modifier = Modifier,
    initialLatitude: Double = 0.0,
    initialLongitude: Double = 0.0,
    initialZoom: Float = 16f,
    savedInstanceState: Bundle? = null,
) {
    val adapter = remember { ObaComposeMapAdapter.newInstance() }
    adapter.Content(
        renderState,
        callbacks,
        mapViewModel,
        modifier,
        initialLatitude,
        initialLongitude,
        initialZoom,
        savedInstanceState,
    )
}
