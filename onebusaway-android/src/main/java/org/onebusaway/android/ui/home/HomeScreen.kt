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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.io.request.ObaArrivalInfoResponse
import org.onebusaway.android.map.RouteHeader
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.survey.SurveyViewModel

/**
 * The home screen's tap/UI callbacks, bundled into one holder (mirrors [SurveyCallbacks]) so
 * [HomeScreen]'s signature stays a handful of parameters — state + the map/survey plumbing + this —
 * instead of ~30 individual lambdas. Each is dispatched up to HomeActivity or a view model.
 */
class HomeCallbacks(
    val onNavItemSelected: (HomeNavItem) -> Unit,
    val onSearch: (String) -> Unit,
    val onRecentStopsRoutes: () -> Unit,
    val onListSort: () -> Unit,
    val onListClear: () -> Unit,
    // The bikeshare-layer toggle (in MapFeature) re-snapshots the host environment for the chrome tint.
    val onBikeshareToggled: () -> Unit,
    val onHelpAction: (HelpAction) -> Unit,
    val onWhatsNewDismissed: () -> Unit,
    val onRegionChosen: (ObaRegion) -> Unit,
    val onSheetSettled: (ArrivalsSheetState, Int) -> Unit,
    val onClearFocus: () -> Unit,
    val onArrivalsLoaded: (ObaArrivalInfoResponse) -> Unit,
    val onShowRouteOnMap: (String) -> Unit,
    val onToggleSheet: () -> Unit,
    val onPreferredHeight: (previewCount: Int, filtering: Boolean) -> Unit,
    val onCancelRouteMode: () -> Unit,
    val onRouteHeaderHeight: (Int) -> Unit,
)

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
 * the map ([MapFeature]), the route-mode header ([RouteHeaderOverlay]), and the survey ([SurveyOverlay])
 * are all composables now — no map-related `AndroidView` / View seam remains.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    events: SharedFlow<HomeEvent>,
    // The map is a self-wiring [MapFeature] (gated by [mapComposed] so SDK init stays lazy until the
    // first NEARBY selection); the route-mode header and survey are Compose overlays over it.
    homeViewModel: HomeViewModel,
    mapViewModel: MapViewModel,
    mapSeedLat: Double,
    mapSeedLon: Double,
    mapSeedZoom: Float,
    mapSavedInstanceState: Bundle?,
    routeHeader: RouteHeader?,
    surveyViewModel: SurveyViewModel,
    donationViewModel: DonationViewModel,
    weatherViewModel: WeatherViewModel,
    helpViewModel: HelpViewModel,
    listVms: HomeListViewModels,
    // All the screen's tap/UI lambdas, bundled (see [HomeCallbacks]); brought into scope below via
    // `with` so the body references them unqualified.
    callbacks: HomeCallbacks,
) {
    with(callbacks) {
    ObaTheme {
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val context = LocalContext.current
        val snackbarHostState = remember { SnackbarHostState() }
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
                    else -> {} // RegionResolved is handled by the activity
                }
            }
        }

        // The "Found X region" snackbar (replaces the legacy toast), shown once per auto-select resolve
        // then cleared in the VM. showSnackbar suspends until dismissed; Long ~ the old Toast.LENGTH_LONG.
        LaunchedEffect(state.regionFoundName) {
            state.regionFoundName?.let { name ->
                snackbarHostState.showSnackbar(
                    context.getString(R.string.region_region_found, name),
                    duration = SnackbarDuration.Long,
                )
                homeViewModel.onRegionFoundShown()
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
                    snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        // The self-wiring map feature module: renders the map (gated so the SDK only
                        // initializes once NEARBY is first shown, then stays composed) + its chrome FABs,
                        // and owns its callbacks / state collectors / effects-as-dialogs / permission /
                        // lifecycle. Only the sheet-derived FAB inset + the env-refresh ping come in.
                        MapFeature(
                            mapViewModel = mapViewModel,
                            homeViewModel = homeViewModel,
                            weatherViewModel = weatherViewModel,
                            mapComposed = state.mapComposed,
                            mapSeedLat = mapSeedLat,
                            mapSeedLon = mapSeedLon,
                            mapSeedZoom = mapSeedZoom,
                            mapSavedInstanceState = mapSavedInstanceState,
                            fabBottomInset = fabInsetTarget,
                            onBikeshareToggled = onBikeshareToggled,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // The weather chip feature module: self-wiring from its ViewModel, NEARBY-gated.
                        WeatherFeature(
                            viewModel = weatherViewModel,
                            onNearby = state.selectedItem == HomeNavItem.NEARBY,
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                        )
                        // The donation feature module: the card (NEARBY + DonationsManager-gated) plus
                        // its dismiss dialog. Self-wiring from its ViewModel; NEARBY-gated like the
                        // other chrome.
                        DonationFeature(
                            viewModel = donationViewModel,
                            onNearby = state.selectedItem == HomeNavItem.NEARBY,
                            cardModifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 62.dp)
                        )
                        // The route-mode header (Compose), top-aligned over the map — drawn above the
                        // weather/donation cards so its opaque bar + cancel button own the top in route
                        // mode. Reports its height for the map's top padding; clears it when dismissed.
                        if (routeHeader != null) {
                            RouteHeaderOverlay(
                                header = routeHeader,
                                onCancel = onCancelRouteMode,
                                onHeight = onRouteHeaderHeight,
                                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                            )
                        } else {
                            LaunchedEffect(Unit) { onRouteHeaderHeight(0) }
                        }
                        // The map survey (Compose): hero card over the map + remaining-questions sheet.
                        // Self-wiring from its ViewModel; self-triggers its request when NEARBY is shown
                        // and a region has resolved.
                        SurveyFeature(
                            viewModel = surveyViewModel,
                            onNearby = state.selectedItem == HomeNavItem.NEARBY,
                            regionReady = state.regionReady,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
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

        HomeDialogs(dialog = state.dialog, onRegionChosen = onRegionChosen)

        // The region-wide GTFS alert dialog, driven declaratively by state (replaces the activity's
        // one-shot event -> GtfsAlertsHelper.showWideAlertDialog path).
        state.wideAlert?.let { WideAlertDialog(it) { homeViewModel.dismissWideAlert() } }

        // The help / what's-new / legend dialogs feature module (self-rendering from its ViewModel;
        // self-shows what's-new once a region resolves; the genuinely-Activity actions + the what's-new
        // opt-out are forwarded to the host).
        HelpFeature(
            viewModel = helpViewModel,
            regionReady = state.regionReady,
            onHelpAction = onHelpAction,
            onWhatsNewDismissed = onWhatsNewDismissed,
        )
    }
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
