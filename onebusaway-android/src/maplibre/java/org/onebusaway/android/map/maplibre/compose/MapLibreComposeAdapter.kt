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

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.map.compose.BikeInfoWindow
import org.onebusaway.android.map.compose.ObaComposeMapAdapter
import org.onebusaway.android.map.compose.ObaMapCallbacks
import org.onebusaway.android.map.compose.VehicleInfoWindow
import org.onebusaway.android.map.maplibre.MapLibreRenderer
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.map.render.CameraSnapshot
import org.onebusaway.android.map.render.GeoPoint
import org.onebusaway.android.map.render.MapRenderState
import org.onebusaway.android.util.PermissionUtils
import kotlin.math.abs

private const val STYLE_URL_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark"

/**
 * maplibre flavor's [ObaComposeMapAdapter]: hosts the classic maplibre [MapView] inside an
 * [AndroidView] (there is no maplibre-compose), bridging the MapView's imperative lifecycle to Compose
 * via a [LifecycleEventObserver], and drives the [MapViewModel]: it sets the style, builds the
 * [MapLibreRenderer] and re-renders on every render-state change, wires map/marker/info-window clicks
 * to the view model + [callbacks], reports camera idles to [MapViewModel.onCameraIdle], applies the
 * dispatched camera intents from [MapRenderState.cameraCommands], and enables the location blue dot
 * from the view model's permission-derived flag. There is no imperative host.
 *
 * Lifecycle note: `addObserver` on an already-STARTED/RESUMED lifecycle synchronously dispatches the
 * upward events, so the MapView still receives `onStart`/`onResume` when it enters composition late.
 * `onCreate` happens once at [remember] time; `onDestroy` happens once in `onDispose`.
 */
class MapLibreComposeAdapter : ObaComposeMapAdapter {

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
        val context = LocalContext.current
        val activity = remember(context) { context.findActivity() }

        // MapLibre must be initialized before any MapView usage.
        remember(activity) { MapLibre.getInstance(activity) }

        // onCreate(null): MapView.onSaveInstanceState is never wired, so there's no saved MapView state
        // to restore — the camera/focus come from MapViewModel + persisted prefs.
        val mapView = remember { MapView(activity).apply { onCreate(null) } }

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

        var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
        var renderer by remember { mutableStateOf<MapLibreRenderer?>(null) }
        var loadedStyle by remember { mutableStateOf<Style?>(null) }

        DisposableEffect(mapView) {
            mapView.getMapAsync { map ->
                mapLibreMap = map
                if (initialLatitude != 0.0 || initialLongitude != 0.0) {
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(initialLatitude, initialLongitude))
                        .zoom(initialZoom.toDouble())
                        .build()
                }
                map.uiSettings.isCompassEnabled = false
                val styleUrl = if (isInDarkMode(context)) STYLE_URL_DARK else STYLE_URL_LIGHT
                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    loadedStyle = style
                    val r = MapLibreRenderer(map, context, renderState)
                    renderer = r
                    wireClicks(map, r, mapViewModel, callbacks)
                    installInfoWindowAdapter(map, r, activity)
                    map.addOnCameraIdleListener {
                        map.cameraPosition.target?.let { mapViewModel.onCameraIdle(snapshot(map, it)) }
                    }
                    r.render()
                }
            }
            onDispose { }
        }

        // Re-render the maplibre annotations on every render-state change (replacing the old imperative
        // rerender() calls), and enable the blue dot from the view model's permission flag.
        val activeRenderer = renderer
        if (activeRenderer != null) {
            LaunchedEffect(activeRenderer) {
                renderState.snapshot.collect { activeRenderer.render() }
            }
            val myLocationEnabled by mapViewModel.myLocationEnabled.collectAsState()
            val map = mapLibreMap
            val style = loadedStyle
            LaunchedEffect(myLocationEnabled, map, style) {
                if (myLocationEnabled && map != null && style != null) {
                    enableLocationComponent(map, style, context)
                }
            }
        }

        // Declarative camera: apply the dispatched camera intents to the map once ready.
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

/**
 * Wires map/marker/info-window taps to the view model + [callbacks] (the home-screen tap policy the
 * host used to install): a stop tap focuses + recenters via [callbacks] (which also drives the home
 * focus + analytics); a tap on empty map clears focus; vehicle/bike taps fall through so the classic
 * info window shows, and a tap on it deep links.
 */
