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

import android.os.Bundle
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.io.request.ObaArrivalInfoResponse
import org.onebusaway.android.map.compose.ObaMap
import org.onebusaway.android.map.compose.ObaMapCallbacks
import org.onebusaway.android.map.compose.ObaMapReadyListener
import org.onebusaway.android.map.render.MapRenderState
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.weather.WeatherUtils

/**
 * The declarative home screen: a Compose `ModalNavigationDrawer` + [HomeTopBar] + Material3
 * `BottomSheetScaffold`, rendered from [HomeUiState] (state down) with taps dispatched through plain
 * lambda callbacks + [HomeViewModel] events (up). Replaces the imperative `HomeShellHost` bridge.
 *
 * The arrivals sheet inverts to declarative: **visibility is business state** — the sheet peeks iff
 * a stop is focused on NEARBY — driven by a [LaunchedEffect] keyed on that derived flag, so it never
 * fights a user drag. **Expansion (peek<->full)** is the live `SheetState`, nudged by one-shot
 * [HomeEvent.ToggleSheet]/[HomeEvent.CollapseSheet] commands (the screen alone knows the live state),
 * plus [BackHandler]. The arrivals panel is hosted directly per focused stop (see [ArrivalsSheetHost]);
 * the map is now a direct [ObaMap] composable (the only residual `AndroidView` is the legacy survey +
 * route-header overlay shim, removed as those are Composed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    events: SharedFlow<HomeEvent>,
    // The map is rendered directly as a composable (ObaMap), gated by [mapComposed] so SDK init stays
    // lazy until the first NEARBY selection. [legacyMapOverlays] is the slimmed home_map_content.xml
    // (survey + route-mode header) still drawn via AndroidView above the map until those are Composed.
    mapRenderState: MapRenderState,
    mapCallbacks: ObaMapCallbacks?,
    onMapReady: ObaMapReadyListener,
    mapSeedLat: Double,
    mapSeedLon: Double,
    mapSeedZoom: Float,
    mapSavedInstanceState: Bundle?,
    mapComposed: Boolean,
    legacyMapOverlays: View,
    listVms: HomeListViewModels,
    onNavItemSelected: (HomeNavItem) -> Unit,
    onSearch: (String) -> Unit,
    onRecentStopsRoutes: () -> Unit,
    onListSort: () -> Unit,
    onListClear: () -> Unit,
    onMyLocation: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onToggleBikeshare: () -> Unit,
    onWeatherClick: () -> Unit,
    onDonationClose: () -> Unit,
    onDonationLearnMore: () -> Unit,
    onDonationDonate: () -> Unit,
    onDonationDismissForever: () -> Unit,
    onDonationRemindLater: () -> Unit,
    onHelpAction: (HelpAction) -> Unit,
    onWhatsNewDismissed: () -> Unit,
    onRegionChosen: (ObaRegion) -> Unit,
    onDismissDialog: () -> Unit,
    onSheetSettled: (ArrivalsSheetState, Int) -> Unit,
    onClearFocus: () -> Unit,
    onArrivalsLoaded: (ObaArrivalInfoResponse) -> Unit,
    onShowRouteOnMap: (String) -> Unit,
    onToggleSheet: () -> Unit,
    onPreferredHeight: (previewCount: Int, filtering: Boolean) -> Unit,
) {
    ObaTheme {
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val sheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false
        )
        val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

        // The arrivals header height for the current preview count + filter offset (no drag handle).
        val peekHeaderDp = arrivalsPeekHeight(state.peekArrivalCount, state.routeFiltering)
        val peekHeaderPx = with(density) { peekHeaderDp.roundToPx() }

        // Visibility is business state. The key is the focused stop id while shown (else null), so the
        // effect reacts to focus/tab changes but NOT to a user drag (same stop -> same key); the
        // reconcile decision (hide / peek-open-if-hidden / leave) is the pure sheetReconcile().
        val showSheet = shouldShowSheet(state.focusedStop, state.selectedItem)
        val sheetKey = if (showSheet) state.focusedStop?.id else null
        LaunchedEffect(sheetKey) {
            runCatching {
                when (sheetReconcile(sheetKey != null, sheetState.currentValue.toArrivalsSheetState())) {
                    SheetReconcile.HIDE -> sheetState.hide()
                    SheetReconcile.PEEK_OPEN -> sheetState.partialExpand()
                    SheetReconcile.LEAVE -> {}
                }
            }
        }

        // Report the resting position back to the activity (map padding / recenter / arrivals preview).
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.currentValue }.collect { value ->
                onSheetSettled(value.toArrivalsSheetState(), peekHeaderPx)
            }
        }

        // One-shot sheet/drawer commands from the ViewModel (the screen holds the live states).
        LaunchedEffect(Unit) {
            events.collect { event ->
                when (event) {
                    HomeEvent.ToggleSheet -> runCatching {
                        when (toggleSheetTarget(sheetState.currentValue.toArrivalsSheetState())) {
                            ArrivalsSheetState.Expanded -> sheetState.expand()
                            else -> sheetState.partialExpand()
                        }
                    }
                    HomeEvent.CollapseSheet -> runCatching { sheetState.partialExpand() }
                    else -> {} // ShowWideAlert is handled by the activity
                }
            }
        }

        // Back collapses an expanded sheet first, then (from peek) clears the focus, which hides it.
        // A hidden sheet leaves back to the system (mirrors the legacy !isSheetHidden() gate).
        BackHandler(enabled = sheetState.currentValue != SheetValue.Hidden) {
            when (sheetBackAction(sheetState.currentValue.toArrivalsSheetState())) {
                SheetBackAction.COLLAPSE -> scope.launch { runCatching { sheetState.partialExpand() } }
                SheetBackAction.CLEAR_FOCUS -> onClearFocus()
                SheetBackAction.NONE -> {}
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            // Gestures only while open: a left-edge drag on the map must NOT open the drawer (it pans
            // the map; the drawer opens via the toolbar hamburger), but once open a scrim tap or swipe
            // should close it. Material3 gates both the open-swipe and the scrim tap-to-close on this
            // one flag, so tie it to the open state.
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                HomeNavDrawerSheet(items = state.navItems, selected = state.selectedItem) { item ->
                    scope.launch { drawerState.close() }
                    onNavItemSelected(item)
                }
            }
        ) {
            // The TopAppBar applies its own top window inset (status bar), so the Column doesn't.
            Column(Modifier.fillMaxSize()) {
                HomeTopBar(
                    title = stringResource(state.selectedItem.titleRes()),
                    showSort = state.showListSortMenu,
                    showClear = state.showListClearMenu,
                    clearLabel = if (state.selectedItem == HomeNavItem.STARRED_ROUTES) {
                        R.string.my_option_clear_starred_routes
                    } else {
                        R.string.my_option_clear_starred_stops
                    },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onSearch = onSearch,
                    onSort = onListSort,
                    onClear = onListClear,
                    onRecentStopsRoutes = onRecentStopsRoutes,
                )
                BottomSheetScaffold(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    scaffoldState = scaffoldState,
                    // The reported header height, plus room for the drag handle above it.
                    sheetPeekHeight = peekHeaderDp + DRAG_HANDLE_ALLOWANCE,
                    sheetContent = {
                        ArrivalsSheetHost(
                            focusedStop = state.focusedStop,
                            collapsed = sheetState.currentValue != SheetValue.Expanded,
                            onArrivalsLoaded = onArrivalsLoaded,
                            onShowRouteOnMap = onShowRouteOnMap,
                            onToggleSheet = onToggleSheet,
                            onPreferredHeight = onPreferredHeight,
                        )
                    }
                ) {
                    // Lift the FABs above the sheet peek when it's collapsed (replaces the legacy
                    // moveFabsLocation() margin animation). The target changes only when the sheet
                    // settles; MapChrome animates it so the per-frame value doesn't recompose this Box.
                    val fabInsetTarget = if (sheetState.currentValue == SheetValue.PartiallyExpanded) {
                        peekHeaderDp
                    } else {
                        0.dp
                    }
                    Box(Modifier.fillMaxSize()) {
                        // The map itself, composed directly (no View seam). Gated so the map SDK only
                        // initializes once NEARBY is first shown, then stays composed (a list tab draws
                        // an opaque destination over it rather than tearing it down).
                        if (mapComposed) {
                            ObaMap(
                                renderState = mapRenderState,
                                callbacks = mapCallbacks,
                                modifier = Modifier.fillMaxSize(),
                                initialLatitude = mapSeedLat,
                                initialLongitude = mapSeedLon,
                                initialZoom = mapSeedZoom,
                                savedInstanceState = mapSavedInstanceState,
                                onMapReady = onMapReady,
                            )
                        }
                        // Legacy View overlays (survey card + route-mode header) above the map. They are
                        // transparent until shown, and are reached via findViewById on the activity, so
                        // they must stay attached. (Composed away in later phases.)
                        AndroidView(factory = { legacyMapOverlays }, modifier = Modifier.fillMaxSize())
                        MapChrome(
                            fabsVisible = state.fabsVisible,
                            zoomVisible = state.zoomControlsVisible,
                            leftHandMode = state.leftHandMode,
                            layersVisible = state.layersFabVisible,
                            bikeshareActive = state.bikeshareActive,
                            mapLoading = state.mapLoading,
                            fabBottomInsetTarget = fabInsetTarget,
                            onMyLocation = onMyLocation,
                            onZoomIn = onZoomIn,
                            onZoomOut = onZoomOut,
                            onToggleBikeshare = onToggleBikeshare,
                        )
                        val weather = state.weather
                        if (weather != null) {
                            WeatherCard(
                                iconRes = WeatherUtils.getWeatherIconRes(weather.icon),
                                tempText = WeatherUtils.formatTemperature(weather.temperatureF),
                                fitIcon = WeatherUtils.isFitIcon(weather.icon),
                                onClick = onWeatherClick,
                                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                            )
                        }
                        if (state.donationVisible) {
                            DonationCard(
                                onClose = onDonationClose,
                                onLearnMore = onDonationLearnMore,
                                onDonate = onDonationDonate,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 62.dp)
                            )
                        }
                        // A selected list tab draws its destination over the map (an opaque, full-size
                        // Surface), covering the map chrome; NEARBY shows the map through.
                        when (state.selectedItem) {
                            HomeNavItem.STARRED_STOPS -> StarredStopsDestination(listVms.starredStops)
                            HomeNavItem.STARRED_ROUTES -> StarredRoutesDestination(listVms.starredRoutes)
                            HomeNavItem.MY_REMINDERS -> RemindersDestination(listVms.reminders)
                            else -> {}
                        }
                    }
                }
            }
        }

        HomeDialogs(
            dialog = state.dialog,
            showContactUs = state.helpShowContactUs,
            onHelpAction = onHelpAction,
            onWhatsNewDismissed = onWhatsNewDismissed,
            onDonationDismissForever = onDonationDismissForever,
            onDonationRemindLater = onDonationRemindLater,
            onRegionChosen = onRegionChosen,
            onDismiss = onDismissDialog
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun SheetValue.toArrivalsSheetState() = when (this) {
    SheetValue.Hidden -> ArrivalsSheetState.Hidden
    SheetValue.PartiallyExpanded -> ArrivalsSheetState.Collapsed
    SheetValue.Expanded -> ArrivalsSheetState.Expanded
}

/** Maps the arrivals preview count + route-filter flag to the collapsed peek header height. */
@Composable
private fun arrivalsPeekHeight(arrivalCount: Int, filtering: Boolean): Dp {
    val base = dimensionResource(
        when (arrivalsPeekTier(arrivalCount)) {
            ArrivalsPeekTier.TWO_OR_MORE -> R.dimen.arrival_header_height_two_arrivals
            ArrivalsPeekTier.ONE -> R.dimen.arrival_header_height_one_arrival
            ArrivalsPeekTier.NONE -> R.dimen.arrival_header_height_no_arrivals
        }
    )
    val offset = if (filtering) {
        dimensionResource(R.dimen.arrival_header_height_offset_filter_routes)
    } else {
        0.dp
    }
    return base + offset
}

/**
 * Extra peek height for the scaffold drag handle above the arrivals content. The handle is ~48dp,
 * but the reported header dimens already budgeted ~20dp for the old in-panel handle the
 * BottomSheetScaffold handle now replaces, so only the net difference is added.
 */
private val DRAG_HANDLE_ALLOWANCE = 28.dp
