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
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import com.google.firebase.analytics.FirebaseAnalytics
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.donations.DonationsManager
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.io.request.ObaArrivalInfoResponse
import org.onebusaway.android.map.MapHostDeps
import org.onebusaway.android.map.LayerInfo
import org.onebusaway.android.map.MapCommand
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.map.ObaMapHost
import org.onebusaway.android.map.OnFocusChangedListener
import org.onebusaway.android.map.OnProgressBarChangedListener
import org.onebusaway.android.report.ui.ReportActivity
import org.onebusaway.android.travelbehavior.TravelBehaviorManager
import org.onebusaway.android.ui.home.ArrivalsSheetState
import org.onebusaway.android.ui.home.DefaultRegionStatusRepository
import org.onebusaway.android.ui.home.DonationViewModel
import org.onebusaway.android.ui.home.DefaultStartupPreferencesRepository
import org.onebusaway.android.ui.home.DefaultWeatherRepository
import org.onebusaway.android.ui.home.DefaultWideAlertsRepository
import org.onebusaway.android.ui.home.FocusedStop
import org.onebusaway.android.ui.home.HelpAction
import org.onebusaway.android.ui.home.HomeEnvironment
import org.onebusaway.android.ui.home.HomeCallbacks
import org.onebusaway.android.ui.home.HomeEvent
import org.onebusaway.android.ui.home.HomeListViewModels
import org.onebusaway.android.ui.home.HomeNavItem
import org.onebusaway.android.ui.home.persistedNavItem
import org.onebusaway.android.ui.home.HomeScreen
import org.onebusaway.android.ui.home.HomeViewModel
import org.onebusaway.android.ui.mylists.RemindersRepository
import org.onebusaway.android.ui.mylists.StarredRoutesRepository
import org.onebusaway.android.ui.mylists.StarredStopsRepository
import org.onebusaway.android.ui.mylists.chooseSortOrder
import org.onebusaway.android.ui.mylists.confirmClear
import org.onebusaway.android.ui.mylists.hostListVm
import org.onebusaway.android.ui.survey.SurveyViewModel
import org.onebusaway.android.ui.weather.RegionCallback
import org.onebusaway.android.ui.weather.WeatherUtils
import org.onebusaway.android.util.LayerUtils
import org.onebusaway.android.util.PermissionUtils
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.ReminderUtils
import org.onebusaway.android.util.ShowcaseViewUtils
import org.onebusaway.android.util.UIUtils
import org.onebusaway.android.widealerts.GtfsAlertsHelper
import org.opentripplanner.routing.bike_rental.BikeRentalStation

