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

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.io.elements.ObaRegion
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
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.report.ui.ReportActivity
import org.onebusaway.android.report.ui.ReportDestination
import org.onebusaway.android.travelbehavior.TravelBehaviorManager
import org.onebusaway.android.ui.arrivals.ArrivalsIntents
import org.onebusaway.android.ui.nav.NavHelp
import org.onebusaway.android.ui.settings.SettingsSupport
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.ui.home.ArrivalsSheetState
import org.onebusaway.android.ui.home.donation.DonationViewModel
import org.onebusaway.android.ui.home.weather.WeatherViewModel
import org.onebusaway.android.ui.home.AccessibilityAnalyticsEffect
import org.onebusaway.android.ui.home.focusedStopFromExtras
import org.onebusaway.android.ui.home.help.HelpAction
import org.onebusaway.android.ui.home.help.HelpViewModel
import org.onebusaway.android.ui.home.HomeEnvironment
import org.onebusaway.android.ui.home.HomeCallbacks
import org.onebusaway.android.ui.home.HomeEvent
import org.onebusaway.android.ui.home.HomeListViewModels
import org.onebusaway.android.ui.home.HomeNavItem
import org.onebusaway.android.ui.home.initialNavItem
import org.onebusaway.android.ui.home.HomeScreen
import org.onebusaway.android.ui.home.HomeViewModel
import org.onebusaway.android.ui.home.HomeNavHost
import org.onebusaway.android.ui.home.HomeDestinationDeps
import org.onebusaway.android.ui.home.DeepLinkEffect
import org.onebusaway.android.ui.home.SettingsRehomeEffect
import org.onebusaway.android.ui.home.PaymentWarningDialog
import org.onebusaway.android.ui.home.chrome.analyticsLabelRes
import org.onebusaway.android.ui.mylists.MyTabs
import org.onebusaway.android.ui.mylists.RemindersRepository
import org.onebusaway.android.ui.mylists.StarredRoutesRepository
import org.onebusaway.android.ui.mylists.StarredStopsRepository
import org.onebusaway.android.ui.nav.NavRoutes
import org.onebusaway.android.ui.mylists.chooseSortOrder
import org.onebusaway.android.ui.mylists.confirmClear
import org.onebusaway.android.ui.mylists.hostListVm
import org.onebusaway.android.ui.survey.SurveyViewModel
import org.onebusaway.android.util.ExternalIntents
import org.onebusaway.android.util.LayerUtils
import org.onebusaway.android.util.PermissionUtils
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.ReminderUtils
import org.onebusaway.android.util.ShowcaseViewUtils

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

    // The region whose fare-payment warning to show (former imperative payment_warning_dialog). Set by
    // the PAY_FARE menu action when a warning is needed; the Compose dialog in setContent renders it.
    private val paymentWarningRegion = MutableStateFlow<ObaRegion?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Host the map content inside a Compose ModalNavigationDrawer + HomeTopBar + BottomSheetScaffold,
        // replacing the XML DrawerLayout + NavigationDrawerFragment + main.xml chrome, the hosted
        // MaterialToolbar + options menu, and the third-party SlidingUpPanelLayout. The arrivals panel
        // is the scaffold's bottom sheet, rendered per focused stop by ArrivalsSheetHost. The map, the
        // route-mode header, and the survey are all Compose now — no map-related View seam remains.

        // The map is driven by mapViewModel; the seed (saved state → intent → last-saved view) just
        // avoids an initial flash before the loaders/region center it.
        val mapSeed = readMapSeed(savedInstanceState)

        val homeCallbacks = buildHomeCallbacks()

        // Stage any external "open this screen" intent (set before setContent so [DeepLinkEffect]
        // observes it once the NavHost composes). MapParams.* focus / route-mode launches return
        // null here and stay on the map path below. Only on a fresh launch (not a config change).
        if (savedInstanceState == null) {
            pendingDeepLinkRoute.value = routeForIntent(intent)
        }

        setContent {
            val navController = rememberNavController()
            AccessibilityAnalyticsEffect()
            DeepLinkEffect(navController, pendingDeepLinkRoute)
            SettingsRehomeEffect(navController)
            HomeNavHost(
                navController = navController,
                home = HomeDestinationDeps(
                    homeViewModel = viewModel,
                    mapViewModel = mapViewModel,
                    surveyViewModel = surveyViewModel,
                    donationViewModel = donationViewModel,
                    weatherViewModel = weatherViewModel,
                    helpViewModel = helpViewModel,
                    listVms = listVms,
                    arrivalsViewModelFactory = arrivalsViewModelFactory,
                    callbacks = homeCallbacks,
                    mapSeed = mapSeed,
                    savedInstanceState = savedInstanceState,
                ),
            )
            PaymentWarningDialog(paymentWarningRegion)
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
     * Bundles the home screen's tap/UI lambdas ([HomeCallbacks]) — a mix of activity-method references
     * and [HomeViewModel] method references — passed down to [HomeScreen] via the HOME destination.
     */
    private fun buildHomeCallbacks(): HomeCallbacks = HomeCallbacks(
        onNavItemSelected = ::onHomeNavItemSelected,
        onSearch = ::onSearch,
        onRecentStopsRoutes = ::onRecentStopsRoutes,
        onListSort = ::onListSortSelected,
        onListClear = ::onListClearSelected,
        onBikeshareToggled = ::pushEnvironment,
        onHelpAction = ::onHelpAction,
        onShowWelcomeTutorial = {
            ShowcaseViewUtils.showTutorial(
                ShowcaseViewUtils.TUTORIAL_WELCOME, this@HomeActivity, null, false
            )
        },
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
     * Subscribes to the region [HomeEvent]s the activity (as opposed to [HomeScreen]) handles:
     * [HomeEvent.RegionResolved] and [HomeEvent.RegionToggleChanged]. The sheet/drawer commands on the
     * same multicast flow are consumed by HomeScreen, so they fall through the `else` here.
     */
    private fun observeRegionResolved() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is HomeEvent.RegionResolved -> onRegionResolved(event)
                        HomeEvent.RegionToggleChanged -> onRegionToggleChanged()
                        // Sheet commands are consumed by HomeScreen.
                        else -> Unit
                    }
                }
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
        // Report an auto-selected region change to analytics (a manual pick passes a null name, so none).
        if (event.changed && event.regionName != null) {
            ObaAnalytics.setRegion(
                Application.get().plausibleInstance,
                FirebaseAnalytics.getInstance(this),
                event.regionName
            )
        }
        pushEnvironment()
    }

    // --- Settings preference-screen host glue (re-homed from the former SettingsActivity) ------------

    /**
     * An experimental-regions toggle changed the region (the [HomeEvent.RegionToggleChanged] effect;
     * ported from SettingsActivity.onRegionTaskFinished): reset the OTP API version. The "found region"
     * announcement is the shared region-found snackbar, and the new region propagates reactively, so no
     * re-home is needed.
     */
    private fun onRegionToggleChanged() {
        Application.get().setUseOldOtpApiUrlVersion(false)
    }

    /** Re-resolves the region after a backup restore (called from SettingsScreen's restore launcher),
     *  in case the restored data implies a different region — raising the picker if it's ambiguous. */
    fun refreshRegionsAfterRestore() {
        viewModel.refreshRegions()
    }

    /** The advanced-settings "refresh regions" (experimental-regions toggle) action, forwarded to the
     *  VM. Public so the (extracted) SETTINGS_ADVANCED destination can reach it off the host. */
    fun onExperimentalRegionsToggled() {
        viewModel.onExperimentalRegionsToggled()
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
            HomeNavItem.PAY_FARE ->
                ExternalIntents.payFareOrWarningRegion(this)?.let { paymentWarningRegion.value = it }
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
            FirebaseAnalytics.getInstance(this),
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
            HelpAction.AGENCIES -> startActivity(navIntent(this, NavRoutes.AGENCIES))
            HelpAction.TWITTER -> {
                var twitterUrl = TWITTER_URL
                val region = regionRepository.region.value
                if (region != null && !TextUtils.isEmpty(region.twitterUrl)) {
                    twitterUrl = region.twitterUrl
                }
                ExternalIntents.goToUrl(this, twitterUrl)
                ObaAnalytics.reportUiEvent(
                    FirebaseAnalytics.getInstance(this),
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

        /**
         * Extra on the [navIntent] SETTINGS intent requesting the settings destination show the
         * "check your region" dialog on first composition. Set by the report flow's region-validate
         * dialog when the user opts to change their region.
         */
        const val EXTRA_SHOW_CHECK_REGION_DIALOG = ".checkRegionDialog"

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
