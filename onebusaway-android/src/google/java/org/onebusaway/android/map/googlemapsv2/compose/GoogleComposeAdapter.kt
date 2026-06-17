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
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import org.onebusaway.android.R
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.map.compose.ObaComposeMapAdapter
import org.onebusaway.android.map.compose.ObaMapCallbacks
import org.onebusaway.android.map.render.CameraSnapshot
import org.onebusaway.android.map.render.GeoPoint
import org.onebusaway.android.map.render.MapRenderState

/**
 * Google flavor's [ObaComposeMapAdapter]: renders the shared [MapRenderState] inside an
 * android-maps-compose `GoogleMap {}` and drives the [MapViewModel]. All overlay content is
 * declarative ([ObaMapContent]); marker and map taps come back through [ObaMapCallbacks]; the live
 * camera is published to the view model on each idle, and styling + the my-location blue dot are
 * applied from the view model's state. `CameraPositionState` seeds the initial position (avoiding a
 * flash) and feeds the bike zoom-band.
 */
class GoogleComposeAdapter : ObaComposeMapAdapter {

    @Composable
    override fun Content(
        renderState: MapRenderState,
        callbacks: ObaMapCallbacks?,
        mapViewModel: MapViewModel,
        modifier: Modifier,
        initialLatitude: Double,
        initialLongitude: Double,
        initialZoom: Float,
    ) {
        val cb = requireNotNull(callbacks) { "GoogleComposeAdapter requires ObaMapCallbacks" }
        val cameraPositionState = rememberCameraPositionState {
            if (initialLatitude != 0.0 || initialLongitude != 0.0) {
                position = CameraPosition.fromLatLngZoom(
                    LatLng(initialLatitude, initialLongitude), initialZoom
                )
            }
        }
        // Declarative map padding (route-header top + arrivals-sheet bottom), applied as the map's
        // contentPadding instead of an imperative mapView.setPadding(...) poke from the activity.
        val padding by renderState.padding.collectAsState()
        val density = LocalDensity.current
        val context = LocalContext.current
        // Declarative camera: apply the host-dispatched camera intents against this CameraPositionState
        // (replacing the host's direct mMap.animateCamera/moveCamera calls).
        LaunchedEffect(cameraPositionState) {
            renderState.cameraCommands.collect { command ->
                applyCameraCommand(command, cameraPositionState, renderState, context)
            }
        }
        // Camera read-back: publish the live camera to the view model on each idle so its reactive
        // loaders can react to pan/zoom (the declarative replacement for the old onCameraChange /
        // MapWatcher). snapshotFlow re-emits when isMoving/projection/position change; the value-typed
        // CameraSnapshot lets distinctUntilChanged drop redundant idles.
        LaunchedEffect(cameraPositionState, mapViewModel) {
            snapshotFlow {
                if (cameraPositionState.isMoving) {
                    null
                } else {
                    cameraPositionState.projection?.let { projection ->
                        val pos = cameraPositionState.position
                        val bounds = projection.visibleRegion.latLngBounds
                        val sw = bounds.southwest
                        val ne = bounds.northeast
                        CameraSnapshot(
                            center = GeoPoint(pos.target.latitude, pos.target.longitude),
                            zoom = pos.zoom.toDouble(),
                            latSpan = ne.latitude - sw.latitude,
                            lonSpan = ne.longitude - sw.longitude,
                            southWest = GeoPoint(sw.latitude, sw.longitude),
                            northEast = GeoPoint(ne.latitude, ne.longitude),
                        )
                    }
                }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { mapViewModel.onCameraIdle(it) }
        }
        // Declarative styling + my-location: the blue dot tracks the view model's permission-derived
        // flag, and the map style is the dark theme or POI-removal (was the host's onMapReady + initMap).
        val myLocationEnabled by mapViewModel.myLocationEnabled.collectAsState()
        val properties = remember(myLocationEnabled, context) {
            MapProperties(
                isMyLocationEnabled = myLocationEnabled,
                mapStyleOptions = resolveMapStyle(context),
            )
        }
        val uiSettings = remember {
            MapUiSettings(
                myLocationButtonEnabled = false,
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
            )
        }
        GoogleMap(
            modifier = modifier,
            cameraPositionState = cameraPositionState,
            properties = properties,
            uiSettings = uiSettings,
            contentPadding = PaddingValues(
                top = with(density) { padding.topPx.toDp() },
                bottom = with(density) { padding.bottomPx.toDp() },
            ),
            // Now that every marker is declarative, maps-compose owns click dispatch. A tap on empty
            // map clears stop/bike focus; per-marker taps are handled in ObaMapContent.
            onMapClick = { latLng -> cb.onMapClick(GeoPoint(latLng.latitude, latLng.longitude)) },
        ) {
            // Declarative overlay content (polylines, markers, vehicles, bikes, stops). The camera
            // state lets bike icons react live to the zoom band.
            ObaMapContent(renderState, cameraPositionState, cb)
        }
    }
}

/** The map style for the current night-mode state: the dark theme, or POI removal in light mode. */
private fun resolveMapStyle(context: Context): MapStyleOptions =
    if (isInDarkMode(context)) {
        MapStyleOptions.loadRawResourceStyle(context, R.raw.dark_map)
    } else {
        // Light mode: just hide POIs (ported from GoogleMapHost.onMapReady).
        MapStyleOptions(
            "[{\"featureType\":\"poi\",\"elementType\":\"all\",\"stylers\":[{\"visibility\":\"off\"}]}]"
        )
    }

/** Mirrors the former GoogleMapHost.inDarkMode: app night-mode override, else the system config. */
private fun isInDarkMode(context: Context): Boolean {
    val mode = AppCompatDelegate.getDefaultNightMode()
    if (mode == AppCompatDelegate.MODE_NIGHT_YES) {
        return true
    }
    if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
        return false
    }
    return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
}
