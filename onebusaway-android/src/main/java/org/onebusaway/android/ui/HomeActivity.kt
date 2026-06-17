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
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
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
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.report.ui.ReportActivity
import org.onebusaway.android.report.ui.ReportDestination
import org.onebusaway.android.travelbehavior.TravelBehaviorManager
import org.onebusaway.android.ui.arrivals.ArrivalsIntents
import org.onebusaway.android.ui.nav.NavHelp
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.ui.home.donation.DonationViewModel
import org.onebusaway.android.ui.home.weather.WeatherViewModel
import org.onebusaway.android.ui.home.AccessibilityAnalyticsEffect
import org.onebusaway.android.ui.home.focusedStopFromExtras
import org.onebusaway.android.ui.home.help.HelpAction
import org.onebusaway.android.ui.home.help.HelpViewModel
import org.onebusaway.android.ui.home.HomeCallbacks
import org.onebusaway.android.ui.home.RegionEvent
import org.onebusaway.android.ui.home.ReportTarget
import org.onebusaway.android.ui.home.HomeNavItem
import org.onebusaway.android.ui.home.HomeScreen
import org.onebusaway.android.ui.home.HomeViewModel
import org.onebusaway.android.ui.home.HomeNavHost
import org.onebusaway.android.ui.home.HomeDestinationDeps
import org.onebusaway.android.ui.home.DeepLinkEffect
import org.onebusaway.android.ui.home.SettingsRehomeEffect
import org.onebusaway.android.ui.home.PaymentWarningDialog
import org.onebusaway.android.ui.home.chrome.analyticsLabelRes
import org.onebusaway.android.ui.mylists.MyTabs
import org.onebusaway.android.ui.nav.NavRoutes
import org.onebusaway.android.ui.survey.SurveyViewModel
import org.onebusaway.android.util.ExternalIntents
import org.onebusaway.android.util.PermissionUtils
import org.onebusaway.android.util.ReminderUtils
import org.onebusaway.android.util.ShowcaseViewUtils

@AndroidEntryPoint
class HomeActivity : AppCompatActivity() {

    // Shared with the NavHost destinations (My* / report / trip-details) that read preferences off the
    // host. (HomeActivity's own nav-selection prefs now live in HomeViewModel.)
    @Inject
    lateinit var prefsRepository: PreferencesRepository

    // Builds the per-stop ArrivalsViewModel for the home bottom-sheet host (and, in C-b.3, the
    // arrivals NavHost destination). Assisted because the sheet's stop id is runtime-dynamic.
    @Inject
    lateinit var arrivalsViewModelFactory: ArrivalsViewModel.Factory

    // The current region (Campaign A): the add-region deep link applies custom API URLs through this
    // instead of reaching into Application.get(). (The region-derived reads it once fed have moved to VMs.)
    @Inject
    lateinit var regionRepository: RegionRepository

    // The last-known location (Campaign A, B1). The send-feedback decision now lives in HomeViewModel;
    // this remains because the infrastructure-issue report screen reads it off the host to submit.
    @Inject
    lateinit var locationRepository: LocationRepository

    private val viewModel: HomeViewModel by viewModels()

    // The map view model — the single source of truth for the map. MapFeature (in HomeScreen) renders it
    // and self-wires the callbacks/collectors/effects/lifecycle; it now also owns its own mode + camera
    // persistence via SavedStateHandle, so the activity no longer marshals them through the Bundle.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Host the map content inside a Compose ModalNavigationDrawer + HomeTopBar + BottomSheetScaffold,
        // replacing the XML DrawerLayout + NavigationDrawerFragment + main.xml chrome, the hosted
        // MaterialToolbar + options menu, and the third-party SlidingUpPanelLayout. The arrivals panel
        // is the scaffold's bottom sheet, rendered per focused stop by ArrivalsSheetHost. The map, the
        // route-mode header, and the survey are all Compose now — no map-related View seam remains.

        val homeCallbacks = buildHomeCallbacks()

