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
package org.onebusaway.android.map.maplibre.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.map.compose.ObaComposeMapAdapter
import org.onebusaway.android.map.compose.ObaMapCallbacks
import org.onebusaway.android.map.compose.ObaMapReadyListener
import org.onebusaway.android.map.render.MapRenderState

/**
 * maplibre flavor's [ObaComposeMapAdapter]: hosts the classic maplibre [MapView] inside an
 * [AndroidView] (there is no maplibre-compose), bridging the MapView's imperative lifecycle to Compose
 * via a [LifecycleEventObserver]. When the map is ready it hands a [MapLibreMapHandle] back to
 * `MapLibreMapHost`, which keeps doing all the setup it always has — style, the [MapLibreRenderer],
 * marker/info-window listeners, and the location component. Rendering aside, this adapter ignores
 * [callbacks] (the host wires its own marker listeners); it consumes [renderState]'s camera-command
 * flow declaratively (the host dispatches camera intents, this applies them to the map), and otherwise
 * just makes the maplibre map a composable with a correct Compose lifecycle.
 *
 * Lifecycle note: `addObserver` on an already-STARTED/RESUMED lifecycle synchronously dispatches the
 * upward events, so the MapView still receives `onStart`/`onResume` when it enters composition late.
 * `onCreate` happens once at [remember] time; `onDestroy` happens once in `onDispose` (so it is not
 * also driven from the observer, avoiding a double teardown).
 */
class MapLibreComposeAdapter : ObaComposeMapAdapter {

    @Composable
    override fun Content(
        renderState: MapRenderState,
        callbacks: ObaMapCallbacks?,
        // Ignored for now (the host still drives loads); the maplibre camera read-back to the view
        // model is wired directly in this adapter in a later phase (P4).
        mapViewModel: MapViewModel?,
        modifier: Modifier,
        initialLatitude: Double,
        initialLongitude: Double,
        initialZoom: Float,
        savedInstanceState: Bundle?,
        onMapReady: ObaMapReadyListener,
    ) {
        val context = LocalContext.current
        val activity = remember(context) { context.findActivity() }

        // MapLibre must be initialized before any MapView usage.
        remember(activity) { MapLibre.getInstance(activity) }

        val mapView = remember { MapView(activity).apply { onCreate(savedInstanceState) } }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, mapView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> { /* ON_CREATE handled at remember; ON_DESTROY handled in onDispose */ }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                mapView.onDestroy()
            }
        }

        // Hand the ready map (+ its MapView, for the host's onSaveInstanceState) to the host once, and
        // keep it so the camera-command collector below can drive it.
        var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
        DisposableEffect(mapView) {
            mapView.getMapAsync { map ->
                mapLibreMap = map
                onMapReady.onMapReady(MapLibreMapHandle(mapView, map))
            }
            onDispose { }
        }

        // Declarative camera: apply the host-dispatched camera intents to the map (replacing the host's
        // direct mMap.animateCamera/moveCamera calls), once the map is ready.
        val map = mapLibreMap
        if (map != null) {
            LaunchedEffect(map) {
                renderState.cameraCommands.collect { command ->
                    applyCameraCommand(command, map, renderState)
                }
            }
        }

        AndroidView(factory = { mapView }, modifier = modifier)
    }
}

private fun Context.findActivity(): Activity {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    throw IllegalStateException("MapLibreComposeAdapter must be hosted in an Activity context")
}
