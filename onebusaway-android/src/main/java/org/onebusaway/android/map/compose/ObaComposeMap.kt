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

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.onebusaway.android.BuildConfig
import org.onebusaway.android.map.render.MapRenderState

/**
 * The flavor-neutral, declarative map surface. A flavor adapter (the Google `GoogleMap {}` content,
 * or the maplibre `MapView` wrapped in an `AndroidView`) implements [Content]; `src/main` selects the
 * implementation by reflection on `BuildConfig.MAP_COMPOSE_ADAPTER_CLASS`, mirroring the existing
 * `MAP_HOST_CLASS`/`MAP_FRAGMENT_CLASS` mechanism. The adapter renders the shared [MapRenderState]
 * and reports taps through [ObaMapCallbacks]; when the underlying map is ready it hands the host an
 * opaque [ObaMapHandle] so the host can keep driving its raw map (camera, styling, location).
 */
interface ObaComposeMapAdapter {

    @Composable
    fun Content(
        renderState: MapRenderState,
        callbacks: ObaMapCallbacks?,
        modifier: Modifier,
        initialLatitude: Double,
        initialLongitude: Double,
        initialZoom: Float,
        savedInstanceState: Bundle?,
        onMapReady: ObaMapReadyListener,
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
    modifier: Modifier = Modifier,
    initialLatitude: Double = 0.0,
    initialLongitude: Double = 0.0,
    initialZoom: Float = 16f,
    savedInstanceState: Bundle? = null,
    onMapReady: ObaMapReadyListener = ObaMapReadyListener { },
) {
    val adapter = remember { ObaComposeMapAdapter.newInstance() }
    adapter.Content(
        renderState,
        callbacks,
        modifier,
        initialLatitude,
        initialLongitude,
        initialZoom,
        savedInstanceState,
        onMapReady,
    )
}

/**
 * Java-friendly factory returning a [ComposeView] that hosts [ObaMap]. The (Java) flavor hosts build
 * their `getView()` from this instead of constructing a flavor-specific map view directly.
 */
fun createObaMapView(
    context: Context,
    renderState: MapRenderState,
    callbacks: ObaMapCallbacks?,
    initialLatitude: Double,
    initialLongitude: Double,
    initialZoom: Float,
    savedInstanceState: Bundle?,
    onMapReady: ObaMapReadyListener,
): View = ComposeView(context).apply {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent {
        ObaMap(
            renderState = renderState,
            callbacks = callbacks,
            modifier = Modifier.fillMaxSize(),
            initialLatitude = initialLatitude,
            initialLongitude = initialLongitude,
            initialZoom = initialZoom,
            savedInstanceState = savedInstanceState,
            onMapReady = onMapReady,
        )
    }
}
