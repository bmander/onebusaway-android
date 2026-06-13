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

/**
 * Bridges the Java [org.onebusaway.android.map.googlemapsv2.BaseMapFragment] onto the
 * android-maps-compose `GoogleMap {}` composable. The fragment still drives the raw [GoogleMap]
 * imperatively (camera, overlays, listeners) exactly as before — this just hosts the map in a
 * [ComposeView] and hands the ready `GoogleMap` back via [onMapReady] (the fragment already
 * implements [OnMapReadyCallback]). Camera state is read/written through the raw map, so the
 * composable's CameraPositionState is only used to seed the initial position and avoid a flash.
 */
@JvmOverloads
fun createComposeMapView(
    context: Context,
    onMapReady: OnMapReadyCallback,
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
            cameraPositionState = cameraPositionState
        ) {
            // Runs once when the underlying GoogleMap is ready; reuses the fragment's existing
            // onMapReady() so all imperative setup + overlays stay unchanged (the MapEffect bridge).
            MapEffect(Unit) { map -> onMapReady.onMapReady(map) }
        }
    }
}
