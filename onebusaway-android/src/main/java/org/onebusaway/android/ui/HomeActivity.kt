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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.onebusaway.android.R
import org.onebusaway.android.io.request.ObaArrivalInfoResponse
import org.onebusaway.android.location.LocationRepository
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.report.ui.ReportActivity
import org.onebusaway.android.report.ui.ReportDestination
import org.onebusaway.android.travelbehavior.TravelBehaviorManager
import org.onebusaway.android.ui.nav.NavHelp
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.ui.home.donation.DonationViewModel
import org.onebusaway.android.ui.home.weather.WeatherViewModel
import org.onebusaway.android.ui.home.AccessibilityAnalyticsEffect
import org.onebusaway.android.ui.home.HomeAnalyticsEffect
import org.onebusaway.android.ui.home.help.HelpAction
import org.onebusaway.android.ui.home.help.HelpViewModel
import org.onebusaway.android.ui.home.HomeCallbacks
import org.onebusaway.android.ui.home.ReportTarget
import org.onebusaway.android.ui.home.FocusedStop
import org.onebusaway.android.ui.home.HomeNavItem
import org.onebusaway.android.ui.home.HomeScreen
import org.onebusaway.android.ui.home.HomeViewModel
import org.onebusaway.android.ui.home.HomeNavHost
import org.onebusaway.android.ui.home.HomeDestinationDeps
import org.onebusaway.android.ui.home.DeepLinkEffect
import org.onebusaway.android.ui.home.SettingsRehomeEffect
import org.onebusaway.android.ui.home.PaymentWarningDialog
import org.onebusaway.android.ui.home.chrome.analyticsLabelRes
import org.onebusaway.android.ui.nav.IntentRouteMapper
import org.onebusaway.android.ui.nav.NavRoutes
import org.onebusaway.android.ui.survey.SurveyViewModel
import org.onebusaway.android.ui.tutorial.ArrivalTutorial
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
            HomeAnalyticsEffect(viewModel.analyticsEvents)
            DeepLinkEffect(navController, viewModel.deepLinkRoute, viewModel::onDeepLinkRouteConsumed)
            // The welcome tutorial (now the Compose green welcome + map-stop spotlight sequence) is
            // started by HomeScreen off the same showWelcomeTutorial latch — no host effect needed.
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
        // Stage the welcome sequence on the VM latch; HomeScreen starts the Compose welcome + map-stop
        // spotlight when it fires (the what's-new opt-out's "yes" reaches this via HelpFeature).
        onShowWelcomeTutorial = viewModel::requestWelcomeTutorial,
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
     * In-app "show route on map": surface the map (select NEARBY, dismissing any list overlay) and enter
     * route mode. The single-Activity replacement for the old `HomeActivity.start(routeId)` intent
     * round-trip — every in-app caller reaches this directly instead of building a MapParams intent.
     */
    fun showRouteOnMap(routeId: String) {
        onHomeNavItemSelected(HomeNavItem.NEARBY)
        mapViewModel.showRoute(routeId)
    }

    /**
     * In-app "show stop on map": surface the map and focus [stopId], recentering on it and peeking its
     * arrivals once they load. Replaces `HomeActivity.start(stopId, lat, lon)`. Unlike the cold-launch
     * [setupMapState]/applyInitialFocus (which only adopts a stop when none is focused), an explicit
     * "show on map" focuses this stop even if another is already up.
     */
    fun focusStopOnMap(stopId: String, lat: Double, lon: Double) {
        onHomeNavItemSelected(HomeNavItem.NEARBY)
        viewModel.onStopFocused(FocusedStop(stopId, null, null, lat, lon))
        viewModel.markPendingMapFocus()
    }

    /**
     * Navigate the NavHost to an in-app [route] (a [NavRoutes] string) — the single-Activity replacement
     * for `startActivity(launcher intent)` from in-app code that has no `navController` in scope (the
     * arrivals sheet, the My* helpers, the Compose overlays). Goes through the same staged-route path
     * the old intent round-trip resolved to, so the back-stack behavior is unchanged.
     */
    fun navigateTo(route: String) = viewModel.stageDeepLinkRoute(route)

    /**
     * Handles an incoming external intent: runs any domain side effects it implies, then stages the
     * NavHost route it should open. Both entry points (cold launch in [onCreate], warm re-launch in
     * [onNewIntent]) funnel through here so the side-effect-then-route sequence stays in one place.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        applyIntentSideEffects(intent)
        viewModel.stageDeepLinkRoute(IntentRouteMapper.routeForIntent(intent))
        // "Show tutorials again" (help menu / settings) re-launches us with this extra to (re-)show the
        // welcome tutorial. HomeActivity is singleTop, so when it's already on top that re-launch arrives
        // via onNewIntent, not a fresh onCreate — staging on the VM latch here in the shared funnel
        // (rather than only in onCreate) makes both the cold first-run and the warm re-launch honor it.
        // HomeScreen starts the Compose welcome + map-stop spotlight sequence off the latch.
        if (intent?.extras?.getBoolean(ShowcaseViewUtils.TUTORIAL_WELCOME) == true) {
            viewModel.requestWelcomeTutorial()
        }
    }

    /**
     * Runs the domain mutations implied by certain incoming intents, kept out of [IntentRouteMapper]'s
     * pure route mapping so that stays a side-effect-free translator: the `add-region` deep link applies
     * custom API URLs (clearing the region), and the FCM payload clears the now-fired reminder.
     */
    private fun applyIntentSideEffects(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        if (data?.scheme == IntentRouteMapper.ADD_REGION_SCHEME &&
            data.host == IntentRouteMapper.ADD_REGION_HOST
        ) {
            // Validating and applying the URLs is the region domain's job; we just parse them off the URI.
            regionRepository.applyCustomApiUrls(
                obaUrl = data.getQueryParameter("oba-url"),
                otpUrl = data.getQueryParameter("otp-url"),
            )
            return
        }
        intent.getStringExtra(ReminderUtils.ARRIVAL_PAYLOAD_KEY)?.let { arrivalJson ->
            ReminderUtils.handleArrivalPayload(applicationContext, arrivalJson)
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
        // Per-item menu analytics (PAY_FARE has no label, so reports none); reported via the VM's
        // analytics event so the imperative ObaAnalytics call lives in HomeAnalyticsEffect, not here.
        if (!reselect) item.analyticsLabelRes?.let { viewModel.reportMenuAnalytics(it) }
        // The survey is a Compose overlay in the map Box; a list tab's opaque destination covers it,
        // so it hides itself off NEARBY with no imperative work here. The donation / weather / layers
        // gates recompute via the VM (selectNav + the reactive environment collector).
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
        // The user found the overflow on their own — don't later spotlight it in the onboarding tutorial.
        prefsRepository.setBoolean(ArrivalTutorial.KEY_MORE_MENU, true)
        viewModel.stageDeepLinkRoute(NavRoutes.myRecent())
    }

    // --- Help-menu actions that are Activity operations (the dialog-opening ones live in HelpFeature) ---

    private fun onHelpAction(action: HelpAction) {
        when (action) {
            HelpAction.TUTORIALS -> {
                ShowcaseViewUtils.resetAllTutorials(this)
                NavHelp.goHome(this, true)
            }
            HelpAction.AGENCIES -> viewModel.stageDeepLinkRoute(NavRoutes.AGENCIES)
            HelpAction.TWITTER -> {
                // The VM derives which URL fits the current region; the host just fires the ACTION_VIEW.
                // Analytics rides the VM's event so the ObaAnalytics call lives in HomeAnalyticsEffect.
                ExternalIntents.goToUrl(this, helpViewModel.twitterUrl())
                viewModel.reportMenuAnalytics(R.string.analytics_label_twitter)
            }
            HelpAction.CONTACT_US -> goToSendFeedBack()
            // LEGEND / WHATS_NEW open dialogs — handled by HelpFeature against HelpViewModel.
            HelpAction.LEGEND, HelpAction.WHATS_NEW -> Unit
        }
    }

    /**
     * Called (from ArrivalsSheetHost's responses collector) when the panel has new arrival info. The
     * focus decision + map dispatch live in [HomeViewModel.onArrivalsLoaded] (it owns the pending-focus
     * latch and reaches the map through the MapInteractionBus), so the host just forwards the loaded
     * stop. The arrivals-panel onboarding spotlights (ETA / panel / star / overflow) are now driven in
     * Compose by [org.onebusaway.android.ui.tutorial.ArrivalTutorial] off the same responses collector.
     */
    private fun onArrivalsLoaded(response: ObaArrivalInfoResponse) {
        val stop = response.stop ?: return
        viewModel.onArrivalsLoaded(stop, response.routes)
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
     * Sets up the initial map focus. A restored focus (SavedStateHandle: process death / rotation)
     * already drives the arrivals sheet via HomeScreen; otherwise adopt a stop deep-linked through the
     * intent (makeIntent). The VM decides which applies and marks the focus pending so the map recenters
     * and adds the marker once arrivals load.
     */
    private fun setupMapState() = viewModel.applyInitialFocus(FocusedStop.fromIntent(intent))

    companion object {
        /**
         * Extra on the [navIntent] SETTINGS intent requesting the settings destination show the
         * "check your region" dialog on first composition. Set by the report flow's region-validate
         * dialog when the user opts to change their region.
         */
        const val EXTRA_SHOW_CHECK_REGION_DIALOG = ".checkRegionDialog"

        /**
         * An intent that opens HomeActivity and navigates its NavHost to [route]. The generic in-app
         * entry point: a launcher facade builds this explicit intent carrying the route via
         * [NavRoutes.EXTRA_NAV_ROUTE]; [IntentRouteMapper] navigates there. Lets former screen Activities
         * be thin facades with no per-screen intent contract. (External contracts — shortcuts/FCM — use
         * [IntentRouteMapper]'s data-URI branches.)
         */
        @JvmStatic
        fun navIntent(context: Context, route: String): Intent =
            Intent(context, HomeActivity::class.java).putExtra(NavRoutes.EXTRA_NAV_ROUTE, route)
    }
}

/**
 * The [FocusedStop] a launch intent deep-links into (makeIntent's STOP_ID + CENTER_LAT/LON), or null
 * when it carries no usable stop — an id plus a real (non-zero) location. A plain launch carries
 * neither, so focus stays null. Parsing lives here with the intent contract, off HomeModels.
 */
private fun FocusedStop.Companion.fromIntent(intent: Intent): FocusedStop? {
    val extras = intent.extras ?: return null
    val id = extras.getString(MapParams.STOP_ID) ?: return null
    val lat = extras.getDouble(MapParams.CENTER_LAT)
    val lon = extras.getDouble(MapParams.CENTER_LON)
    if (lat == 0.0 || lon == 0.0) return null
    return FocusedStop(id, extras.getString(MapParams.STOP_NAME), extras.getString(MapParams.STOP_CODE), lat, lon)
}
