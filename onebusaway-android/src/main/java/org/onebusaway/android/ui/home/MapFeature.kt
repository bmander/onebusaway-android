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

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.firebase.analytics.FirebaseAnalytics
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.io.elements.ObaTripStatus
import org.onebusaway.android.map.LayerInfo
import org.onebusaway.android.map.MapEffect
import org.onebusaway.android.map.MapNavigation
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.map.compose.ObaMap
import org.onebusaway.android.map.compose.ObaMapCallbacks
import org.onebusaway.android.map.render.GeoPoint
import org.onebusaway.android.util.LayerUtils
import org.onebusaway.android.util.PermissionUtils
import org.onebusaway.android.util.PreferenceUtils
import org.opentripplanner.routing.bike_rental.BikeRentalStation

/**
 * The self-wiring map feature module: renders [ObaMap] and owns everything that used to be map glue
 * in HomeActivity — the tap callbacks (focus -> the map view model + the home focused stop +
 * analytics; info-window taps -> navigation), the state collectors (loading -> the home loading
 * indicator; valid-region -> wide alerts + weather), the one-shot effects (the out-of-range /
 * no-location / permission-rationale dialogs + the my-location toast + the permission request, now
 * Compose-native), the eager first-launch permission prompt, and the resume/pause lifecycle. Mirrors
 * the survey / donation / weather / help feature modules; the host just places it.
 *
 * It drives [homeViewModel] (focus + loading + region) because the home screen's arrivals sheet +
 * chrome react to map focus/loading — that map→home coupling is inherent to the screen.
 */
