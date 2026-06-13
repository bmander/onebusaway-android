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
package org.onebusaway.android.map.googlemapsv2.compose

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.rememberCameraPositionState
import org.onebusaway.android.map.render.MapRenderState

/**
 * Bridges the Java [org.onebusaway.android.map.googlemapsv2.GoogleMapHost] onto the
 * android-maps-compose `GoogleMap {}` composable. All overlay *content* is declarative now: the host
 * mutates [renderState] and [ObaMapContent] renders it; marker/map taps come back through
 * [callbacks]. The host still holds the ready raw [GoogleMap] (handed back via [onMapReady]) — but
 * only for camera reads/writes (projection, visible region, animateCamera) and the camera-change
 * signal that drives stop loading, which have no clean Compose equivalent. CameraPositionState seeds
 * the initial position (avoiding a flash) and feeds the bike zoom-band; it is not the camera's owner.
 */
@JvmOverloads
fun createComposeMapView(
    context: Context,
    renderState: MapRenderState,
    onMapReady: OnMapReadyCallback,
    callbacks: ObaMapCallbacks,
    initialLatitude: Double = 0.0,
    initialLongitude: Double = 0.0,
    initialZoom: Float = 16f
): View = ComposeView(context).apply {
    // The fragment owns the view-tree lifecycle; dispose with it.
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent {
        val cameraPositionState = rememberCameraPositionState {
            if (initialLatitude != 0.0 || initialLongitude != 0.0) {
                position = CameraPosition.fromLatLngZoom(
                    LatLng(initialLatitude, initialLongitude), initialZoom
                )
            }
        }
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            // Now that every marker is declarative, maps-compose owns click dispatch. A tap on empty
            // map clears stop/bike focus; per-marker taps are handled in ObaMapContent.
            onMapClick = { latLng -> callbacks.onMapClick(latLng) },
        ) {
            // Runs once when the underlying GoogleMap is ready; reuses the host's existing
            // onMapReady() so all imperative setup stays unchanged (the MapEffect bridge).
            MapEffect(Unit) { map -> onMapReady.onMapReady(map) }
            // Declarative overlay content (polylines, markers, vehicles, bikes, stops), driven by the
            // shared render state. The camera state lets bike icons react live to the zoom band.
            ObaMapContent(renderState, cameraPositionState, callbacks)
        }
    }
}