        // Stage any external "open this screen" intent (set before setContent so [DeepLinkEffect]
        // observes it once the NavHost composes) and run its side effects. MapParams.* focus / route-mode
        // launches stage null here and stay on the map path below. Only on a fresh launch (not a config
        // change), so a rotation doesn't re-fire reminder deletes / URL applies.
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val navController = rememberNavController()
            AccessibilityAnalyticsEffect()
            DeepLinkEffect(navController, viewModel.deepLinkRoute, viewModel::onDeepLinkRouteConsumed)
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
                    arrivalsViewModelFactory = arrivalsViewModelFactory,
                    callbacks = homeCallbacks,
                ),
            )
            PaymentWarningDialog(viewModel.paymentWarning, viewModel::dismissPaymentWarning)
        }

        setupNavigationDrawer()

        setupMapState()

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

        observeRegionEvents()
    }

    /**
     * Bundles the home screen's tap/UI lambdas ([HomeCallbacks]) — a mix of activity-method references
     * and [HomeViewModel] method references — passed down to [HomeScreen] via the HOME destination.
     */
    private fun buildHomeCallbacks(): HomeCallbacks = HomeCallbacks(
        onNavItemSelected = ::onHomeNavItemSelected,
        onSearch = ::onSearch,
        onRecentStopsRoutes = ::onRecentStopsRoutes,
        onListSort = viewModel::requestListSort,
        onListClear = viewModel::requestListClear,
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
    )

    /**
     * A warm re-launch (singleTop) carrying an external screen intent — FCM CLEAR_TOP, the
     * NavigationService reminder PendingIntent, a pinned shortcut. Stage its route; the NavHost's
     * LaunchedEffect navigates. (Cold launches are handled in onCreate.)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Handles an incoming external intent: runs any domain side effects it implies, then stages the
     * NavHost route it should open. Both entry points (cold launch in [onCreate], warm re-launch in
     * [onNewIntent]) funnel through here so the side-effect-then-route sequence stays in one place.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        applyIntentSideEffects(intent)
        viewModel.stageDeepLinkRoute(routeForIntent(intent))
    }

    /**
     * Runs the domain mutations implied by certain incoming intents, kept out of the pure
     * [routeForIntent] mapping so it stays a side-effect-free translator: the `add-region` deep link
     * applies custom API URLs (clearing the region), and the FCM `arrival_and_departure` payload clears
     * the now-fired reminder.
     */
    private fun applyIntentSideEffects(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        if (data?.scheme == "onebusaway" && data.host == "add-region") {
            // Validating and applying the URLs is the region domain's job; we just parse them off the URI.
            regionRepository.applyCustomApiUrls(
                obaUrl = data.getQueryParameter("oba-url"),
                otpUrl = data.getQueryParameter("otp-url"),
            )
            return
        }
        intent.getStringExtra("arrival_and_departure")?.let { arrivalJson ->
            ReminderUtils.handleArrivalPayload(applicationContext, arrivalJson)
        }
    }

    /**
     * Translates an incoming external intent into the NavHost route it should open, or null to leave
     * the home/map path untouched — a pure mapping with no side effects (the domain mutations some
     * intents imply run in [applyIntentSideEffects]). Maps the FCM `arrival_and_departure` payload to
     * the stop's arrivals route, and the explicit-component screen intents that carry a
     * `content://<authority>/<path>/{id}` data URI (read by path segment, since the authority is
     * flavor-specific). MapParams.* focus / route-mode launches have no data URI and return null —
     * they stay map behavior (the map mode/camera seed from the intent extras, via MapViewModel's
     * SavedStateHandle; the focused stop via setupMapState).
     */
    private fun routeForIntent(intent: Intent?): String? {
        if (intent == null) return null
        // In-app / cross-screen launches carry their destination route verbatim (see [navIntent]).
        intent.getStringExtra(EXTRA_NAV_ROUTE)?.let { return it }
        // The exported `onebusaway://add-region` deep link applies custom API URLs as a side effect (see
        // [applyIntentSideEffects]); for routing it stays on the home/map path (the legacy handler went
        // Home), so return null.
        val data = intent.data
        if (data?.scheme == "onebusaway" && data.host == "add-region") return null
        // System search (HomeActivity is the default_searchable target): open the search destination.
        if (intent.action == Intent.ACTION_SEARCH) {
            return NavRoutes.search(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        }
        // The FCM arrival payload clears its fired reminder as a side effect; here it just opens arrivals.
        intent.getStringExtra("arrival_and_departure")?.let { arrivalJson ->
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
     * Subscribes to the [RegionEvent]s the activity handles — currently just [RegionEvent.RegionResolved]
     * (region analytics). (Sheet commands ride a separate [HomeViewModel.sheetCommands] flow consumed by
     * [HomeScreen], so this `when` is exhaustive with no discard arm.)
     */
    private fun observeRegionEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.regionEvents.collect { event ->
                    when (event) {
                        is RegionEvent.RegionResolved -> onRegionResolved(event)
                    }
                }
            }
        }
    }

    /**
     * The host-only effects of a region resolve. The map re-zoom (VM onRegionChanged), nav items (VM
     * refreshNavItems), the region-found snackbar (HomeUiState.regionFoundName), what's-new (HelpFeature),
     * the survey (SurveyFeature), and the chrome environment (the VM's reactive environment collector
     * re-derives bikeshare availability from the region) are all self-wired elsewhere; what remains here
     * is analytics.
     */
    private fun onRegionResolved(event: RegionEvent.RegionResolved) {
        // Report an auto-selected region change to analytics (a manual pick passes a null name, so none).
        if (event.changed && event.regionName != null) {
            ObaAnalytics.setRegion(
                Application.get().plausibleInstance,
                FirebaseAnalytics.getInstance(this),
                event.regionName
            )
        }
    }

    // --- Settings preference-screen host glue (re-homed from the former SettingsActivity) ------------

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

    override fun onPause() {
        ShowcaseViewUtils.hideShowcaseView()
        super.onPause()
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
                viewModel.stageDeepLinkRoute(NavRoutes.TRIP_PLAN)
            HomeNavItem.PAY_FARE ->
                ExternalIntents.payFareOrWarningRegion(this)?.let { viewModel.showPaymentWarning(it) }
            HomeNavItem.SETTINGS ->
                viewModel.stageDeepLinkRoute(NavRoutes.SETTINGS)
            HomeNavItem.HELP -> helpViewModel.showMenu()
            HomeNavItem.SEND_FEEDBACK -> goToSendFeedBack()
            HomeNavItem.OPEN_SOURCE ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.open_source_github))))
        }
        if (!reselect) reportNavAnalytics(item)
        // The survey is a Compose overlay in the map Box; a list tab's opaque destination covers it,
        // so it hides itself off NEARBY with no imperative work here. The donation / weather / layers
        // gates recompute via the VM (selectNav + the reactive environment collector).
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

    /** The route header's cancel button: return to stop mode, preserving the current zoom + center. */
    private fun onCancelRouteMode() {
        mapViewModel.exitRouteMode()
    }

    /**
     * Runs the global search for [query] (from [HomeTopBar]'s search field) by navigating to the
     * search destination (staged through the VM's deep-link route since the navController lives in the
     * NavHost composition, not here).
     */
    private fun onSearch(query: String) {
        viewModel.stageDeepLinkRoute(NavRoutes.search(query))
    }

    /** Opens the recent stops/routes screen (the toolbar overflow item) — the MY_RECENT destination. */
    private fun onRecentStopsRoutes() {
        ShowcaseViewUtils.doNotShowTutorial(this, ShowcaseViewUtils.TUTORIAL_RECENT_STOPS_ROUTES)
        viewModel.stageDeepLinkRoute(NavRoutes.myRecent())
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
                // The VM derives which URL fits the current region; the host just fires the ACTION_VIEW.
                ExternalIntents.goToUrl(this, helpViewModel.twitterUrl())
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
     * focus decision + map dispatch live in [HomeViewModel.onArrivalsLoaded] (it owns the pending-focus
     * latch and reaches the map through the MapInteractionBus), so the host no longer relays between the
     * two view models — it just forwards the loaded stop and triggers the tutorials.
     */
    private fun onArrivalsLoaded(response: ObaArrivalInfoResponse) {
        val stop = response.stop ?: return
        viewModel.onArrivalsLoaded(stop, response.routes)
        // Show arrival info related tutorials
        showArrivalInfoTutorials()
    }

    /**
     * Shows the recent-stops/routes tutorial over the loaded arrival info. The *decision* (the map is up
     * and the sheet is visible) lives in [HomeViewModel.shouldShowArrivalTutorial]; what stays here is the
     * imperative ShowcaseView seam — the already-showing guard and the View-overlay show. (The legacy
     * arrival-header tutorials anchored to the old header's Views, gone with the Compose panel.)
     */
    private fun showArrivalInfoTutorials() {
        if (ShowcaseViewUtils.isShowcaseViewShowing()) return
        if (!viewModel.shouldShowArrivalTutorial()) return
        ShowcaseViewUtils.showTutorial(
            ShowcaseViewUtils.TUTORIAL_RECENT_STOPS_ROUTES, this, null, false
        )
    }

    private fun goToSendFeedBack() {
        // The VM picks the report target (focused stop → last location → nothing); the host just launches.
        when (val target = viewModel.reportTarget()) {
            is ReportTarget.Stop -> target.stop.let {
                ReportActivity.start(this, it.id, it.name, it.code, it.lat, it.lon)
            }
            is ReportTarget.Location -> ReportActivity.start(this, target.lat, target.lon)
            ReportTarget.Generic -> ReportActivity.start(this)
        }
    }

    private fun setupNavigationDrawer() {
        // The nav items themselves are built by the ViewModel (init + on region resolve); here we only
        // determine and apply the initial selection. The VM resolves the deep-link-vs-remembered-tab
        // decision from its persisted prefs; the host just reads whether the intent deep-links to the map.
        val bundle = intent.extras
        val deepLinksToMap = bundle != null &&
            (bundle.getString(MapParams.ROUTE_ID) != null || bundle.getString(MapParams.STOP_ID) != null)
        val item = viewModel.resolveInitialNavItem(deepLinksToMap)
        // Defer the first content selection until after onCreate (so the Compose content has composed
        // and lazy map/survey gating reads the applied selection).
        window.decorView.post { onHomeNavItemSelected(item) }
    }

    /** Routes a Compose-drawer selection to the ViewModel selection + the imperative per-item work. */
    private fun onHomeNavItemSelected(item: HomeNavItem) {
        // The VM owns the fresh-vs-re-tap decision and the selection persistence (SavedStateHandle +
        // cross-session pref); a re-tap of the active in-place tab returns false to suppress the redundant
        // showMap()/analytics.
        val fresh = viewModel.selectNav(item)
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
