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

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.rememberCameraPositionState
import org.onebusaway.android.map.compose.ObaComposeMapAdapter
import org.onebusaway.android.map.compose.ObaMapCallbacks
import org.onebusaway.android.map.compose.ObaMapReadyListener
import org.onebusaway.android.map.render.GeoPoint
import org.onebusaway.android.map.render.MapRenderState

/**
 * Google flavor's [ObaComposeMapAdapter]: renders the shared [MapRenderState] inside an
 * android-maps-compose `GoogleMap {}`. All overlay content is declarative ([ObaMapContent]); marker
 * and map taps come back through [ObaMapCallbacks]. When the underlying `GoogleMap` is ready (the
 * `MapEffect` bridge), it's handed to the host via a [GoogleMapHandle] so the host keeps driving the
 * raw map (styling, camera, location). `CameraPositionState` seeds the initial position (avoiding a
 * flash) and feeds the bike zoom-band; it is not the camera's owner.
 *
 * This is the former `ComposeMapHost.createComposeMapView` body, now behind the flavor-neutral
 * interface so `src/main` can resolve it by reflection.
 */
class GoogleComposeAdapter : ObaComposeMapAdapter {

    @Composable
    override fun Content(
        renderState: MapRenderState,
        callbacks: ObaMapCallbacks?,
        modifier: Modifier,
        initialLatitude: Double,
        initialLongitude: Double,
        initialZoom: Float,
        savedInstanceState: Bundle?,
        onMapReady: ObaMapReadyListener,
    ) {
        val cb = requireNotNull(callbacks) { "GoogleComposeAdapter requires ObaMapCallbacks" }
        val cameraPositionState = rememberCameraPositionState {
            if (initialLatitude != 0.0 || initialLongitude != 0.0) {
                position = CameraPosition.fromLatLngZoom(
                    LatLng(initialLatitude, initialLongitude), initialZoom
                )
            }
        }
        GoogleMap(
            modifier = modifier,
            cameraPositionState = cameraPositionState,
            // Now that every marker is declarative, maps-compose owns click dispatch. A tap on empty
            // map clears stop/bike focus; per-marker taps are handled in ObaMapContent.
            onMapClick = { latLng -> cb.onMapClick(GeoPoint(latLng.latitude, latLng.longitude)) },
        ) {
            // Runs once when the underlying GoogleMap is ready; hands it to the host (which keeps all
            // imperative setup) via the opaque handle.
            MapEffect(Unit) { map -> onMapReady.onMapReady(GoogleMapHandle(map)) }
            // Declarative overlay content (polylines, markers, vehicles, bikes, stops). The camera
            // state lets bike icons react live to the zoom band.
            ObaMapContent(renderState, cameraPositionState, cb)
        }
    }
}