class HomeActivity : AppCompatActivity(),
    OnFocusChangedListener,
    OnProgressBarChangedListener,
    RegionCallback {

    private val viewModel: HomeViewModel by viewModels {
        viewModelFactory {
            initializer {
                HomeViewModel(
                    createSavedStateHandle(),
                    DefaultWeatherRepository(),
                    DefaultWideAlertsRepository(),
                    DefaultRegionStatusRepository(applicationContext),
                    DefaultStartupPreferencesRepository(),
                    mapViewModel,
                )
            }
        }
    }

    /**
     * Whether the deferred first nav selection has run. Distinguishes the initial (posted) selection
     * from a user re-tap of the active tab, so the first selection always runs its per-item work even
     * when the restored tab matches the VM default. The selected tab itself lives in the VM.
     */
    private var navSelectionApplied = false

    // The map is composed directly by HomeScreen via ObaMap(); the host is a viewless *controller*
    // (mode controllers, overlays, region/focus/location, lifecycle, camera) created eagerly in
    // onCreate and handed the ready map via onMapReady(). Lifecycle/permission/state are forwarded from
    // this activity. The three other map screens still use the thin Fragment wrapper (view-owning host).
    private lateinit var mapHost: ObaMapHost

    // The Activity-scoped map render state, observed by ObaMap() in HomeScreen. Same instance the host
    // resolves from the Activity ViewModelStore, so the host's overlay mutations drive this composition.
    private val mapViewModel: MapViewModel by viewModels()

    // Gates ObaMap() composition: flipped true on the first NEARBY selection so the map SDK only
    // initializes then (and stays composed thereafter — list tabs draw over it, not tear it down).
    private var mapComposed by mutableStateOf(false)

    // The map survey (Compose), shown over the map on NEARBY. Activity-scoped.
    private val surveyViewModel: SurveyViewModel by viewModels()

    // The donation card feature module (Compose), shown over the map on NEARBY. Activity-scoped.
    private val donationViewModel: DonationViewModel by viewModels()

    // The host can't call requestPermissions() itself (it's neither Activity nor Fragment), so it asks
    // through MapHostDeps and we drive the real launcher, delivering the outcome back to the host.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        mapHost.onLocationPermissionResult(
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        )
    }

    private val mapDeps = MapHostDeps {
        permissionLauncher.launch(PermissionUtils.LOCATION_PERMISSIONS)
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The map's center/zoom/focus/mode were written into the saved bundle by onSaveInstanceState;
        // the host restores them when it's created below (and the seed avoids an initial map flash).
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

        // Host the map content inside a Compose ModalNavigationDrawer + HomeTopBar + BottomSheetScaffold,
        // replacing the XML DrawerLayout + NavigationDrawerFragment + main.xml chrome, the hosted
        // MaterialToolbar + options menu, and the third-party SlidingUpPanelLayout. The arrivals panel
        // is the scaffold's bottom sheet, rendered per focused stop by ArrivalsSheetHost. The map, the
        // route-mode header, and the survey are all Compose now — no map-related View seam remains.

        // Create the map host eagerly as a viewless controller. HomeScreen composes ObaMap() itself and
        // hands the host the ready map via onMapReady(); the host owns all the map controller logic.
        val host = ObaMapHost.newController(this, mapDeps, savedInstanceState)
        host.setOnFocusChangeListener(this)
        host.setOnProgressBarChangedListener(this)
        host.setRegionCallback(this)
        // First-launch permission result (granted or denied): the VM completes the deferred region check.
        host.setOnLocationPermissionResultListener { viewModel.onLocationPermissionResult() }
        mapHost = host

        val mapSeed = ObaMapHost.resolveInitialCamera(this, savedInstanceState)

        val homeCallbacks = HomeCallbacks(
            onNavItemSelected = ::onHomeNavItemSelected,
            onSearch = ::onSearch,
            onRecentStopsRoutes = ::onRecentStopsRoutes,
            onListSort = ::onListSortSelected,
            onListClear = ::onListClearSelected,
            onMyLocation = ::onMyLocation,
            onZoomIn = ::onZoomIn,
            onZoomOut = ::onZoomOut,
            onToggleBikeshare = ::onToggleBikeshare,
            onWeatherClick = ::onWeatherClick,
            onHelpAction = ::onHelpAction,
            onWhatsNewDismissed = ::onWhatsNewDismissed,
            onRegionChosen = viewModel::onRegionChosen,
            onDismissDialog = viewModel::dismissDialog,
            onSheetSettled = viewModel::onSheetSettled,
            onClearFocus = viewModel::requestClearMapFocus,
            onArrivalsLoaded = ::onArrivalsLoaded,
            onShowRouteOnMap = viewModel::requestShowRouteOnMap,
            onToggleSheet = viewModel::requestToggleSheet,
            onPreferredHeight = viewModel::onPreferredHeight,
            onCancelRouteMode = ::onCancelRouteMode,
            onRouteHeaderHeight = ::onRouteHeaderHeight,
        )

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val routeHeader by mapViewModel.routeHeader.collectAsStateWithLifecycle()
            HomeScreen(
                state = state,
                events = viewModel.events,
                mapRenderState = mapViewModel.renderState,
                mapCallbacks = host.mapCallbacks,
                onMapReady = host,
                mapSeedLat = mapSeed[0],
                mapSeedLon = mapSeed[1],
                mapSeedZoom = mapSeed[2].toFloat(),
                mapSavedInstanceState = savedInstanceState,
                mapComposed = mapComposed,
                routeHeader = routeHeader,
                surveyViewModel = surveyViewModel,
                donationViewModel = donationViewModel,
                listVms = listVms,
                callbacks = homeCallbacks,
            )
        }

        setupNavigationDrawer()

        setupMapState()

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

        // Handle deep link from background FCM notification tap (only on fresh launch, not config change)
        if (savedInstanceState == null) {
            handleFcmNotificationIntent(intent)
        }

        observeViewModelEvents()
        observeMapCommands()
    }

    /** Carry out one-shot effects from the ViewModel (currently the GTFS wide-alert dialog). */
    private fun observeViewModelEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is HomeEvent.ShowWideAlert -> GtfsAlertsHelper.showWideAlertDialog(
                            this@HomeActivity, event.alert.title, event.alert.message, event.alert.url
                        )
                        is HomeEvent.RegionResolved -> {
                            // The map re-zoom is a MapCommand the VM dispatched; here we run only the
                            // non-map side effects: analytics, what's-new/drawer/toast, survey retry.
                            if (event.changed && event.regionName != null) {
                                ObaAnalytics.setRegion(
                                    Application.get().plausibleInstance,
                                    firebaseAnalytics,
                                    event.regionName
                                )
                            }
                            onRegionResolved(event.changed)
                            // A valid region is required to request the survey; retry now it's resolved.
                            if (viewModel.uiState.value.selectedItem == HomeNavItem.NEARBY) {
                                surveyViewModel.maybeRequestSurvey()
                            }
                        }
                        // Sheet / drawer commands are carried out by HomeScreen.
                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * Bridges the view models' host-level [org.onebusaway.android.map.MapCommand]s (focus / route mode /
     * recenter / region re-zoom) to the host. This is the only wiring HomeActivity keeps for those — the
     * per-command logic lives in [ObaMapHost.executeMapCommand], so the activity no longer translates
     * events into map calls.
     */
    private fun observeMapCommands() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mapViewModel.mapCommands.collect { command -> mapHost.executeMapCommand(command) }
            }
        }
    }

    /**
     * If this activity was launched by tapping an FCM notification (background delivery),
     * the data payload is in the intent extras. Extract stop_id and deep-link to ArrivalsListActivity.
     */
    private fun handleFcmNotificationIntent(intent: Intent?) {
        if (intent == null || intent.extras == null) {
            return
        }
        val arrivalJson = intent.getStringExtra("arrival_and_departure")
        val stopId = ReminderUtils.getStopIdFromPayload(arrivalJson)
        if (stopId != null) {
            ReminderUtils.handleArrivalPayload(applicationContext, arrivalJson)
            val arrivalsIntent = ArrivalsListActivity.Builder(this, stopId).intent
            arrivalsIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(arrivalsIntent)
        }
    }

    override fun onStart() {
        super.onStart()
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val isTalkBackEnabled = am.isTouchExplorationEnabled
        ObaAnalytics.setAccessibility(firebaseAnalytics, isTalkBackEnabled)
        mapHost.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapHost.onResume()

        // Re-snapshot preferences + app-global flags so the ViewModel recomputes the chrome/overlay
        // visibility gates (zoom controls, left-hand mode, layers FAB, weather).
        // (The arrivals panel's collapsed state is derived live from the sheet in HomeScreen now;
        // the survey + donation feature modules self-wire their own state/effects/refresh.)
        pushEnvironment()
    }

    override fun onPause() {
        ShowcaseViewUtils.hideShowcaseView()
        mapHost.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapHost.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        mapHost.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapHost.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The host writes the map's center/zoom/focus/mode into our bundle (replacing the free state
        // survival the FragmentManager used to provide); showMap() feeds it back on recreation.
        mapHost.onSaveInstanceState(outState)
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
                startActivity(Intent(this@HomeActivity, TripPlanActivity::class.java))
            HomeNavItem.PAY_FARE -> UIUtils.launchPayMyFareApp(this)
            HomeNavItem.SETTINGS ->
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
            // Hide "Contact Us" when a custom API URL is set (no contact email to use).
            HomeNavItem.HELP -> viewModel.showHelp(TextUtils.isEmpty(Application.get().customApiUrl))
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
        val label = when (item) {
            HomeNavItem.NEARBY -> R.string.analytics_label_button_press_nearby
            HomeNavItem.STARRED_STOPS, HomeNavItem.STARRED_ROUTES ->
                R.string.analytics_label_button_press_star
            HomeNavItem.MY_REMINDERS -> R.string.analytics_label_button_press_reminders
            HomeNavItem.PLAN_TRIP -> R.string.analytics_label_button_press_trip_plan
            HomeNavItem.SETTINGS -> R.string.analytics_label_button_press_settings
            HomeNavItem.HELP -> R.string.analytics_label_button_press_help
            HomeNavItem.SEND_FEEDBACK -> R.string.analytics_label_button_press_feedback
            HomeNavItem.OPEN_SOURCE -> R.string.analytics_label_button_press_open_source
            HomeNavItem.PAY_FARE -> return
        }
        ObaAnalytics.reportUiEvent(
            firebaseAnalytics,
            Application.get().plausibleInstance,
            PlausibleAnalytics.REPORT_MENU_EVENT_URL,
            getString(label),
            null
        )
    }

    private fun showMap() {
        // The host (controller) is created eagerly in onCreate; here we just flip the gate so HomeScreen
        // composes ObaMap() (deferring the map SDK init to the first NEARBY selection). It then stays
        // composed — list tabs draw an opaque destination over it rather than tearing it down — so this
        // is idempotent. The host's lifecycle is forwarded normally; the map composes when the gate flips.
        mapComposed = true
        // Request the map survey on the first NEARBY selection (idempotent + region-gated in the VM).
        surveyViewModel.maybeRequestSurvey()
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
        mapViewModel.dispatchMapCommand(MapCommand.ExitRouteMode)
    }

    /**
     * Runs the global search for [query] (from [HomeTopBar]'s search field) by firing the legacy
     * `ACTION_SEARCH` flow — `SearchActivity` (the app's `default_searchable`) shows the results.
     */
    private fun onSearch(query: String) {
        startActivity(
            Intent(this, SearchActivity::class.java)
                .setAction(Intent.ACTION_SEARCH)
                .putExtra(SearchManager.QUERY, query)
        )
    }

    /** Opens the recent stops/routes screen (the toolbar overflow item). */
    private fun onRecentStopsRoutes() {
        ShowcaseViewUtils.doNotShowTutorial(ShowcaseViewUtils.TUTORIAL_RECENT_STOPS_ROUTES)
        startActivity(Intent(this, MyRecentStopsAndRoutesActivity::class.java))
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

    // --- Help / What's-New dialog actions (passed to HomeScreen as lambdas) ---

    private fun onHelpAction(action: HelpAction) {
        when (action) {
            HelpAction.TUTORIALS -> {
                ShowcaseViewUtils.resetAllTutorials(this)
                NavHelp.goHome(this, true)
            }
            HelpAction.LEGEND -> viewModel.showLegend()
            HelpAction.WHATS_NEW -> viewModel.showWhatsNew()
            HelpAction.AGENCIES -> AgenciesActivity.start(this)
            HelpAction.TWITTER -> {
                var twitterUrl = TWITTER_URL
                if (Application.get().currentRegion != null &&
                    !TextUtils.isEmpty(Application.get().currentRegion.twitterUrl)
                ) {
                    twitterUrl = Application.get().currentRegion.twitterUrl
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
        }
    }

    private fun onWhatsNewDismissed() {
        val showOptOut = Application.getPrefs()
            .getBoolean(ShowcaseViewUtils.TUTORIAL_OPT_OUT_DIALOG, true)
        if (showOptOut) {
            ShowcaseViewUtils.showOptOutDialog(this)
        }
    }

    /**
     * Show the "What's New" message if a new version was just installed
     *
     * @return true if a new version was just installed, false if not
     */
    @Suppress("DEPRECATION")
    private fun autoShowWhatsNew(): Boolean {
        val settings = Application.getPrefs()

        // Get the current app version.
        val pm = packageManager
        val appInfo = try {
            pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            // Do nothing, perhaps we'll get to show it again? Or never.
            return false
        }

        val oldVer = settings.getInt(WHATS_NEW_VER, 0)
        val newVer = appInfo.versionCode

        if (oldVer < newVer && !isFinishing) {
            viewModel.showWhatsNew()
            PreferenceUtils.saveInt(WHATS_NEW_VER, appInfo.versionCode)
            return true
        }
        return false
    }

    /**
     * Called by the map host when a stop obtains focus, or no stop has focus.
     */
    override fun onFocusChanged(
        stop: ObaStop?,
        routes: HashMap<String, ObaRoute>?,
        location: Location?
    ) {
        // Check to see if we're already focused on this same stop - if so, we shouldn't do anything
        val focusedId = viewModel.uiState.value.focusedStop?.id
        if (focusedId != null && stop != null && focusedId.equals(stop.id, ignoreCase = true)) {
            return
        }
        // Ignore focus callbacks that arrive while we're stopped (state already saved) — updating the
        // ViewModel then would be lost on the way down.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return
        }

        if (stop != null) {
            // A stop on the map was just tapped; the arrivals sheet shows itself from focusedStop.
            viewModel.onStopFocused(
                FocusedStop(stop.id, stop.name, stop.stopCode, stop.latitude, stop.longitude)
            )

            ObaAnalytics.reportUiEvent(
                firebaseAnalytics,
                Application.get().plausibleInstance,
                PlausibleAnalytics.REPORT_MAP_EVENT_URL,
                getString(R.string.analytics_label_button_press_map_icon),
                null
            )
        } else {
            // No stop is in focus (e.g., user tapped on the map), so clear the focus; the sheet (and
            // its per-stop arrivals panel) hides + tears down once focusedStop is null.
            viewModel.onStopFocused(null)
        }
    }

    /**
     * Called from the map host when a BikeRentalStation is clicked.
     */
    override fun onFocusChanged(bikeRentalStation: BikeRentalStation?) {
        // Check to see if we're already focused on this same bike rental station
        val bikeId = viewModel.uiState.value.focusedBikeStationId
        if (bikeId != null && bikeRentalStation != null &&
            bikeId.equals(bikeRentalStation.id, ignoreCase = true)
        ) {
            return
        }

        viewModel.onBikeStationFocused(bikeRentalStation?.id)
    }

    override fun onProgressBarChanged(showProgressBar: Boolean) {
        viewModel.onMapLoading(showProgressBar)
    }

    /**
     * Called (from ArrivalsSheetHost's responses collector) when the panel has new arrival info. The VM
     * owns the pending-focus latch + the overlay-expanded decision; when it reports a pending focus we
     * dispatch a [MapCommand.FocusStop] carrying the response's raw io/elements stop + routes (which the
     * VM is decoupled from), and the host recenters + adds the focus marker.
     */
    private fun onArrivalsLoaded(response: ObaArrivalInfoResponse) {
        val stop = response.stop ?: return
        viewModel.onArrivalsLoaded()?.let { overlayExpanded ->
            mapViewModel.dispatchMapCommand(MapCommand.FocusStop(stop, response.routes, overlayExpanded))
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
        if (!mapComposed || viewModel.lastSettledSheet == ArrivalsSheetState.Hidden) {
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
            val loc = Application.getLastKnownLocation(this)
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
    private fun onRegionResolved(currentRegionChanged: Boolean) {
        // Show "What's New" (which might need refreshed Regions API contents)
        val update = autoShowWhatsNew()

        // Rebuild the region-gated nav items if the region changed, or if we just installed a new version
        if (currentRegionChanged || update) {
            refreshDrawerItems()
        }

        // If region changed and was auto-selected, show user what region we're using
        if (currentRegionChanged &&
            Application.getPrefs()
                .getBoolean(getString(R.string.preference_key_auto_select_region), true) &&
            Application.get().currentRegion != null &&
            UIUtils.canManageDialog(this)
        ) {
            Toast.makeText(
                applicationContext,
                getString(R.string.region_region_found, Application.get().currentRegion.name),
                Toast.LENGTH_LONG
            ).show()
        }
        pushEnvironment()
    }

    /**
     * Snapshots the non-reactive environment (preferences + app-global flags) and feeds it to the
     * ViewModel, which recomputes the gated chrome/overlay visibility (zoom controls, left-hand
     * mode, layers FAB, weather chip, donation card). Called whenever those inputs may have changed:
     * onResume, after a nav selection, after a region update, and after toggling the bikeshare layer.
     */
    private fun pushEnvironment() {
        val prefs = Application.getPrefs()
        viewModel.onEnvironmentRefreshed(
            HomeEnvironment(
                bikeshareEnabled = Application.isBikeshareEnabled(),
                bikeshareActive = LayerUtils.isBikeshareLayerVisible(),
                zoomControlsPref = prefs.getBoolean(
                    getString(R.string.preference_key_show_zoom_controls), false
                ),
                leftHandMode = prefs.getBoolean(
                    getString(R.string.preference_key_left_hand_mode), false
                ),
                weatherHidden = WeatherUtils.isWeatherViewHiddenPref(),
            )
        )
    }

    // --- Map-chrome FAB actions (passed to HomeScreen as lambdas) ---

    private fun onMyLocation() {
        val host = mapHost
        // Reset the preference to ask user to enable location
        PreferenceUtils.saveBoolean(
            getString(R.string.preference_key_never_show_location_dialog), false
        )
        PreferenceUtils.setUserDeniedLocationPermissions(false)

        host.setMyLocation(true, true)
        ObaAnalytics.reportUiEvent(
            firebaseAnalytics,
            Application.get().plausibleInstance,
            PlausibleAnalytics.REPORT_MAP_EVENT_URL,
            getString(R.string.analytics_label_button_press_location),
            null
        )
    }

    private fun onZoomIn() {
        mapHost.zoomIn()
    }

    private fun onZoomOut() {
        mapHost.zoomOut()
    }

    private fun onToggleBikeshare() {
        val host = mapHost
        val active = LayerUtils.isBikeshareLayerVisible()
        val layer: LayerInfo = LayerUtils.bikeshareLayerInfo
        if (active) {
            host.onDeactivateLayer(layer)
        } else {
            host.onActivateLayer(layer)
        }
        // Persist the toggled state (mirrors the legacy LayersSpeedDialAdapter), then re-snapshot the
        // environment so the ViewModel reflects the new bikeshare-active tint.
        Application.getPrefs().edit()
            .putBoolean(layer.sharedPreferenceKey, !active).apply()
        pushEnvironment()
    }

    private fun setupNavigationDrawer() {
        refreshDrawerItems()

        // Determine the initial selection: NEARBY if launched to show a route/stop, else the last
        // remembered tab (mirrors NavigationDrawerFragment's saved-position behavior). Read the
        // enum-name pref, falling back to the legacy int position for installs from before P16.
        val prefs = Application.getPrefs()
        var initial = persistedNavItem(
            prefs.getString(STATE_SELECTED_NAV_ITEM, null),
            prefs.getInt(STATE_SELECTED_POSITION, 0)
        )
        val bundle = intent.extras
        if (bundle != null &&
            (bundle.getString(MapParams.ROUTE_ID) != null ||
                bundle.getString(MapParams.STOP_ID) != null)
        ) {
            initial = HomeNavItem.NEARBY
        }
        val item = initial
        // Defer the first content selection until after onCreate (so the Compose content has composed
        // and lazy map/survey gating reads the applied selection).
        window.decorView.post { onHomeNavItemSelected(item) }
    }

    /** Rebuilds the region-gated drawer item list (mirrors NavigationDrawerFragment.populateNavDrawer). */
    private fun refreshDrawerItems() {
        val region = Application.get().currentRegion
        val items = mutableListOf<HomeNavItem>()
        items.add(HomeNavItem.NEARBY)
        items.add(HomeNavItem.STARRED_STOPS)
        items.add(HomeNavItem.STARRED_ROUTES)
        if (ReminderUtils.shouldShowReminders()) {
            items.add(HomeNavItem.MY_REMINDERS)
        }
        if (region != null) {
            if (!TextUtils.isEmpty(region.otpBaseUrl) ||
                !TextUtils.isEmpty(Application.get().customOtpApiUrl)
            ) {
                items.add(HomeNavItem.PLAN_TRIP)
            }
            if (!TextUtils.isEmpty(region.paymentAndroidAppId)) {
                items.add(HomeNavItem.PAY_FARE)
            }
        }
        items.add(HomeNavItem.OPEN_SOURCE)
        items.add(HomeNavItem.SETTINGS)
        items.add(HomeNavItem.HELP)
        items.add(HomeNavItem.SEND_FEEDBACK)
        viewModel.setNavItems(items)
    }

    /** Routes a Compose-drawer selection to the ViewModel selection + the imperative per-item work. */
    private fun onHomeNavItemSelected(item: HomeNavItem) {
        // Capture before the VM update so re-tapping the active in-place tab suppresses redundant work.
        // navSelectionApplied gates the *first* selection (the legacy -1 sentinel) so startup always
        // runs showMap(), even when the restored tab is NEARBY (the VM's default).
        val reselect = navSelectionApplied &&
            !item.launchesActivity && viewModel.uiState.value.selectedItem == item
        if (!item.launchesActivity) {
            viewModel.onNavItemSelected(item)
            // Remember the tab across sessions, keyed by enum name (mirrors the legacy pref).
            PreferenceUtils.saveString(STATE_SELECTED_NAV_ITEM, item.name)
        }
        navSelectionApplied = true
        goToNavDrawerItem(item, reselect)
    }

    /**
     * Sets up the initial map state from the ViewModel's restored focus (process death / rotation,
     * via SavedStateHandle) or from an Intent that deep-links into a specific stop.
     */
    private fun setupMapState() {
        // The restored focus (SavedStateHandle) already drives the arrivals sheet via HomeScreen; we
        // only need to flag the map to recenter + add the marker once arrivals load.
        val restored = viewModel.uiState.value.focusedStop
        if (restored != null) {
            viewModel.markPendingMapFocus()
        } else {
            // Check the intent for a deep link into a specific stop (via makeIntent()).
            val bundle = intent.extras
            if (bundle != null) {
                val stopId = bundle.getString(MapParams.STOP_ID)
                val stopName = bundle.getString(MapParams.STOP_NAME)
                val stopCode = bundle.getString(MapParams.STOP_CODE)
                val lat = bundle.getDouble(MapParams.CENTER_LAT)
                val lon = bundle.getDouble(MapParams.CENTER_LON)

                if (stopId != null && lat != 0.0 && lon != 0.0) {
                    viewModel.onStopFocused(FocusedStop(stopId, stopName, stopCode, lat, lon))
                    viewModel.markPendingMapFocus()
                }
            }
        }
    }

    // Getting a callback from the map fragment to check if we are in a valid region or not. The
    // ViewModel fetches the weather + streams GTFS wide alerts for a valid region (null clears them).
    override fun onValidRegion(isValid: Boolean) {
        viewModel.onRegionValid(if (isValid) Application.get().currentRegion.id else null)
    }

    private fun onWeatherClick() {
        val summary = viewModel.uiState.value.weather?.summary
        if (summary != null) {
            Toast.makeText(applicationContext, summary.trim(), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val TWITTER_URL = "http://mobile.twitter.com/onebusaway"

        private const val WHATS_NEW_VER = "whatsNewVer"

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