private fun wireClicks(
    map: MapLibreMap,
    renderer: MapLibreRenderer,
    mapViewModel: MapViewModel,
    callbacks: ObaMapCallbacks?,
) {
    map.addOnMapClickListener {
        if (callbacks != null) callbacks.onMapClick(null) else mapViewModel.onMapTapped()
        false
    }
    map.setOnMarkerClickListener { marker ->
        val stop = renderer.stopForMarker(marker)
        if (stop != null) {
            if (callbacks != null) callbacks.onStopClick(stop.stop) else mapViewModel.onStopTapped(stop.stop)
            true
        } else {
            false
        }
    }
    map.setOnInfoWindowClickListener { marker ->
        val vehicle = renderer.vehicleForMarker(marker)
        if (vehicle != null) {
            callbacks?.onVehicleInfoWindowClick(vehicle.status)
            return@setOnInfoWindowClickListener true
        }
        val bike = renderer.bikeForMarker(marker)
        if (bike != null) {
            callbacks?.onBikeInfoWindowClick(bike.station)
            return@setOnInfoWindowClickListener true
        }
        false
    }
}

/**
 * Renders the shared vehicle/bike info-window composables as the maplibre info window (replacing the
 * classic title/snippet), so both map flavors show identical content. maplibre's custom info window
 * is a plain [View], so each is a themed [ComposeView]. Only the bike content is wrapped in a white
 * bubble here: [VehicleInfoWindow] draws its own card (matching Google, where it's a `MarkerInfoWindow`
 * with no SDK chrome), whereas [BikeInfoWindow] is background-free (Google draws its bubble via
 * `MarkerInfoWindowContent`), so maplibre supplies the bubble in its stead.
 */
private fun installInfoWindowAdapter(
    map: MapLibreMap,
    renderer: MapLibreRenderer,
    activity: Activity,
) {
    map.setInfoWindowAdapter { marker ->
        val vehicle = renderer.vehicleForMarker(marker)
        val response = renderer.vehicleResponse()
        if (vehicle != null && response != null) {
            return@setInfoWindowAdapter infoWindowComposeView(activity) {
                VehicleInfoWindow(vehicle.status, response)
            }
        }
        val bike = renderer.bikeForMarker(marker)
        if (bike != null) {
            return@setInfoWindowAdapter infoWindowComposeView(activity) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(8.dp),
                    shadowElevation = 2.dp,
                ) {
                    BikeInfoWindow(bike.station)
                }
            }
        }
        null
    }
}

/**
 * A [ComposeView] hosting [content] in [ObaTheme] for use as a maplibre info window. The ViewTree
 * owners are set from the host [activity] (also the view's [Context]) because the info-window view is
 * added outside the activity's normal content view; the composition is disposed when the window
 * detaches.
 */
private fun infoWindowComposeView(
    activity: Activity,
    content: @Composable () -> Unit,
): View = ComposeView(activity).apply {
    setViewTreeLifecycleOwner(activity as LifecycleOwner)
    setViewTreeViewModelStoreOwner(activity as ViewModelStoreOwner)
    setViewTreeSavedStateRegistryOwner(activity as SavedStateRegistryOwner)
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    setContent { ObaTheme { content() } }
}

/** Builds a [CameraSnapshot] from the maplibre map's current camera + visible region. */
private fun snapshot(map: MapLibreMap, target: LatLng): CameraSnapshot {
    val bounds = map.projection.visibleRegion.latLngBounds
    val north = bounds.getLatNorth()
    val south = bounds.getLatSouth()
    val east = bounds.getLonEast()
    val west = bounds.getLonWest()
    return CameraSnapshot(
        center = GeoPoint(target.latitude, target.longitude),
        zoom = map.cameraPosition.zoom,
        latSpan = abs(north - south),
        lonSpan = abs(east - west),
        southWest = GeoPoint(south, west),
        northEast = GeoPoint(north, east),
    )
}

@SuppressLint("MissingPermission")
private fun enableLocationComponent(map: MapLibreMap, style: Style, context: Context) {
    if (!PermissionUtils.hasGrantedAtLeastOnePermission(context, PermissionUtils.LOCATION_PERMISSIONS)) {
        return
    }
    val component = map.locationComponent
    if (!component.isLocationComponentActivated) {
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style).build()
        )
        component.cameraMode = CameraMode.NONE
        component.renderMode = RenderMode.COMPASS
    }
    component.isLocationComponentEnabled = true
}

/** Mirrors the former MapLibreMapHost.inDarkMode: app night-mode override, else the system config. */
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

private fun Context.findActivity(): Activity {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    throw IllegalStateException("MapLibreComposeAdapter must be hosted in an Activity context")
}
