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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.ui.compose.findActivity
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.home.arrivals.ArrivalsSheetHost
import org.onebusaway.android.ui.home.chrome.HomeNavDrawerSheet
import org.onebusaway.android.ui.home.chrome.HomeTopBar
import org.onebusaway.android.ui.home.chrome.titleRes
import org.onebusaway.android.ui.home.donation.DonationFeature
import org.onebusaway.android.ui.home.donation.DonationViewModel
import org.onebusaway.android.ui.home.help.HelpAction
import org.onebusaway.android.ui.home.help.HelpFeature
import org.onebusaway.android.ui.home.help.HelpViewModel
import org.onebusaway.android.ui.home.map.MapChrome
import org.onebusaway.android.ui.home.map.MapFeature
import org.onebusaway.android.ui.home.map.RouteHeaderOverlay
import org.onebusaway.android.ui.survey.SurveyFeature
import org.onebusaway.android.ui.home.weather.WeatherFeature
import org.onebusaway.android.ui.home.weather.WeatherViewModel
import org.onebusaway.android.ui.mylists.MyListViewModel
import org.onebusaway.android.ui.mylists.ReminderItem
import org.onebusaway.android.ui.mylists.ReminderListDestination
import org.onebusaway.android.ui.mylists.RouteListDestination
import org.onebusaway.android.ui.mylists.RouteListItem
import org.onebusaway.android.ui.mylists.StopListDestination
import org.onebusaway.android.ui.mylists.StopListItem
import org.onebusaway.android.ui.mylists.editReminder
import org.onebusaway.android.ui.mylists.openRoute
import org.onebusaway.android.ui.mylists.reminderActions
import org.onebusaway.android.ui.mylists.routeActions
import org.onebusaway.android.ui.mylists.stopActions
import org.onebusaway.android.ui.survey.SurveyViewModel

/**
 * The home screen's tap/UI callbacks, bundled into one holder (mirrors [org.onebusaway.android.ui.survey.SurveyCallbacks]) so
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
    val onShowWelcomeTutorial: () -> Unit,
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
 * The home list destinations' backing [MyListViewModel]s, owned by [org.onebusaway.android.ui.HomeActivity]
 * (so its options menu can sort/clear them) and handed to [HomeScreen]. They stay cheap until a
 * destination subscribes.
 */
class HomeListViewModels(
    val starredStops: MyListViewModel<StopListItem>,
    val starredRoutes: MyListViewModel<RouteListItem>,
    val reminders: MyListViewModel<ReminderItem>,
)

