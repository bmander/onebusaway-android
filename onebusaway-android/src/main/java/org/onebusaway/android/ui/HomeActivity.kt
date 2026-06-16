/*
 * Copyright (C) 2012-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com),
 * Microsoft Corporation
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
package org.onebusaway.android.ui

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.fragment.app.Fragment
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.io.request.ObaArrivalInfoResponse
import org.onebusaway.android.location.LocationRepository
import org.onebusaway.android.map.MapCameraSeed
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.map.mapModeToParams
import org.onebusaway.android.map.resolveMapMode
import org.onebusaway.android.map.resolveMapSeed
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.region.RegionRefresher
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.report.ui.InfrastructureIssueDestination
import org.onebusaway.android.report.ui.ReportActivity
import org.onebusaway.android.report.ui.ReportDestination
import org.onebusaway.android.report.ui.CustomerServiceDestination
import org.onebusaway.android.travelbehavior.TravelBehaviorManager
import org.onebusaway.android.ui.agencies.AgenciesRoute
import org.onebusaway.android.ui.arrivals.ArrivalsIntents
import org.onebusaway.android.ui.arrivals.ArrivalsRoute
import org.onebusaway.android.ui.arrivals.ArrivalsUiState
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.home.ArrivalsSheetState
import org.onebusaway.android.ui.home.DonationViewModel
import org.onebusaway.android.ui.home.WeatherViewModel
import org.onebusaway.android.ui.home.focusedStopFromExtras
import org.onebusaway.android.ui.home.HelpAction
import org.onebusaway.android.ui.home.HelpViewModel
import org.onebusaway.android.ui.home.HomeEnvironment
import org.onebusaway.android.ui.home.HomeCallbacks
import org.onebusaway.android.ui.home.HomeEvent
import org.onebusaway.android.ui.home.HomeListViewModels
import org.onebusaway.android.ui.home.HomeNavItem
import org.onebusaway.android.ui.home.initialNavItem
import org.onebusaway.android.ui.home.HomeScreen
import org.onebusaway.android.ui.home.HomeViewModel
import org.onebusaway.android.ui.home.analyticsLabelRes
import org.onebusaway.android.ui.compose.components.ObaTopAppBar
import org.onebusaway.android.ui.mylists.MyRecentDestination
import org.onebusaway.android.ui.mylists.MyRoutesDestination
import org.onebusaway.android.ui.mylists.MyStopsDestination
import org.onebusaway.android.ui.mylists.MyTabs
import org.onebusaway.android.ui.mylists.ReminderListDestination
import org.onebusaway.android.ui.mylists.RemindersRepository
import org.onebusaway.android.ui.mylists.StarredRoutesRepository
import org.onebusaway.android.ui.mylists.StarredStopsRepository
import org.onebusaway.android.ui.mylists.rememberListVm
import org.onebusaway.android.ui.nav.NavRoutes
import org.onebusaway.android.ui.settings.AdvancedSettingsRoute
import org.onebusaway.android.ui.regions.RegionsRoute
import org.onebusaway.android.ui.routeinfo.RouteInfoRoute
import org.onebusaway.android.ui.searchresults.SearchResultsRoute
import org.onebusaway.android.ui.searchresults.SearchResultsViewModel
import org.onebusaway.android.ui.tripdetails.TripDetailsRoute
import org.onebusaway.android.ui.tripdetails.TripDetailsViewModel
import org.onebusaway.android.ui.tripdetails.rememberDestinationReminderAction
import org.onebusaway.android.ui.tripinfo.TripInfoEvent
import org.onebusaway.android.ui.tripinfo.TripInfoRoute
import org.onebusaway.android.ui.tripinfo.TripInfoViewModel
import org.onebusaway.android.ui.tripplan.TripPlanDestination
import org.onebusaway.android.ui.tripplan.TripPlanLocationPickerDestination
import org.onebusaway.android.ui.mylists.chooseSortOrder
import org.onebusaway.android.ui.mylists.confirmClear
import org.onebusaway.android.ui.mylists.hostListVm
import org.onebusaway.android.ui.survey.SurveyViewModel
import org.onebusaway.android.ui.survey.activities.SurveyWebViewScreen
import org.onebusaway.android.util.DBUtil
import org.onebusaway.android.util.LayerUtils
import org.onebusaway.android.util.PermissionUtils
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.ReminderUtils
import org.onebusaway.android.util.ShowcaseViewUtils
import org.onebusaway.android.util.UIUtils

@AndroidEntryPoint
class HomeActivity : AppCompatActivity() {

    // HomeActivity's own preference reads (what's-new opt-out, zoom/left-hand chrome flags, the
    // remembered nav item). HomeViewModel is now a plain @HiltViewModel — no hand-built factory.
    @Inject
    lateinit var prefsRepository: PreferencesRepository

    // Builds the per-stop ArrivalsViewModel for the home bottom-sheet host (and, in C-b.3, the
    // arrivals NavHost destination). Assisted because the sheet's stop id is runtime-dynamic.
    @Inject
    lateinit var arrivalsViewModelFactory: ArrivalsViewModel.Factory

    // The observable current region (Campaign A): the activity's region-dependent reads (region-found
    // toast, the region's Twitter URL) collect this instead of Application.get().currentRegion.
    @Inject
    lateinit var regionRepository: RegionRepository

    // The last-known location (Campaign A, B1): the send-feedback fallback reads it from here instead
    // of Application.getLastKnownLocation.
    @Inject
    lateinit var locationRepository: LocationRepository

    private val viewModel: HomeViewModel by viewModels()

    // The map view model — the single source of truth for the map. MapFeature (in HomeScreen) renders it
    // and self-wires the callbacks/collectors/effects/lifecycle; the activity only obtains it here (Hilt-
    // injected, shared with HomeViewModel) and reads its mode/camera in onSaveInstanceState + sets the mode/seed.
    private val mapViewModel: MapViewModel by viewModels()

    // The map survey (Compose), shown over the map on NEARBY. Activity-scoped.
    private val surveyViewModel: SurveyViewModel by viewModels()

    // The donation card feature module (Compose), shown over the map on NEARBY. Activity-scoped.
    private val donationViewModel: DonationViewModel by viewModels()

    // The weather chip feature module (Compose), shown over the map on NEARBY. Activity-scoped;
    // Hilt injects its weather + region dependencies.
    private val weatherViewModel: WeatherViewModel by viewModels()

    // The help / what's-new / legend dialogs feature module. Activity-scoped.
    private val helpViewModel: HelpViewModel by viewModels()

    // The three home list destinations (starred stops/routes, reminders) render as Compose overlays
    // over the map (HomeListDestinations) rather than swapped-in fragments. Their MyListViewModels are
    // owned here (keyed in the activity's ViewModelStore) so the toolbar sort/clear menu can reach
    // them; they stay cheap until a destination subscribes (WhileSubscribed). See HomeListViewModels.
    private val listVms: HomeListViewModels by lazy {
        HomeListViewModels(
            hostListVm("home.starredStops") { StarredStopsRepository(applicationContext) },
            hostListVm("home.starredRoutes") { StarredRoutesRepository(applicationContext) },
            hostListVm("home.reminders") { RemindersRepository(applicationContext) },
        )
    }

    private lateinit var firebaseAnalytics: FirebaseAnalytics

    // A NavHost route to navigate to once the NavHost has composed, set from an incoming external
    // intent (FCM, a pinned shortcut / legacy class name resolved via an activity-alias, an in-app
    // launch from non-NavHost code). The navController is created inside setContent, so onCreate /
    // onNewIntent can't navigate directly — they stage the route here and a LaunchedEffect consumes it.
    private val pendingDeepLinkRoute = MutableStateFlow<String?>(null)

    // Re-homed from the former SettingsActivity: the AdvancedSettingsFragment sets this (via
    // setOtpCustomAPIUrlChanged) when the user edits the custom OTP API URL; the settings destination's
    // onDispose reads it to decide whether to re-home (NavHelp.goHome). Kept on the host activity so the
    // fragment can reach it through its host (now HomeActivity instead of SettingsActivity).
    var otpCustomAPIUrlChanged: Boolean = false
        private set

    /**
     * Set by the advanced settings screen ([org.onebusaway.android.ui.settings.AdvancedSettingsRoute])
     * when the user changes the custom OTP API URL; read on leaving the settings subtree to decide
     * whether to re-home (so the change takes effect).
     */
    fun setOtpCustomAPIUrlChanged(changed: Boolean) {
        otpCustomAPIUrlChanged = changed
    }

    // Set true by the report chooser's region-validate dialog (ReportDestination) when the user
    // confirms their region; the chooser observes it to swap the validate dialog for the type list.
    val reportRegionValidated = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The map's center/zoom/mode are restored from the saved bundle via initMapMode()/resolveMapSeed()
        // below (the focus is restored by HomeViewModel's SavedStateHandle); the seed avoids a map flash.
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

        // Host the map content inside a Compose ModalNavigationDrawer + HomeTopBar + BottomSheetScaffold,
        // replacing the XML DrawerLayout + NavigationDrawerFragment + main.xml chrome, the hosted
        // MaterialToolbar + options menu, and the third-party SlidingUpPanelLayout. The arrivals panel
        // is the scaffold's bottom sheet, rendered per focused stop by ArrivalsSheetHost. The map, the
        // route-mode header, and the survey are all Compose now — no map-related View seam remains.

        // The map is driven by mapViewModel; the seed (saved state → intent → last-saved view) just
        // avoids an initial flash before the loaders/region center it.
        val mapSeed = readMapSeed(savedInstanceState)

        val homeCallbacks = HomeCallbacks(
            onNavItemSelected = ::onHomeNavItemSelected,
            onSearch = ::onSearch,
            onRecentStopsRoutes = ::onRecentStopsRoutes,
            onListSort = ::onListSortSelected,
            onListClear = ::onListClearSelected,
            onBikeshareToggled = ::pushEnvironment,
            onHelpAction = ::onHelpAction,
            onWhatsNewDismissed = ::onWhatsNewDismissed,
            onRegionChosen = viewModel::onRegionChosen,
            onSheetSettled = viewModel::onSheetSettled,
            onClearFocus = viewModel::requestClearMapFocus,
            onArrivalsLoaded = ::onArrivalsLoaded,
            onShowRouteOnMap = viewModel::requestShowRouteOnMap,
            onToggleSheet = viewModel::requestToggleSheet,
            onPreferredHeight = viewModel::onPreferredHeight,
            onCancelRouteMode = ::onCancelRouteMode,
            onRouteHeaderHeight = ::onRouteHeaderHeight,
        )

        // Stage any external "open this screen" intent (set before setContent so the NavHost's
        // LaunchedEffect observes it once composed). MapParams.* focus / route-mode launches return
        // null here and stay on the map path below. Only on a fresh launch (not a config change).
        if (savedInstanceState == null) {
            pendingDeepLinkRoute.value = routeForIntent(intent)
        }

        setContent {
            // Campaign C0: the single-Activity Navigation-Compose backbone. HomeActivity hosts every
            // screen as a NavHost destination; external intents (FCM, pinned shortcuts, legacy class
            // names via activity-aliases) land here and are routed by [routeForIntent].
            val navController = rememberNavController()
            // Consume a staged deep-link route once the NavHost is ready (and on each onNewIntent).
            val pending by pendingDeepLinkRoute.collectAsStateWithLifecycle()
            LaunchedEffect(pending) {
                pending?.let { route ->
                    navController.navigate(route) {
                        popUpTo(NavRoutes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                    pendingDeepLinkRoute.value = null
                }
            }
            // Re-home when leaving the settings subtree if the user re-enabled auto-select-region or
            // changed the custom OTP URL (ported from the former SettingsActivity.onDestroy). The
            // auto-select baseline is captured on entry and compared on exit, so a re-home fires only
            // when it was turned back on during this settings visit.
            DisposableEffect(navController) {
                val settingsRoutes = setOf(NavRoutes.SETTINGS, NavRoutes.SETTINGS_ADVANCED)
                val autoSelectKey = getString(R.string.preference_key_auto_select_region)
                var autoSelectInitial: Boolean? = null
                val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                    if (destination.route in settingsRoutes) {
                        if (autoSelectInitial == null) {
                            autoSelectInitial = PreferenceUtils.getBoolean(autoSelectKey, true)
                        }
                    } else if (autoSelectInitial != null) {
                        val reEnabledAutoSelect =
                            PreferenceUtils.getBoolean(autoSelectKey, true) && autoSelectInitial == false
                        autoSelectInitial = null
                        if (reEnabledAutoSelect) {
                            NavHelp.goHome(this@HomeActivity, false)
                        } else if (otpCustomAPIUrlChanged) {
                            setOtpCustomAPIUrlChanged(false)
                            NavHelp.goHome(this@HomeActivity, false)
                        }
                    }
                }
                navController.addOnDestinationChangedListener(listener)
                onDispose { navController.removeOnDestinationChangedListener(listener) }
            }
            NavHost(navController = navController, startDestination = NavRoutes.HOME) {
                composable(NavRoutes.HOME) {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val routeHeader by mapViewModel.routeHeader.collectAsStateWithLifecycle()
                    HomeScreen(
                        state = state,
                        events = viewModel.events,
                        homeViewModel = viewModel,
                        mapViewModel = mapViewModel,
                        mapSeedLat = mapSeed.lat,
                        mapSeedLon = mapSeed.lon,
                        mapSeedZoom = mapSeed.zoom,
                        mapSavedInstanceState = savedInstanceState,
                        routeHeader = routeHeader,
                        surveyViewModel = surveyViewModel,
                        donationViewModel = donationViewModel,
                        weatherViewModel = weatherViewModel,
                        helpViewModel = helpViewModel,
                        listVms = listVms,
                        arrivalsViewModelFactory = arrivalsViewModelFactory,
                        callbacks = homeCallbacks,
                        onShowRouteInfo = { routeId ->
                            navController.navigate(NavRoutes.routeInfo(routeId))
                        },
                        onShowArrivals = { stopId, stopName ->
                            navController.navigate(NavRoutes.arrivals(stopId, stopName))
                        },
                    )
                }
                // RouteInfo destination (Campaign C-a): a route's stops grouped by direction. Reached
                // in-app from the home reminders overlay's "show route"; RouteInfoActivity still hosts
                // the same RouteInfoRoute for the standalone/external launch paths (collapsed to an
                // activity-alias in C-c). The VM reads routeId from SavedStateHandle (the nav-arg).
                composable(
                    NavRoutes.ROUTE_INFO,
                    arguments = listOf(
                        navArgument(NavRoutes.ARG_ROUTE_ID) { type = NavType.StringType }
                    ),
                ) { backStackEntry ->
                    val routeId =
                        backStackEntry.arguments?.getString(NavRoutes.ARG_ROUTE_ID).orEmpty()
                    ObaTheme {
                        RouteInfoRoute(
                            viewModel = hiltViewModel(),
                            onBack = { navController.popBackStack() },
                            onShowRouteOnMap = { HomeActivity.start(this@HomeActivity, routeId) },
                            onStopClick = { stop ->
                                navController.navigate(NavRoutes.arrivals(stop.id, stop.name))
                            },
                            onStopShowOnMap = { stop ->
                                HomeActivity.start(
                                    this@HomeActivity, stop.id, stop.latitude, stop.longitude
                                )
                            },
                        )
                    }
                }
                // Arrivals destination (Campaign C-b): real-time arrivals for a stop. Reached in-app
                // from RouteInfo's stop tap and the home overlays' stop taps; ArrivalsListActivity
                // still hosts the same ArrivalsRoute for the standalone/FCM/external paths (collapsed
                // to an activity-alias in C-c). The VM is built from the assisted factory with the
                // nav-arg stop id (process-death safe — it's re-read from the back-stack arg).
                composable(
                    NavRoutes.ARRIVALS,
                    arguments = listOf(
                        navArgument(NavRoutes.ARG_STOP_ID) { type = NavType.StringType },
                        navArgument(NavRoutes.ARG_STOP_NAME) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { backStackEntry ->
                    val stopId =
                        backStackEntry.arguments?.getString(NavRoutes.ARG_STOP_ID).orEmpty()
                    val stopName =
                        backStackEntry.arguments?.getString(NavRoutes.ARG_STOP_NAME).orEmpty()
                    val arrivalsVm: ArrivalsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                arrivalsViewModelFactory.create(stopId, ignorePersistedFilter = false)
                            }
                        }
                    )
                    val snackbarHostState = remember { SnackbarHostState() }
                    val scope = rememberCoroutineScope()
                    val context = LocalContext.current
                    val handler = remember(arrivalsVm) {
                        createArrivalActionHandler(
                            activity = this@HomeActivity,
                            viewModel = arrivalsVm,
                            currentContent = { arrivalsVm.state.value as? ArrivalsUiState.Content },
                            onShowRouteOnMap = { routeId ->
                                HomeActivity.start(this@HomeActivity, routeId)
                            },
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
                            onShowTrip = { tripId, sid ->
                                navController.navigate(
                                    NavRoutes.tripDetails(tripId, sid, TripDetailsActivity.SCROLL_MODE_STOP)
                                )
                            },
                        )
                    }
                    ObaTheme {
                        ArrivalsRoute(
                            viewModel = arrivalsVm,
                            initialTitle = stopName,
                            handler = handler,
                            onBack = { navController.popBackStack() },
                            snackbarHostState = snackbarHostState,
                        )
                    }
                }
                // TripDetails destination (Campaign C-d): a trip's stops + live vehicle position.
                // Reached in-app from the arrivals destination's "show trip"; TripDetailsActivity still
                // hosts the same TripDetailsRoute for standalone/map/NavigationService launches
                // (collapsed to an activity-alias in C-c). The destination-reminder flow is the shared
                // rememberDestinationReminderAction controller; the VM reads its args from the nav-args.
                composable(
                    NavRoutes.TRIP_DETAILS,
                    arguments = listOf(
                        navArgument(NavRoutes.ARG_TRIP_ID) { type = NavType.StringType },
                        navArgument(NavRoutes.ARG_STOP_ID) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(NavRoutes.ARG_SCROLL_MODE) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { backStackEntry ->
                    val tripId =
                        backStackEntry.arguments?.getString(NavRoutes.ARG_TRIP_ID).orEmpty()
                    val tripStopId = backStackEntry.arguments?.getString(NavRoutes.ARG_STOP_ID)
                    val tripVm: TripDetailsViewModel = hiltViewModel()
                    ObaTheme {
                        TripDetailsRoute(
                            viewModel = tripVm,
                            onBack = { navController.popBackStack() },
                            onShowOnMap = { routeId -> HomeActivity.start(this@HomeActivity, routeId) },
                            onStopClick = { sid, name, _ ->
                                navController.navigate(NavRoutes.arrivals(sid, name))
                            },
                            onSetDestinationReminder = rememberDestinationReminderAction(
                                viewModel = tripVm,
                                prefsRepository = prefsRepository,
                                tripId = tripId,
                                stopId = tripStopId,
                            ),
                        )
                    }
                }
                // TripInfo destination (reminder editor). Reached in-app from the home reminders
                // overlay's edit-tap and the arrivals "set reminder" action (both via the
                // TripInfoActivity facade → HomeActivity → translator). Non-exported; no alias.
                composable(
                    NavRoutes.TRIP_INFO,
                    arguments = listOf(
                        navArgument(NavRoutes.ARG_TRIP_ID) { type = NavType.StringType },
                        navArgument(NavRoutes.ARG_STOP_ID) { type = NavType.StringType },
                        navArgument(NavRoutes.ARG_ROUTE_ID) {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                        navArgument(NavRoutes.ARG_ROUTE_NAME) {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                        navArgument(NavRoutes.ARG_STOP_NAME) {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                        navArgument(NavRoutes.ARG_HEADSIGN) {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                        navArgument(NavRoutes.ARG_DEPART_TIME) {
                            type = NavType.LongType; defaultValue = 0L
                        },
                        navArgument(NavRoutes.ARG_STOP_SEQUENCE) {
                            type = NavType.IntType; defaultValue = 0
                        },
                        navArgument(NavRoutes.ARG_SERVICE_DATE) {
                            type = NavType.LongType; defaultValue = 0L
                        },
                        navArgument(NavRoutes.ARG_VEHICLE_ID) {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                    ),
                ) { backStackEntry ->
                    val infoTripId =
                        backStackEntry.arguments?.getString(NavRoutes.ARG_TRIP_ID).orEmpty()
                    val infoStopId =
                        backStackEntry.arguments?.getString(NavRoutes.ARG_STOP_ID).orEmpty()
                    val infoVm: TripInfoViewModel = hiltViewModel()
                    LaunchedEffect(infoVm) {
                        infoVm.events.collect { event ->
                            when (event) {
                                TripInfoEvent.Saved -> {
                                    Toast.makeText(
                                        this@HomeActivity, R.string.trip_info_saved, Toast.LENGTH_SHORT
                                    ).show()
                                    navController.popBackStack()
                                }
                                TripInfoEvent.SaveFailed -> Toast.makeText(
                                    this@HomeActivity, R.string.failed_to_set_reminder, Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    ObaTheme {
                        TripInfoRoute(
                            viewModel = infoVm,
                            onBack = { navController.popBackStack() },
                            onSave = {
                                ActivityCompat.requestPermissions(
                                    this@HomeActivity,
                                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                    PermissionUtils.NOTIFICATION_PERMISSION_REQUEST
                                )
                                infoVm.save()
                            },
                            onDelete = {
                                confirmDeleteReminder(this@HomeActivity) {
                                    ReminderUtils.requestDeleteAlarm(
                                        this@HomeActivity,
                                        ObaContract.Trips.buildUri(infoTripId, infoStopId)
                                    )
                                    navController.popBackStack()
                                }
                            },
                            onShowRoute = {
                                infoVm.routeId()?.let { navController.navigate(NavRoutes.routeInfo(it)) }
                            },
                            onShowStop = {
                                navController.navigate(NavRoutes.arrivals(infoStopId, infoVm.stopName()))
                            },
                        )
                    }
                }
                // Agencies destination (Campaign C): the transit agencies in the current region.
                // Reached in-app from the help menu (HelpAction.AGENCIES → AgenciesActivity facade →
                // HomeActivity → translator). State lives in the Hilt AgenciesViewModel. Non-exported.
                composable(NavRoutes.AGENCIES) {
                    ObaTheme {
                        AgenciesRoute(
                            viewModel = hiltViewModel(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                // Regions destination (Campaign C): the manual OBA region (server) picker. Reached
                // in-app from Settings (RegionsActivity facade → HomeActivity → translator). Selecting
                // a region is terminal; on selection (which may disable auto-select, surfaced via the
                // toast) we pop back, matching the legacy "set region, return home" behavior.
                composable(NavRoutes.REGIONS) {
                    ObaTheme {
                        RegionsRoute(
                            viewModel = hiltViewModel(),
                            onBack = { navController.popBackStack() },
                            onRegionSelected = { autoSelectDisabled ->
                                if (autoSelectDisabled) {
                                    Toast.makeText(
                                        this@HomeActivity,
                                        R.string.region_disabled_auto_selection,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                navController.popBackStack()
                            },
                        )
                    }
                }
                // About destination (Campaign C): version / license / contributor info. Reached in-app
                // from Settings (AboutActivity facade → HomeActivity → translator). No VM; the version
                // line is computed from the package info via buildVersionText. Non-exported.
                composable(NavRoutes.ABOUT) {
                    ObaTheme {
                        AboutScreen(
                            versionText = buildVersionText(LocalContext.current),
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                // Donation "learn more" destination (Campaign C): the why-donate explainer. Reached
                // in-app from the home donation card (DonationLearnMoreActivity facade → HomeActivity →
                // translator). The donate button reproduces the former Activity's behavior: dismiss any
                // pending donation requests, open the donations page, then pop back. Non-exported.
                composable(NavRoutes.DONATION_LEARN_MORE) {
                    ObaTheme {
                        DonationLearnMoreScreen(
                            onBack = { navController.popBackStack() },
                            onDonate = {
                                Application.getDonationsManager().dismissDonationRequests()
                                startActivity(
                                    Application.getDonationsManager().buildOpenDonationsPageIntent()
                                )
                                navController.popBackStack()
                            },
                        )
                    }
                }
                // Settings destination (Campaign C; former SettingsActivity): a pure-Compose settings
                // screen ([SettingsRoute]). Reached in-app from the home drawer's Settings item and from
                // the report flow (region-validate dialog, with the SHOW_CHECK_REGION_DIALOG extra on the
                // HomeActivity intent). Host-bound actions (theme recreate, go-home, donate/browser) are
                // passed as lambdas; the Advanced sub-screen is its own destination below.
                composable(NavRoutes.SETTINGS) {
                    ObaTheme {
                        SettingsRoute(
                            onNavigateToRegions = { navController.navigate(NavRoutes.REGIONS) },
                            onNavigateToAbout = { navController.navigate(NavRoutes.ABOUT) },
                            onNavigateToAdvanced = {
                                navController.navigate(NavRoutes.SETTINGS_ADVANCED)
                            },
                            onBack = { navController.popBackStack() },
                            onRecreate = { recreate() },
                            onGoHomeResetTutorial = { NavHelp.goHome(this@HomeActivity, true) },
                            onOpenDonate = {
                                startActivity(
                                    Application.getDonationsManager().buildOpenDonationsPageIntent()
                                )
                            },
                            onOpenPoweredByOba = {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(getString(R.string.powered_by_oba_url))
                                    )
                                )
                            },
                        )
                    }
                }
                composable(NavRoutes.SETTINGS_ADVANCED) {
                    ObaTheme {
                        AdvancedSettingsRoute(
                            onBack = { navController.popBackStack() },
                            onRefreshRegions = {
                                RegionRefresher.refresh(this@HomeActivity, null) { changed ->
                                    this@HomeActivity.onRegionTaskFinished(changed)
                                }
                            },
                            onOtpUrlChanged = { setOtpCustomAPIUrlChanged(true) },
                            onGoHome = { NavHelp.goHome(this@HomeActivity, false) },
                        )
                    }
                }
                // Report flow (Campaign C; former ReportActivity / CustomerServiceActivity /
                // InfrastructureIssueActivity). The chooser ([REPORT]) shows the region-validate dialog
                // (if needed) then the type list; a tapped type navigates in-NavHost to customer service
                // or the infrastructure-issue screen, so back returns to the chooser (today's behavior).
                // The stop/location context rides on this activity's intent (from the launch facade) and
                // is read by the issue destination. Non-exported; no aliases.
                composable(NavRoutes.REPORT) {
                    ObaTheme {
                        ReportDestination(navController = navController)
                    }
                }
                composable(NavRoutes.CUSTOMER_SERVICE) {
                    ObaTheme {
                        CustomerServiceDestination(navController = navController)
                    }
                }
                composable(
                    NavRoutes.INFRASTRUCTURE_ISSUE,
                    arguments = listOf(
                        navArgument(NavRoutes.ARG_SELECTED_SERVICE) {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                    ),
                ) { backStackEntry ->
                    val selectedService =
                        backStackEntry.arguments?.getString(NavRoutes.ARG_SELECTED_SERVICE)
                    ObaTheme {
                        InfrastructureIssueDestination(
                            navController = navController,
                            selectedService = selectedService,
                        )
                    }
                }
                // Survey web view destination (Campaign C): the external-survey WebView. Reached in-app
                // from the home survey overlay (SurveyWebViewActivity facade → HomeActivity → translator).
                // The survey URL is the nav-arg. Non-exported; no alias.
                composable(
                    NavRoutes.SURVEY_WEB_VIEW,
                    arguments = listOf(
                        navArgument(NavRoutes.ARG_URL) { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    val url = backStackEntry.arguments?.getString(NavRoutes.ARG_URL).orEmpty()
                    ObaTheme {
                        SurveyWebViewScreen(
                            url = url,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                // Feedback destination (Campaign C): the post-trip destination-reminder feedback screen.
                // Reached only from the post-trip notification's Yes/No actions (NavigationService →
                // FeedbackActivity facade → HomeActivity → translator). On send it runs the submit/log
                // glue (FeedbackSubmitter) then pops back. Non-exported; no alias.
                composable(
                    NavRoutes.FEEDBACK,
                    arguments = listOf(
                        navArgument(NavRoutes.ARG_FEEDBACK_RESPONSE) {
                            type = NavType.IntType; defaultValue = 0
                        },
                        navArgument(NavRoutes.ARG_LOG_FILE) {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                        navArgument(NavRoutes.ARG_TRIP_ID) {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                        navArgument(NavRoutes.ARG_NOTIFICATION_ID) {
                            type = NavType.IntType; defaultValue = 0
                        },
                    ),
                ) { backStackEntry ->
                    val response =
                        backStackEntry.arguments?.getInt(NavRoutes.ARG_FEEDBACK_RESPONSE) ?: 0
                    val logFile = backStackEntry.arguments?.getString(NavRoutes.ARG_LOG_FILE)
                    val context = LocalContext.current
                    val submitter = remember(logFile) {
                        FeedbackSubmitter(context.applicationContext, prefsRepository, logFile)
                    }
                    ObaTheme {
                        FeedbackScreen(
                            initialLiked = response == FeedbackActivity.FEEDBACK_YES,
                            initialSendLogs = submitter.shareLogsPref(),
                            onBack = { navController.popBackStack() },
                            onSendLogsChanged = submitter::setShareLogs,
                            onSend = { liked, text ->
                                submitter.submit(liked, text)
                                navController.popBackStack()
                            },
                        )
                    }
                }
                // Search results (system ACTION_SEARCH + the home top-bar search field). The query is a
                // nav-arg; result taps route to the in-NavHost destinations (route info / arrivals) or
                // the map. Re-search when the query arg changes (a fresh search reuses this entry).
                composable(
                    NavRoutes.SEARCH,
                    arguments = listOf(
                        navArgument(NavRoutes.ARG_QUERY) {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { backStackEntry ->
                    val query = backStackEntry.arguments?.getString(NavRoutes.ARG_QUERY).orEmpty()
                    val searchVm: SearchResultsViewModel = hiltViewModel()
                    LaunchedEffect(query) {
                        ObaAnalytics.reportSearchEvent(
                            Application.get().plausibleInstance, firebaseAnalytics, query
                        )
                        searchVm.search(query)
                    }
                    ObaTheme {
                        SearchResultsRoute(
                            viewModel = searchVm,
                            onBack = { navController.popBackStack() },
                            onRouteListStops = { route ->
                                DBUtil.addRouteToDB(
                                    this@HomeActivity, route.id, route.shortName, route.longName, route.url
                                )
                                navController.navigate(NavRoutes.routeInfo(route.id))
                            },
                            onRouteShowOnMap = { route ->
                                DBUtil.addRouteToDB(
                                    this@HomeActivity, route.id, route.shortName, route.longName, route.url
                                )
                                HomeActivity.start(this@HomeActivity, route.id)
                            },
                            onStopArrivals = { stop ->
                                navController.navigate(NavRoutes.arrivals(stop.id))
                            },
                            onStopShowOnMap = { stop ->
                                HomeActivity.start(
                                    this@HomeActivity, stop.id, stop.latitude, stop.longitude
                                )
                            },
                        )
                    }
                }
                // The three "My*" tabbed list destinations (Campaign C). Reached from static app
                // shortcuts + old pinned tab:// shortcuts (the translator maps the tag to the route) and,
                // for Recent, the toolbar overflow. Tab wiring lives in MyListScreens.kt; the per-tab VMs
                // are scoped to the back-stack entry. The legacy CREATE_SHORTCUT picker mode is dropped.
                val tabArg = listOf(
                    navArgument(NavRoutes.ARG_TAB) {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                )
                composable(NavRoutes.MY_STOPS, arguments = tabArg) { entry ->
                    ObaTheme {
                        MyStopsDestination(
                            initialTag = entry.arguments?.getString(NavRoutes.ARG_TAB),
                            prefsRepository = prefsRepository,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(NavRoutes.MY_ROUTES, arguments = tabArg) { entry ->
                    ObaTheme {
                        MyRoutesDestination(
                            initialTag = entry.arguments?.getString(NavRoutes.ARG_TAB),
                            prefsRepository = prefsRepository,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(NavRoutes.MY_RECENT, arguments = tabArg) { entry ->
                    ObaTheme {
                        MyRecentDestination(
                            initialTag = entry.arguments?.getString(NavRoutes.ARG_TAB),
                            prefsRepository = prefsRepository,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                // "My Reminders" destination (Campaign C): the standalone saved-trip-reminders list.
                // A single ReminderListDestination in a Scaffold (not MyTabsScreen) with a sort action.
                // Entry-scoped VM. The home drawer embeds the same destination separately.
                composable(NavRoutes.MY_REMINDERS) {
                    val reminders = rememberListVm("reminders") {
                        RemindersRepository(applicationContext)
                    }
                    ObaTheme {
                        Scaffold(
                            topBar = {
                                ObaTopAppBar(
                                    title = stringResource(R.string.app_name),
                                    onBack = { navController.popBackStack() }
                                ) {
                                    IconButton(onClick = {
                                        chooseSortOrder(
                                            PreferenceUtils.getReminderSortOrderFromPreferences(),
                                            R.array.sort_reminders
                                        ) { reminders.setSort(it) }
                                    }) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_action_content_sort),
                                            contentDescription = stringResource(R.string.menu_option_sort_by)
                                        )
                                    }
                                }
                            }
                        ) { padding ->
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                ReminderListDestination(
                                    reminders,
                                    emptyText = R.string.trip_list_notrips,
                                    onClick = { editReminder(it) },
                                    actions = { reminderActions(it) }
                                )
                            }
                        }
                    }
                }
                // Night light (Campaign C): the flashing screen riders show to flag drivers. Reached
                // from the arrivals overflow and old pinned launcher shortcuts (frozen NightLightActivity
                // name → alias → HomeActivity, routed by component name). Window/brightness/orientation
                // concerns live in NightLightRoute for as long as it's on the back stack.
                composable(NavRoutes.NIGHT_LIGHT) {
                    ObaTheme {
                        NightLightRoute(onBack = { navController.popBackStack() })
                    }
                }
                // Trip plan destination (Campaign C): the trip-planning form + results sheet. Reached
                // in-app from the home drawer's "Plan a trip"; re-entered from a RealtimeService trip-
                // update notification (RealtimeService tags the open intent with the TRIP_PLAN route).
                // The destination ports the former TripPlanActivity's Android glue. Non-exported.
                composable(NavRoutes.TRIP_PLAN) {
                    ObaTheme {
                        TripPlanDestination(
                            navController = navController,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                // Trip plan "pick a point on the map" sub-screen (Campaign C; former
                // TripPlanLocationPickerActivity). Reached only from the trip-plan destination's
                // from/to "pick on map"; hands the chosen point back via this entry's previous
                // back-stack SavedStateHandle. The initial center arrives as decimal-string lat/lon.
                composable(
                    NavRoutes.TRIP_PLAN_PICK_LOCATION,
                    arguments = listOf(
                        navArgument(NavRoutes.ARG_PICK_LAT) {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                        navArgument(NavRoutes.ARG_PICK_LON) {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                    ),
                ) { entry ->
                    ObaTheme {
                        TripPlanLocationPickerDestination(
                            navController = navController,
                            lat = entry.arguments?.getString(NavRoutes.ARG_PICK_LAT)?.toDoubleOrNull(),
                            lon = entry.arguments?.getString(NavRoutes.ARG_PICK_LON)?.toDoubleOrNull(),
                        )
                    }
                }
            }
        }

        setupNavigationDrawer()

        setupMapState()

        // Initialize the map mode from the intent (route deep link vs nearby stops), unless the view
        // model already has a mode (it survives a configuration change).
        if (mapViewModel.currentMapMode == null) {
            initMapMode(savedInstanceState)
        }

        pushEnvironment()

        TravelBehaviorManager(this, applicationContext).registerTravelBehaviorParticipant()

        // The VM owns the startup region-check decision: on the very first launch without permission it
        // defers until the map's permission result, otherwise it checks now. (The permission read needs
        // a Context, so it stays here.)
        viewModel.onHomeStarted(
            PermissionUtils.hasGrantedAtLeastOnePermission(this, PermissionUtils.LOCATION_PERMISSIONS)
        )

        // Check to see if we should show the welcome tutorial
        val b = intent.extras
        if (b != null) {
            if (b.getBoolean(ShowcaseViewUtils.TUTORIAL_WELCOME)) {
                ShowcaseViewUtils.showTutorial(ShowcaseViewUtils.TUTORIAL_WELCOME, this, null, false)
            }
        }

        observeRegionResolved()
    }

    /**
     * A warm re-launch (singleTop) carrying an external screen intent — FCM CLEAR_TOP, the
     * NavigationService reminder PendingIntent, a pinned shortcut. Stage its route; the NavHost's
     * LaunchedEffect navigates. (Cold launches are handled in onCreate.)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLinkRoute.value = routeForIntent(intent)
    }

    /**
     * Translates an incoming external intent into the NavHost route it should open, or null to leave
     * the home/map path untouched. Handles the FCM `arrival_and_departure` payload (delete the
     * reminder, then open arrivals) and the explicit-component screen intents that carry a
     * `content://<authority>/<path>/{id}` data URI (read by path segment, since the authority is
     * flavor-specific). MapParams.* focus / route-mode launches have no data URI and return null —
     * they stay map behavior (initMapMode / setupMapState).
     */
    private fun routeForIntent(intent: Intent?): String? {
        if (intent == null) return null
        // In-app / cross-screen launches carry their destination route verbatim (see [navIntent]).
        intent.getStringExtra(EXTRA_NAV_ROUTE)?.let { return it }
        // The exported `onebusaway://add-region?oba-url=…&otp-url=…` deep link (former SettingsActivity
        // VIEW filter). Apply the custom API URL(s), clear the current region, and stay on the home/map
        // path (the legacy handler immediately went Home), so return null after processing.
        val data = intent.data
        if (data?.scheme == "onebusaway" && data.host == "add-region") {
            applyAddRegionDeepLink(data)
            return null
        }
        // System search (HomeActivity is the default_searchable target): open the search destination.
        if (intent.action == Intent.ACTION_SEARCH) {
            return NavRoutes.search(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        }
        intent.getStringExtra("arrival_and_departure")?.let { arrivalJson ->
            ReminderUtils.handleArrivalPayload(applicationContext, arrivalJson)
            return ReminderUtils.getStopIdFromPayload(arrivalJson)?.let { NavRoutes.arrivals(it, null) }
        }
        // Trip details carries its args as extras (no data URI) — e.g. the arrivals "show trip" / map
        // vehicle tap / NavigationService reminder notification.
        intent.getStringExtra(NavRoutes.ARG_TRIP_ID)?.let { tripId ->
            return NavRoutes.tripDetails(
                tripId,
                intent.getStringExtra(NavRoutes.ARG_STOP_ID),
                intent.getStringExtra(NavRoutes.ARG_SCROLL_MODE),
            )
        }
        // Old pinned night-light launcher shortcuts target the frozen NightLightActivity component
        // (now an alias → HomeActivity) with no data URI; the alias name is preserved in the launched
        // intent's component, so route by class name. (New pins use the NAV_ROUTE extra above.)
        if (intent.component?.className?.endsWith("NightLightActivity") == true) {
            return NavRoutes.NIGHT_LIGHT
        }
        // Old pinned launcher shortcuts (the deleted My* shells/aliases) carry a `tab://<tag>` data URI.
        // Map the tag to the matching My* list route so they keep opening the right screen.
        if (intent.data?.scheme == "tab") {
            val tag = intent.data?.let { MyTabs.defaultTabFromUri(it) }
            return when (tag) {
                MyTabs.RECENT_ROUTES -> NavRoutes.myRoutes(MyTabs.RECENT_ROUTES)
                else -> NavRoutes.myStops(tag)
            }
        }
        val segments = intent.data?.pathSegments ?: return null
        return when (segments.firstOrNull()) {
            ObaContract.Stops.PATH -> intent.data?.lastPathSegment?.let { stopId ->
                NavRoutes.arrivals(stopId, intent.getStringExtra(ArrivalsIntents.STOP_NAME))
            }
            ObaContract.Routes.PATH -> intent.data?.lastPathSegment?.let { routeId ->
                NavRoutes.routeInfo(routeId)
            }
            // Trip reminder editor: ids in the data URI path; the create path adds the trip context
            // as extras (edit path omits them). content://…/trips/{tripId}/{stopId}.
            ObaContract.Trips.PATH -> if (segments.size >= 3) {
                NavRoutes.tripInfo(
                    tripId = segments[1],
                    stopId = segments[2],
                    routeId = intent.getStringExtra(NavRoutes.ARG_ROUTE_ID),
                    routeName = intent.getStringExtra(NavRoutes.ARG_ROUTE_NAME),
                    stopName = intent.getStringExtra(NavRoutes.ARG_STOP_NAME),
                    headsign = intent.getStringExtra(NavRoutes.ARG_HEADSIGN),
                    departTime = intent.getLongExtra(NavRoutes.ARG_DEPART_TIME, 0L),
                    stopSequence = intent.getIntExtra(NavRoutes.ARG_STOP_SEQUENCE, 0),
                    serviceDate = intent.getLongExtra(NavRoutes.ARG_SERVICE_DATE, 0L),
                    vehicleId = intent.getStringExtra(NavRoutes.ARG_VEHICLE_ID),
                )
            } else null
            else -> null
        }
    }

    /**
     * Applies the `onebusaway://add-region` deep link (ported from SettingsActivity.onAddCustomRegion):
     * set the custom OBA / OTP API URLs from the query params (validating each), clearing the current
     * region when a valid OBA URL is supplied. The legacy handler then went Home + finished; here the
     * caller ([routeForIntent]) returns null so we simply stay on the home/map path.
     */
    private fun applyAddRegionDeepLink(deepLink: Uri) {
        val obaCustomUrl = deepLink.getQueryParameter("oba-url")
        val otpCustomUrl = deepLink.getQueryParameter("otp-url")

        if (obaCustomUrl != null && SettingsSupport.validateUrl(obaCustomUrl)) {
            Application.get().setCustomApiUrl(obaCustomUrl)
            regionRepository.clear()
        }
        if (otpCustomUrl != null && SettingsSupport.validateUrl(otpCustomUrl)) {
            Application.get().setCustomOtpApiUrl(otpCustomUrl)
        }
    }

    /**
     * Subscribes to the one [HomeEvent] the activity (as opposed to [HomeScreen]) cares about:
     * [HomeEvent.RegionResolved]. The sheet/drawer commands on the same flow are consumed by HomeScreen,
     * so this filters to just the region event rather than a `when` with an empty `else`.
     */
    private fun observeRegionResolved() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events
                    .filterIsInstance<HomeEvent.RegionResolved>()
                    .collect(::onRegionResolved)
            }
        }
    }

    /**
     * The host-only effects of a region resolve. The map re-zoom (VM onRegionChanged), nav items (VM
     * refreshNavItems), the region-found snackbar (HomeUiState.regionFoundName), what's-new (HelpFeature)
     * and the survey (SurveyFeature) are all self-wired elsewhere; what remains here is analytics plus a
     * chrome-environment refresh (a region change can flip bikeshare availability).
     */
    private fun onRegionResolved(event: HomeEvent.RegionResolved) {
        reportRegionToAnalytics(event)
        pushEnvironment()
    }

    /** Reports an auto-selected region change to analytics (a manual pick passes a null name, so none). */
    private fun reportRegionToAnalytics(event: HomeEvent.RegionResolved) {
        if (event.changed && event.regionName != null) {
            ObaAnalytics.setRegion(
                Application.get().plausibleInstance,
                firebaseAnalytics,
                event.regionName
            )
        }
    }

    // --- Settings preference-screen host glue (re-homed from the former SettingsActivity) ------------

    /**
     * The experimental-regions region-refresh callback (invoked by [RegionRefresher] from the advanced
     * settings screen; ported from SettingsActivity.onRegionTaskFinished): on a region change, reset the
     * OTP API version, toast the newly found region (when auto-selecting), and re-home.
     */
    fun onRegionTaskFinished(currentRegionChanged: Boolean) {
        if (currentRegionChanged) {
            Application.get().setUseOldOtpApiUrlVersion(false)
            val region = regionRepository.region.value
            if (PreferenceUtils.getBoolean(getString(R.string.preference_key_auto_select_region), true)
                && region != null
            ) {
                Toast.makeText(
                    this,
                    getString(R.string.region_region_found, region.name),
                    Toast.LENGTH_LONG
                ).show()
            }
            NavHelp.goHome(this, false)
        }
    }

    /** Sets the initial map mode from the launch sources (route deep link, else nearby stops). */
    private fun initMapMode(savedInstanceState: Bundle?) {
        val src = savedInstanceState ?: intent.extras
        mapViewModel.setMode(
            resolveMapMode(
                mode = src?.getString(MapParams.MODE),
                routeId = src?.getString(MapParams.ROUTE_ID),
                zoomToRoute = src?.getBoolean(MapParams.ZOOM_TO_ROUTE, false) ?: false,
            )
        )
    }

    /**
     * Reads the initial map camera candidates from the launch sources — the primary seed (saved state,
     * else the intent) and the persisted last view — and hands them to the pure [resolveMapSeed] for the
     * precedence decision (so the map opens where you left it). Replaces ObaMapHost.resolveInitialCamera.
     */
    private fun readMapSeed(savedInstanceState: Bundle?): MapCameraSeed {
        val src = savedInstanceState ?: intent.extras
        val primary = MapCameraSeed(
            lat = src?.getDouble(MapParams.CENTER_LAT, 0.0) ?: 0.0,
            lon = src?.getDouble(MapParams.CENTER_LON, 0.0) ?: 0.0,
            zoom = src?.getFloat(MapParams.ZOOM, MAP_DEFAULT_ZOOM) ?: MAP_DEFAULT_ZOOM,
        )
        val restored = Bundle().also { PreferenceUtils.maybeRestoreMapViewToBundle(it) }
        val persisted = MapCameraSeed(
            lat = restored.getDouble(MapParams.CENTER_LAT, 0.0),
            lon = restored.getDouble(MapParams.CENTER_LON, 0.0),
            // The persisted zoom defaults to the primary zoom (so an empty persisted view keeps it).
            zoom = restored.getFloat(MapParams.ZOOM, primary.zoom),
        )
        return resolveMapSeed(primary, persisted)
    }

    override fun onStart() {
        super.onStart()
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val isTalkBackEnabled = am.isTouchExplorationEnabled
        ObaAnalytics.setAccessibility(firebaseAnalytics, isTalkBackEnabled)
    }

    override fun onResume() {
        super.onResume()
        // Re-snapshot preferences + app-global flags so the ViewModel recomputes the chrome/overlay
        // visibility gates (zoom controls, left-hand mode, layers FAB, weather). The map's own
        // resume/pause is handled by MapFeature; the survey/donation/weather modules self-wire theirs.
        pushEnvironment()
    }

    override fun onPause() {
        ShowcaseViewUtils.hideShowcaseView()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persist the map's mode + camera so a process-death restore re-enters the same mode/viewport
        // (the focused stop is persisted by HomeViewModel's SavedStateHandle). Config changes survive
        // in the view model itself; these feed initMapMode()/readMapSeed() on recreation.
        val (modeString, routeId) = mapModeToParams(mapViewModel.currentMapMode)
        outState.putString(MapParams.MODE, modeString)
        routeId?.let { outState.putString(MapParams.ROUTE_ID, it) }
        mapViewModel.camera.value?.let {
            outState.putDouble(MapParams.CENTER_LAT, it.center.latitude)
            outState.putDouble(MapParams.CENTER_LON, it.center.longitude)
            outState.putFloat(MapParams.ZOOM, it.zoom.toFloat())
        }
    }

    private fun goToNavDrawerItem(item: HomeNavItem, reselect: Boolean) {
        // Selectable list tabs render as Compose overlays (HomeScreen reads selectedItem); only NEARBY
        // drives the hosted map. Activity-launcher items start their screen; in-place items need no
        // imperative work (the title/content come from selectedItem). [reselect] suppresses the
        // redundant re-show / re-report when the active in-place tab is tapped again.
        when (item) {
            HomeNavItem.NEARBY -> if (!reselect) showMap()
            HomeNavItem.STARRED_STOPS,
            HomeNavItem.STARRED_ROUTES,
            HomeNavItem.MY_REMINDERS -> Unit
            HomeNavItem.PLAN_TRIP ->
                pendingDeepLinkRoute.value = NavRoutes.TRIP_PLAN
            HomeNavItem.PAY_FARE -> UIUtils.launchPayMyFareApp(this)
            HomeNavItem.SETTINGS ->
                pendingDeepLinkRoute.value = NavRoutes.SETTINGS
            // Hide "Contact Us" when a custom API URL is set (no contact email to use).
            HomeNavItem.HELP -> helpViewModel.showMenu(TextUtils.isEmpty(Application.get().customApiUrl))
            HomeNavItem.SEND_FEEDBACK -> goToSendFeedBack()
            HomeNavItem.OPEN_SOURCE ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.open_source_github))))
        }
        if (!reselect) reportNavAnalytics(item)
        // The survey is a Compose overlay in the map Box; a list tab's opaque destination covers it,
        // so it hides itself off NEARBY with no imperative work here.
        // Recompute the donation / weather / layers gates for the new selection.
        pushEnvironment()
    }

    /** Per-item menu analytics, preserving the legacy labels (PAY_FARE intentionally reports none). */
    private fun reportNavAnalytics(item: HomeNavItem) {
        val label = item.analyticsLabelRes ?: return
        ObaAnalytics.reportUiEvent(
            firebaseAnalytics,
            Application.get().plausibleInstance,
            PlausibleAnalytics.REPORT_MENU_EVENT_URL,
            getString(label),
            null
        )
    }

    private fun showMap() {
        // Latch the map shown (in the VM) so MapFeature composes ObaMap() — deferring the map SDK init
        // to the first NEARBY selection, then staying composed (list tabs draw an opaque destination
        // over it rather than tearing it down), so this is idempotent. (MapFeature does the eager
        // location-permission prompt once the map shows.)
        viewModel.onMapShown()
        // The survey self-triggers when NEARBY is shown (SurveyFeature reads selectedItem + regionReady).
    }

    // Keeps vehicle markers from being hidden under the route-mode header (was RoutePopup's logic).
    private val routeHeaderMarkerPaddingPx by lazy {
        resources.getDimensionPixelSize(R.dimen.map_route_vehicle_markers_padding)
    }

    /** The Compose route header reports its measured height; set the map's top padding accordingly. */
    private fun onRouteHeaderHeight(heightPx: Int) {
        val top = if (heightPx > 0) heightPx + routeHeaderMarkerPaddingPx else 0
        mapViewModel.setTopPadding(top)
    }

    /** The route header's cancel button: return to stop mode, preserving the current zoom + center. */
    private fun onCancelRouteMode() {
        mapViewModel.exitRouteMode()
    }

    /**
     * Runs the global search for [query] (from [HomeTopBar]'s search field) by navigating to the
     * search destination (staged through [pendingDeepLinkRoute] since the navController lives in the
     * NavHost composition, not here).
     */
    private fun onSearch(query: String) {
        pendingDeepLinkRoute.value = NavRoutes.search(query)
    }

    /** Opens the recent stops/routes screen (the toolbar overflow item) — the MY_RECENT destination. */
    private fun onRecentStopsRoutes() {
        ShowcaseViewUtils.doNotShowTutorial(this, ShowcaseViewUtils.TUTORIAL_RECENT_STOPS_ROUTES)
        pendingDeepLinkRoute.value = NavRoutes.myRecent()
    }

    /** Sort the visible list tab (the dialog + persisted order live with the shared list helpers). */
    private fun onListSortSelected() = when (viewModel.uiState.value.selectedItem) {
        HomeNavItem.STARRED_STOPS ->
            chooseSortOrder(PreferenceUtils.getStopSortOrderFromPreferences(), R.array.sort_stops) {
                listVms.starredStops.setSort(it)
            }
        HomeNavItem.STARRED_ROUTES ->
            chooseSortOrder(PreferenceUtils.getStopSortOrderFromPreferences(), R.array.sort_stops) {
                listVms.starredRoutes.setSort(it)
            }
        HomeNavItem.MY_REMINDERS ->
            chooseSortOrder(PreferenceUtils.getReminderSortOrderFromPreferences(), R.array.sort_reminders) {
                listVms.reminders.setSort(it)
            }
        else -> Unit
    }

    /** Clear-all confirmation for the visible starred tab (recents/reminders aren't clearable here). */
    private fun onListClearSelected() = when (viewModel.uiState.value.selectedItem) {
        HomeNavItem.STARRED_STOPS ->
            confirmClear(
                R.string.my_option_clear_starred_stops_title,
                R.string.my_option_clear_starred_stops_confirm
            ) { listVms.starredStops.clearAll() }
        HomeNavItem.STARRED_ROUTES ->
            confirmClear(
                R.string.my_option_clear_starred_routes_title,
                R.string.my_option_clear_starred_routes_confirm
            ) { listVms.starredRoutes.clearAll() }
        else -> Unit
    }

    // --- Help-menu actions that are Activity operations (the dialog-opening ones live in HelpFeature) ---

    private fun onHelpAction(action: HelpAction) {
        when (action) {
            HelpAction.TUTORIALS -> {
                ShowcaseViewUtils.resetAllTutorials(this)
                NavHelp.goHome(this, true)
            }
            HelpAction.AGENCIES -> AgenciesActivity.start(this)
            HelpAction.TWITTER -> {
                var twitterUrl = TWITTER_URL
                val region = regionRepository.region.value
                if (region != null && !TextUtils.isEmpty(region.twitterUrl)) {
                    twitterUrl = region.twitterUrl
                }
                UIUtils.goToUrl(this, twitterUrl)
                ObaAnalytics.reportUiEvent(
                    firebaseAnalytics,
                    Application.get().plausibleInstance,
                    PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                    getString(R.string.analytics_label_twitter),
                    null
                )
            }
            HelpAction.CONTACT_US -> goToSendFeedBack()
            // LEGEND / WHATS_NEW open dialogs — handled by HelpFeature against HelpViewModel.
            HelpAction.LEGEND, HelpAction.WHATS_NEW -> Unit
        }
    }

    private fun onWhatsNewDismissed() {
        val showOptOut = prefsRepository.getBoolean(ShowcaseViewUtils.TUTORIAL_OPT_OUT_DIALOG, true)
        if (showOptOut) {
            ShowcaseViewUtils.showOptOutDialog(this)
        }
    }

    /**
     * Called (from ArrivalsSheetHost's responses collector) when the panel has new arrival info. The
     * HomeViewModel owns the pending-focus latch + the overlay-expanded decision; when it reports a
     * pending focus we call [MapViewModel.focusStop] with the response's raw io/elements stop + routes,
     * which recenters the map + render-focuses the stop.
     */
    private fun onArrivalsLoaded(response: ObaArrivalInfoResponse) {
        val stop = response.stop ?: return
        viewModel.onArrivalsLoaded()?.let { overlayExpanded ->
            mapViewModel.focusStop(stop, response.routes, overlayExpanded)
        }
        // Show arrival info related tutorials
        showArrivalInfoTutorials(response)
    }

    /**
     * Triggers the various tutorials related to arrival info and the sliding panel header
     */
    private fun showArrivalInfoTutorials(response: ObaArrivalInfoResponse) {
        // If we're already showing a ShowcaseView, we don't want to stack another on top
        if (ShowcaseViewUtils.isShowcaseViewShowing()) {
            return
        }

        // If we can't see the map or arrivals sheet, we can't see the arrival info, so return. The map
        // is composed (and stays so — lists overlay it) once NEARBY is first selected, so mapComposed
        // == "map shown".
        if (!viewModel.uiState.value.mapComposed ||
            viewModel.lastSettledSheet == ArrivalsSheetState.Hidden
        ) {
            return
        }

        // The arrival-header tutorials (arrival info / sliding panel / star route) anchored to the
        // legacy header's Views, which the Compose panel no longer exposes, so they've been retired.
        // The general "recent stops/routes" tutorial still applies.
        ShowcaseViewUtils.showTutorial(
            ShowcaseViewUtils.TUTORIAL_RECENT_STOPS_ROUTES, this, null, false
        )
    }

    private fun goToSendFeedBack() {
        val focusedStop = viewModel.uiState.value.focusedStop
        if (focusedStop != null) {
            ReportActivity.start(
                this, focusedStop.id, focusedStop.name, focusedStop.code,
                focusedStop.lat, focusedStop.lon
            )
        } else {
            val loc = locationRepository.lastKnownLocation()
            if (loc != null) {
                ReportActivity.start(this, loc.latitude, loc.longitude)
            } else {
                ReportActivity.start(this)
            }
        }
    }

    /**
     * Carries out the region-resolved side effects (was the `ObaRegionsTask.Callback` override): map
     * re-zoom is done by the caller; this handles What's-New, the nav-drawer redraw, and the
     * region-found toast. Body preserved verbatim from the legacy callback.
     */
    /**
     * Snapshots the non-reactive environment (preferences + app-global flags) and feeds it to the
     * ViewModel, which recomputes the gated chrome visibility (zoom controls, left-hand mode, layers
     * FAB). Called whenever those inputs may have changed: onResume, after a nav selection, after a
     * region update, and after toggling the bikeshare layer. (The weather + donation feature modules
     * read their own prefs.)
     */
    private fun pushEnvironment() {
        viewModel.onEnvironmentRefreshed(
            HomeEnvironment(
                bikeshareEnabled = Application.isBikeshareEnabled(),
                bikeshareActive = LayerUtils.isBikeshareLayerVisible(this),
                zoomControlsPref =
                    prefsRepository.getBoolean(R.string.preference_key_show_zoom_controls, false),
                leftHandMode =
                    prefsRepository.getBoolean(R.string.preference_key_left_hand_mode, false),
            )
        )
    }

    private fun setupNavigationDrawer() {
        // The nav items themselves are built by the ViewModel (init + on region resolve); here we only
        // determine and apply the initial selection. The deep-link-vs-remembered-tab decision is the pure
        // initialNavItem() (the enum-name pref falls back to the legacy int position for pre-P16 installs;
        // process-death restore uses the VM's SavedStateHandle).
        val bundle = intent.extras
        val deepLinksToMap = bundle != null &&
            (bundle.getString(MapParams.ROUTE_ID) != null || bundle.getString(MapParams.STOP_ID) != null)
        val item = initialNavItem(
            persistedName = prefsRepository.getString(STATE_SELECTED_NAV_ITEM, null),
            legacyPosition = prefsRepository.getInt(STATE_SELECTED_POSITION, 0),
            deepLinksToMap = deepLinksToMap,
        )
        // Defer the first content selection until after onCreate (so the Compose content has composed
        // and lazy map/survey gating reads the applied selection).
        window.decorView.post { onHomeNavItemSelected(item) }
    }

    /** Routes a Compose-drawer selection to the ViewModel selection + the imperative per-item work. */
    private fun onHomeNavItemSelected(item: HomeNavItem) {
        // The VM owns the fresh-vs-re-tap decision (and the first-selection bookkeeping); a re-tap of
        // the active in-place tab returns false to suppress the redundant showMap()/analytics.
        val fresh = viewModel.selectNav(item)
        if (!item.launchesActivity) {
            // Remember the tab across sessions, keyed by enum name (the cross-session pref boundary).
            PreferenceUtils.saveString(STATE_SELECTED_NAV_ITEM, item.name)
        }
        goToNavDrawerItem(item, reselect = !fresh)
    }

    /**
     * Sets up the initial map state from the ViewModel's restored focus (process death / rotation,
     * via SavedStateHandle) or from an Intent that deep-links into a specific stop.
     */
    private fun setupMapState() {
        // A restored focus (SavedStateHandle) already drives the arrivals sheet via HomeScreen; otherwise
        // adopt a stop deep-linked through the intent (makeIntent). The VM decides which applies + marks
        // the focus pending so the map recenters + adds the marker once arrivals load.
        val b = intent.extras
        viewModel.applyInitialFocus(
            focusedStopFromExtras(
                stopId = b?.getString(MapParams.STOP_ID),
                stopName = b?.getString(MapParams.STOP_NAME),
                stopCode = b?.getString(MapParams.STOP_CODE),
                lat = b?.getDouble(MapParams.CENTER_LAT) ?: 0.0,
                lon = b?.getDouble(MapParams.CENTER_LON) ?: 0.0,
            )
        )
    }

    companion object {
        const val TWITTER_URL = "http://mobile.twitter.com/onebusaway"

        // Generic in-app entry point: a launcher facade builds an explicit HomeActivity intent
        // carrying the NavHost route to open (see [navIntent]); the translator (routeForIntent)
        // navigates there. Lets former screen Activities become thin facade objects with no
        // per-screen intent contract. (External contracts — shortcuts/FCM — use the data-URI branches.)
        const val EXTRA_NAV_ROUTE = "org.onebusaway.android.ui.HomeActivity.NAV_ROUTE"

        /** An intent that opens HomeActivity and navigates its NavHost to [route]. */
        @JvmStatic
        fun navIntent(context: Context, route: String): Intent =
            Intent(context, HomeActivity::class.java).putExtra(EXTRA_NAV_ROUTE, route)

        // The seed-camera default zoom (matches the former ObaMapHost.CAMERA_DEFAULT_ZOOM).
        private const val MAP_DEFAULT_ZOOM = 16f

        // The remembered nav tab, keyed by HomeNavItem.name. STATE_SELECTED_POSITION is the legacy
        // int key (NavigationDrawerFragment's), read once as a migration fallback for old installs.
        private const val STATE_SELECTED_NAV_ITEM = "home_selected_nav_item"
        private const val STATE_SELECTED_POSITION = "selected_navigation_drawer_position"

        /**
         * Starts HomeActivity with a particular stop focused with the center of
         * the map at a particular point.
         */
        @JvmStatic
        fun start(context: Context, focusId: String?, lat: Double, lon: Double) {
            context.startActivity(makeIntent(context, focusId, lat, lon))
        }

        /**
         * Starts HomeActivity with a particular stop focused with the center of
         * the map at a particular point.
         */
        @JvmStatic
        fun start(context: Context, stop: ObaStop) {
            context.startActivity(makeIntent(context, stop))
        }

        /**
         * Starts HomeActivity in "RouteMode", which shows stops along a route,
         * and does not get new stops when the user pans the map.
         */
        @JvmStatic
        fun start(context: Context, routeId: String) {
            context.startActivity(makeIntent(context, routeId))
        }

        /**
         * Returns an intent that will start HomeActivity with a particular stop
         * focused with the center of the map at a particular point.
         */
        @JvmStatic
        fun makeIntent(context: Context, focusId: String?, lat: Double, lon: Double): Intent {
            val myIntent = Intent(context, HomeActivity::class.java)
            myIntent.putExtra(MapParams.STOP_ID, focusId)
            myIntent.putExtra(MapParams.CENTER_LAT, lat)
            myIntent.putExtra(MapParams.CENTER_LON, lon)
            return myIntent
        }

        /**
         * Returns an intent that will start HomeActivity with a particular stop
         * focused with the center of the map at a particular point.
         */
        @JvmStatic
        fun makeIntent(context: Context, stop: ObaStop): Intent {
            val myIntent = Intent(context, HomeActivity::class.java)
            myIntent.putExtra(MapParams.STOP_ID, stop.id)
            myIntent.putExtra(MapParams.STOP_NAME, stop.name)
            myIntent.putExtra(MapParams.STOP_CODE, stop.stopCode)
            myIntent.putExtra(MapParams.CENTER_LAT, stop.latitude)
            myIntent.putExtra(MapParams.CENTER_LON, stop.longitude)
            return myIntent
        }

        /**
         * Returns an intent that starts HomeActivity in "RouteMode", which shows
         * stops along a route, and does not get new stops when the user pans the map.
         */
        @JvmStatic
        fun makeIntent(context: Context, routeId: String): Intent {
            val myIntent = Intent(context, HomeActivity::class.java)
            myIntent.putExtra(MapParams.MODE, MapParams.MODE_ROUTE)
            myIntent.putExtra(MapParams.ZOOM_TO_ROUTE, true)
            myIntent.putExtra(MapParams.ROUTE_ID, routeId)
            return myIntent
        }
    }
}