@Composable
fun MapFeature(
    mapViewModel: MapViewModel,
    homeViewModel: HomeViewModel,
    mapComposed: Boolean,
    mapSeedLat: Double,
    mapSeedLon: Double,
    mapSeedZoom: Float,
    mapSavedInstanceState: Bundle?,
    fabBottomInset: Dp,
    onBikeshareToggled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val firebaseAnalytics = remember { FirebaseAnalytics.getInstance(context) }

    // Compose-native permission launcher: deliver the result to the map view model (blue dot) + the
    // home view model (the deferred first-launch region check).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        mapViewModel.onLocationPermissionResult(granted)
        homeViewModel.onLocationPermissionResult()
    }

    val callbacks = remember(mapViewModel, homeViewModel, firebaseAnalytics) {
        object : ObaMapCallbacks {
            override fun onStopClick(stop: ObaStop) {
                mapViewModel.onStopTapped(stop)
                // Already focused on this stop? Then don't re-fire the home focus + analytics.
                val focusedId = homeViewModel.uiState.value.focusedStop?.id
                if (focusedId != null && focusedId.equals(stop.id, ignoreCase = true)) {
                    return
                }
                homeViewModel.onStopFocused(
                    FocusedStop(stop.id, stop.name, stop.stopCode, stop.latitude, stop.longitude)
                )
                ObaAnalytics.reportUiEvent(
                    firebaseAnalytics,
                    Application.get().plausibleInstance,
                    PlausibleAnalytics.REPORT_MAP_EVENT_URL,
                    context.getString(R.string.analytics_label_button_press_map_icon),
                    null,
                )
            }

            override fun onMapClick(point: GeoPoint?) {
                mapViewModel.onMapTapped()
                homeViewModel.onStopFocused(null)
                homeViewModel.onBikeStationFocused(null)
            }

            override fun onBikeClick(station: BikeRentalStation) {
                val bikeId = homeViewModel.uiState.value.focusedBikeStationId
                if (bikeId == null || !bikeId.equals(station.id, ignoreCase = true)) {
                    homeViewModel.onBikeStationFocused(station.id)
                }
                ObaAnalytics.reportUiEvent(
                    firebaseAnalytics,
                    Application.get().plausibleInstance,
                    PlausibleAnalytics.REPORT_BIKE_EVENT_URL,
                    context.getString(
                        if (station.isFloatingBike) {
                            R.string.analytics_label_bike_station_marker_clicked
                        } else {
                            R.string.analytics_label_floating_bike_marker_clicked
                        }
                    ),
                    null,
                )
            }

            override fun onVehicleInfoWindowClick(status: ObaTripStatus) {
                MapNavigation.openVehicleTripDetails(
                    context, status, homeViewModel.uiState.value.focusedStop?.id
                )
            }

            override fun onBikeInfoWindowClick(station: BikeRentalStation) {
                MapNavigation.openBikeDeepLink(context, station)
            }
        }
    }

    // Map loading indicator, driven from the map view model. (Weather now self-subscribes to the region
    // repository; the wide-alert region signal still rides regionValid below until that migrates too.)
    LaunchedEffect(mapViewModel) {
        mapViewModel.progress.collect { homeViewModel.onMapLoading(it) }
    }
    LaunchedEffect(mapViewModel) {
        mapViewModel.regionValid.collect { valid ->
            val regionId = if (valid) Application.get().currentRegion?.id else null
            homeViewModel.onRegionValid(regionId)
        }
    }

    // One-shot effects -> Compose dialogs / the permission launcher / a toast.
    var dialog by remember { mutableStateOf<MapEffect?>(null) }
    LaunchedEffect(mapViewModel) {
        mapViewModel.effects.collect { effect ->
            when (effect) {
                MapEffect.OutOfRange,
                MapEffect.NoLocation,
                MapEffect.ShowPermissionRationale -> dialog = effect
                MapEffect.RequestLocationPermission ->
                    permissionLauncher.launch(PermissionUtils.LOCATION_PERMISSIONS)
                MapEffect.WaitingForLocation -> Toast.makeText(
                    context, R.string.main_waiting_for_location, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Eager first-launch permission prompt when the map first shows (was the host's initMap prompt;
    // also drives the deferred first-launch region check via the permission result).
    LaunchedEffect(mapComposed) {
        if (mapComposed) {
            mapViewModel.requestLocationPermissionIfNeeded()
        }
    }

    // Resume/pause: the map view model restarts its vehicle poll + refreshes prefs on resume, and
    // persists the camera + stops the poll on pause.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewModel.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewModel.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (dialog) {
        MapEffect.OutOfRange -> OutOfRangeDialog(
            onConfirm = { mapViewModel.zoomToRegion(); dialog = null },
            onDismiss = { dialog = null },
        )
        MapEffect.NoLocation -> NoLocationDialog(
            onEnable = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        MapEffect.ShowPermissionRationale -> PermissionRationaleDialog(
            onOk = {
                PreferenceUtils.setUserDeniedLocationPermissions(false)
                permissionLauncher.launch(PermissionUtils.LOCATION_PERMISSIONS)
                dialog = null
            },
            onNoThanks = {
                PreferenceUtils.setUserDeniedLocationPermissions(true)
                mapViewModel.onLocationPermissionResult(false)
                homeViewModel.onLocationPermissionResult()
                dialog = null
            },
        )
        else -> {}
    }

    // The map itself, gated so the SDK only initializes once NEARBY is first shown (then stays).
    if (mapComposed) {
        ObaMap(
            renderState = mapViewModel.renderState,
            callbacks = callbacks,
            modifier = modifier,
            mapViewModel = mapViewModel,
            initialLatitude = mapSeedLat,
            initialLongitude = mapSeedLon,
            initialZoom = mapSeedZoom,
            savedInstanceState = mapSavedInstanceState,
        )
    }

    // The map chrome FABs (my-location / zoom / layers), over the map. Their actions drive the map view
    // model directly; the bikeshare toggle also pings the host to re-snapshot the chrome environment.
    val state by homeViewModel.uiState.collectAsStateWithLifecycle()
    MapChrome(
        fabsVisible = state.fabsVisible,
        zoomVisible = state.zoomControlsVisible,
        leftHandMode = state.leftHandMode,
        layersVisible = state.layersFabVisible,
        bikeshareActive = state.bikeshareActive,
        mapLoading = state.mapLoading,
        fabBottomInsetTarget = fabBottomInset,
        onMyLocation = {
            // Reset the prefs that suppress the enable-location / permission prompts, then recenter.
            PreferenceUtils.saveBoolean(
                context.getString(R.string.preference_key_never_show_location_dialog), false
            )
            PreferenceUtils.setUserDeniedLocationPermissions(false)
            mapViewModel.requestMyLocation(useDefaultZoom = true, animate = true)
            ObaAnalytics.reportUiEvent(
                firebaseAnalytics,
                Application.get().plausibleInstance,
                PlausibleAnalytics.REPORT_MAP_EVENT_URL,
                context.getString(R.string.analytics_label_button_press_location),
                null,
            )
        },
        onZoomIn = { mapViewModel.zoomIn() },
        onZoomOut = { mapViewModel.zoomOut() },
        onToggleBikeshare = {
            val active = LayerUtils.isBikeshareLayerVisible()
            val layer: LayerInfo = LayerUtils.bikeshareLayerInfo
            // Persist the toggled state + drive the bike loader, then ping the host to re-snapshot the
            // environment (so the bikeshare-active tint updates).
            Application.getPrefs().edit().putBoolean(layer.sharedPreferenceKey, !active).apply()
            mapViewModel.setBikeshareLayerVisible(!active)
            ObaAnalytics.reportUiEvent(
                firebaseAnalytics,
                Application.get().plausibleInstance,
                PlausibleAnalytics.REPORT_MAP_EVENT_URL,
                context.getString(R.string.analytics_layer_bikeshare),
                context.getString(
                    if (active) {
                        R.string.analytics_label_bikeshare_deactivated
                    } else {
                        R.string.analytics_label_bikeshare_activated
                    }
                ),
            )
            onBikeshareToggled()
        },
    )
}

/** The viewport (or device) is outside the current region (ported from GoogleMapHost.showOutOfRange). */
@Composable
private fun OutOfRangeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val regionName = Application.get().currentRegion?.name ?: ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.main_outofrange_title)) },
        text = { Text(stringResource(R.string.main_outofrange, regionName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.main_outofrange_yes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.main_outofrange_no)) }
        },
    )
}

/** Location services are off (ported from GoogleMapHost.showNoLocationDialog + its never-ask opt-out). */
@Composable
private fun NoLocationDialog(onEnable: () -> Unit, onDismiss: () -> Unit) {
    var neverAskAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.main_nolocation_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.main_nolocation, stringResource(R.string.app_name)))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = neverAskAgain,
                        onCheckedChange = {
                            neverAskAgain = it
                            PreferenceUtils.saveBoolean(
                                Application.get()
                                    .getString(R.string.preference_key_never_show_location_dialog),
                                it,
                            )
                        },
                    )
                    Text(stringResource(R.string.main_never_ask_again))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onEnable) { Text(stringResource(R.string.rt_yes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rt_no)) }
        },
    )
}

/** Why location permission is needed (ported from GoogleMapHost.showLocationPermissionDialog). */
@Composable
private fun PermissionRationaleDialog(onOk: () -> Unit, onNoThanks: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNoThanks,
        title = { Text(stringResource(R.string.location_permissions_title)) },
        text = { Text(stringResource(R.string.location_permissions_message)) },
        confirmButton = {
            TextButton(onClick = onOk) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onNoThanks) { Text(stringResource(R.string.no_thanks)) }
        },
    )
}