/**
 * The declarative home screen: a Compose `ModalNavigationDrawer` + [HomeTopBar] + Material3
 * `BottomSheetScaffold`, rendered from [HomeUiState] (state down) with taps dispatched through plain
 * lambda callbacks + [HomeViewModel] events (up). Replaces the imperative `HomeShellHost` bridge.
 *
 * The arrivals sheet inverts to declarative: **visibility is business state** — the sheet peeks iff
 * a stop is focused on NEARBY — driven by a [LaunchedEffect] keyed on that derived flag, so it never
 * fights a user drag. **Expansion (peek<->full)** is the live `SheetState`, nudged by one-shot
 * [SheetCommand.ToggleSheet]/[SheetCommand.CollapseSheet] commands (the screen alone knows the live state),
 * plus [BackHandler]. The arrivals panel is hosted directly per focused stop (see [ArrivalsSheetHost]);
 * the map ([MapFeature]), the route-mode header ([RouteHeaderOverlay]), and the survey ([org.onebusaway.android.ui.survey.SurveyOverlay])
 * are all composables now — no map-related `AndroidView` / View seam remains.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    sheetCommands: SharedFlow<SheetCommand>,
    // The map is a self-wiring [MapFeature] (gated by [mapComposed] so SDK init stays lazy until the
    // first NEARBY selection); the route-mode header and survey are Compose overlays over it.
    homeViewModel: HomeViewModel,
    mapViewModel: MapViewModel,
    mapSeedLat: Double,
    mapSeedLon: Double,
    mapSeedZoom: Float,
    routeHeader: RouteHeader?,
    surveyViewModel: SurveyViewModel,
    donationViewModel: DonationViewModel,
    weatherViewModel: WeatherViewModel,
    helpViewModel: HelpViewModel,
    listVms: HomeListViewModels,
    // Builds the per-focused-stop ArrivalsViewModel for the bottom-sheet host (assisted-injected;
    // the sheet's stop id is runtime-dynamic, so it can't be a plain hiltViewModel). Injected into
    // HomeActivity and threaded down.
    arrivalsViewModelFactory: ArrivalsViewModel.Factory,
    // All the screen's tap/UI lambdas, bundled (see [HomeCallbacks]); brought into scope below via
    // `with` so the body references them unqualified.
    callbacks: HomeCallbacks,
    // In-NavHost navigation out of a home list overlay (Campaign C); built with the navController
    // (which lives in the NavHost, not in HomeCallbacks). `onShowRouteInfo` → the RouteInfo
    // destination (reminders "show route"); `onShowArrivals` → the Arrivals destination (a starred-
    // stop tap and a reminder's "show stop").
    onShowRouteInfo: (routeId: String) -> Unit,
    onShowArrivals: (stopId: String, stopName: String?) -> Unit,
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

        // One-shot sheet commands from the ViewModel (the screen holds the live SheetState).
        LaunchedEffect(Unit) {
            sheetCommands.collect { command ->
                when (command) {
                    SheetCommand.ToggleSheet -> runCatching {
                        when (toggleSheetTarget(sheetState.currentValue.toArrivalsSheetState())) {
                            ArrivalsSheetState.Expanded -> sheetState.expand()
                            else -> sheetState.partialExpand()
                        }
                    }
                    SheetCommand.CollapseSheet -> runCatching { sheetState.partialExpand() }
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

        HomeDrawer(
            drawerState = drawerState,
            navItems = state.navItems,
            selectedItem = state.selectedItem,
            onNavItemSelected = onNavItemSelected,
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
                            arrivalsViewModelFactory = arrivalsViewModelFactory,
                            onArrivalsLoaded = onArrivalsLoaded,
                            onShowRouteOnMap = onShowRouteOnMap,
                            onToggleSheet = onToggleSheet,
                            onPreferredHeight = onPreferredHeight,
                            showUndoSnackbar = { messageRes, actionRes, onAction ->
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = context.getString(messageRes),
                                        actionLabel = actionRes?.let { context.getString(it) },
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
                                }
                            },
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
                            mapComposed = state.mapComposed,
                            mapSeedLat = mapSeedLat,
                            mapSeedLon = mapSeedLon,
                            mapSeedZoom = mapSeedZoom,
                            fabBottomInset = fabInsetTarget,
                            onBikeshareToggled = onBikeshareToggled,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // The map chrome stack drawn over the map (weather/donation/route-header/survey)
                        // plus the selected list tab's full-size destination. Extracted to keep this
                        // scaffold body shallow; see [HomeMapOverlays].
                        HomeMapOverlays(
                            state = state,
                            weatherViewModel = weatherViewModel,
                            donationViewModel = donationViewModel,
                            surveyViewModel = surveyViewModel,
                            routeHeader = routeHeader,
                            listVms = listVms,
                            onCancelRouteMode = onCancelRouteMode,
                            onRouteHeaderHeight = onRouteHeaderHeight,
                            onShowRouteInfo = onShowRouteInfo,
                            onShowArrivals = onShowArrivals,
                        )
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
            onShowWelcomeTutorial = onShowWelcomeTutorial,
        )
    }
    }
}

/**
 * The home screen's `ModalNavigationDrawer`: the nav-drawer sheet ([HomeNavDrawerSheet]) wrapping the
 * screen [content]. A tap closes the drawer and dispatches the selection up. The drawer is opened from
 * the toolbar hamburger (via the host-owned [drawerState]), so gestures are enabled only while it's
 * already open — a left-edge drag on the map must pan the map, not peel the drawer open.
 */
@Composable
private fun HomeDrawer(
    drawerState: DrawerState,
    navItems: List<HomeNavItem>,
    selectedItem: HomeNavItem,
    onNavItemSelected: (HomeNavItem) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        // Material3 gates both the open-swipe and the scrim tap-to-close on this one flag, so tie it
        // to the open state (see the KDoc above).
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            HomeNavDrawerSheet(items = navItems, selected = selectedItem) { item ->
                scope.launch { drawerState.close() }
                onNavItemSelected(item)
            }
        },
        content = content,
    )
}

/**
 * The chrome drawn over the map inside the home scaffold's content [Box]: the weather chip, donation
 * card, route-mode header, and survey hero — then, on a selected list tab, that tab's opaque full-size
 * destination ([HomeListOverlay]). A [BoxScope] extension so the overlays keep their `align`/fill
 * modifiers; NEARBY shows the map through.
 */
@Composable
private fun BoxScope.HomeMapOverlays(
    state: HomeUiState,
    weatherViewModel: WeatherViewModel,
    donationViewModel: DonationViewModel,
    surveyViewModel: SurveyViewModel,
    routeHeader: RouteHeader?,
    listVms: HomeListViewModels,
    onCancelRouteMode: () -> Unit,
    onRouteHeaderHeight: (Int) -> Unit,
    onShowRouteInfo: (routeId: String) -> Unit,
    onShowArrivals: (stopId: String, stopName: String?) -> Unit,
) {
    // The weather chip feature module: self-wiring from its ViewModel, NEARBY-gated.
    WeatherFeature(
        viewModel = weatherViewModel,
        onNearby = state.selectedItem == HomeNavItem.NEARBY,
        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
    )
    // The donation feature module: the card (NEARBY + DonationsManager-gated) plus its dismiss dialog.
    // Self-wiring from its ViewModel; NEARBY-gated like the other chrome.
    DonationFeature(
        viewModel = donationViewModel,
        onNearby = state.selectedItem == HomeNavItem.NEARBY,
        cardModifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 62.dp)
    )
    // The route-mode header (Compose), top-aligned over the map — drawn above the weather/donation
    // cards so its opaque bar + cancel button own the top in route mode. Reports its height for the
    // map's top padding; clears it when dismissed.
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
    // The map survey (Compose): hero card over the map + remaining-questions sheet. Self-wiring from
    // its ViewModel; self-triggers its request when NEARBY is shown and a region has resolved.
    SurveyFeature(
        viewModel = surveyViewModel,
        onNearby = state.selectedItem == HomeNavItem.NEARBY,
        regionReady = state.regionReady,
        modifier = Modifier.align(Alignment.TopCenter),
    )
    HomeListOverlay(
        selectedItem = state.selectedItem,
        listVms = listVms,
        onShowRouteInfo = onShowRouteInfo,
        onShowArrivals = onShowArrivals,
    )
}

/**
 * A selected list tab draws its destination over the map (an opaque, full-size Surface), covering the
 * map chrome; NEARBY shows the map through. These are thin home-specific bindings to the shared My*
 * list destinations (strings + actions, shortcutMode = false — the home screen is never a
 * launcher-shortcut picker).
 */
@Composable
private fun HomeListOverlay(
    selectedItem: HomeNavItem,
    listVms: HomeListViewModels,
    onShowRouteInfo: (routeId: String) -> Unit,
    onShowArrivals: (stopId: String, stopName: String?) -> Unit,
) {
    when (selectedItem) {
        HomeNavItem.STARRED_STOPS -> {
            val host = LocalContext.current.findActivity()
            StopListDestination(
                listVms.starredStops,
                emptyText = R.string.my_no_starred_stops,
                onClick = { onShowArrivals(it.id, it.name) },
                actions = {
                    host.stopActions(it, R.string.my_context_remove_star, shortcutMode = false) {
                        listVms.starredStops.remove(it.id)
                    }
                },
            )
        }
        HomeNavItem.STARRED_ROUTES -> {
            val host = LocalContext.current.findActivity()
            RouteListDestination(
                listVms.starredRoutes,
                emptyText = R.string.my_no_starred_routes,
                onClick = { host.openRoute(it, shortcutMode = false) },
                actions = {
                    host.routeActions(it, R.string.my_context_remove_star, shortcutMode = false) {
                        listVms.starredRoutes.remove(it.id)
                    }
                },
            )
        }
        HomeNavItem.MY_REMINDERS -> {
            val host = LocalContext.current.findActivity()
            ReminderListDestination(
                listVms.reminders,
                emptyText = R.string.trip_list_notrips,
                onClick = { host.editReminder(it) },
                // A reminder carries only a stop id (no cached name).
                actions = {
                    host.reminderActions(
                        it,
                        onShowRoute = onShowRouteInfo,
                        onShowStop = { stopId -> onShowArrivals(stopId, null) },
                    )
                },
            )
        }
        else -> {}
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
