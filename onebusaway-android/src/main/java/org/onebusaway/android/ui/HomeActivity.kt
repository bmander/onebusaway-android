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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.analytics.FirebaseAnalytics
import org.onebusaway.android.BuildConfig
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.donations.DonationsManager
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.io.request.ObaArrivalInfoResponse
import org.onebusaway.android.io.request.survey.SurveyListener
import org.onebusaway.android.io.request.survey.model.StudyResponse
import org.onebusaway.android.io.request.survey.model.SubmitSurveyResponse
import org.onebusaway.android.map.LayerActivationListener
import org.onebusaway.android.map.LayerInfo
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.map.ObaMapFragment
import org.onebusaway.android.region.ObaRegionsTask
import org.onebusaway.android.report.ui.ReportActivity
import org.onebusaway.android.travelbehavior.TravelBehaviorManager
import org.onebusaway.android.ui.home.ArrivalsSheetState
import org.onebusaway.android.ui.home.DefaultWeatherRepository
import org.onebusaway.android.ui.home.DefaultWideAlertsRepository
import org.onebusaway.android.ui.home.FocusedStop
import org.onebusaway.android.ui.home.HelpAction
import org.onebusaway.android.ui.home.HomeEnvironment
import org.onebusaway.android.ui.home.HomeEvent
import org.onebusaway.android.ui.home.HomeListViewModels
import org.onebusaway.android.ui.home.HomeNavItem
import org.onebusaway.android.ui.home.HomeScreen
import org.onebusaway.android.ui.home.HomeViewModel
import org.onebusaway.android.ui.mylists.RemindersRepository
import org.onebusaway.android.ui.mylists.StarredRoutesRepository
import org.onebusaway.android.ui.mylists.StarredStopsRepository
import org.onebusaway.android.ui.mylists.chooseSortOrder
import org.onebusaway.android.ui.mylists.confirmClear
import org.onebusaway.android.ui.mylists.hostListVm
import org.onebusaway.android.ui.survey.SurveyManager
import org.onebusaway.android.ui.survey.utils.SurveyViewUtils
import org.onebusaway.android.ui.weather.RegionCallback
import org.onebusaway.android.ui.weather.WeatherUtils
import org.onebusaway.android.util.LayerUtils
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.PermissionUtils
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.RegionUtils
import org.onebusaway.android.util.ReminderUtils
import org.onebusaway.android.util.ShowcaseViewUtils
import org.onebusaway.android.util.UIUtils
import org.onebusaway.android.widealerts.GtfsAlertsHelper
import org.opentripplanner.routing.bike_rental.BikeRentalStation
import java.util.Date

class HomeActivity : AppCompatActivity(),
    ObaMapFragment.OnFocusChangedListener,
    ObaMapFragment.OnProgressBarChangedListener,
    RegionCallback,
    ObaRegionsTask.Callback {

    private val viewModel: HomeViewModel by viewModels {
        viewModelFactory {
            initializer {
                HomeViewModel(
                    createSavedStateHandle(),
                    DefaultWeatherRepository(),
                    DefaultWideAlertsRepository()
                )
            }
        }
    }

    private var mSurveyView: View? = null

    /** GoogleApiClient being used for Location Services. TODO(PR #1569): migrate to FusedLocation. */
    private var mGoogleApiClient: GoogleApiClient? = null

    // The inflated map content + toolbar are hosted by the Compose HomeScreen
    // (ModalNavigationDrawer + BottomSheetScaffold) via AndroidView; the arrivals sheet content is a
    // composable keyed per focused stop (ArrivalsSheetHost), no longer a hosted fragment View.
    private lateinit var mMapContent: View

    // Last settled arrivals-sheet position, reported by HomeScreen, so the activity can answer
    // synchronous queries (the preview-vs-full flag) and ignore the initial reveal.
    private var mLastSettledSheet = ArrivalsSheetState.Hidden

    /**
     * Currently selected navigation drawer position (so we don't unnecessarily swap fragments
     * if the same item is selected).  Initialized to -1 so the initial callback always
     * instantiates the fragments.
     */
    private var mCurrentNavDrawerPosition = -1

    private var mMapFragment: ObaMapFragment? = null

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

    // True when a focused stop was restored (process death / rotation) or deep-linked but the map
    // fragment hasn't been told yet, so the next arrivals load should recenter + add the focus
    // marker. A fresh map tap already shows the stop, so it doesn't set this.
    private var mPendingMapFocus = false

    private var mInitialStartup = true

    private lateinit var mFirebaseAnalytics: FirebaseAnalytics

    private var surveyManager: SurveyManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this)

        // Host the map content inside a Compose ModalNavigationDrawer + HomeTopBar + BottomSheetScaffold,
        // replacing the XML DrawerLayout + NavigationDrawerFragment + main.xml chrome, the hosted
        // MaterialToolbar + options menu, and the third-party SlidingUpPanelLayout. The arrivals panel
        // is the scaffold's bottom sheet, rendered per focused stop by ArrivalsSheetHost.
        mMapContent = layoutInflater.inflate(R.layout.home_map_content, null)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            HomeScreen(
                state = state,
                events = viewModel.events,
                mapContent = mMapContent,
                listVms = listVms,
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
                onDonationClose = ::onDonationClose,
                onDonationLearnMore = ::onDonationLearnMore,
                onDonationDonate = ::onDonationDonate,
                onHelpAction = ::onHelpAction,
                onWhatsNewDismissed = ::onWhatsNewDismissed,
                onDismissDialog = viewModel::dismissDialog,
                onSheetSettled = ::onSheetSettled,
                onClearFocus = ::onClearFocus,
                onArrivalsLoaded = ::onArrivalsLoaded,
                onShowRouteOnMap = ::onShowRouteOnMap,
                onToggleSheet = viewModel::requestToggleSheet,
                onPreferredHeight = viewModel::onPreferredHeight,
            )
        }

        mInitialStartup = Application.getPrefs().getBoolean(INITIAL_STARTUP, true)

        setupNavigationDrawer()

        setupMapState()

        setupGooglePlayServices()

        pushEnvironment()

        TravelBehaviorManager(this, applicationContext).registerTravelBehaviorParticipant()

        if (!mInitialStartup ||
            PermissionUtils.hasGrantedAtLeastOnePermission(this, PermissionUtils.LOCATION_PERMISSIONS)
        ) {
            // It's not the first startup or if the user has already granted location permissions
            // (Android L and lower), then check the region status. Otherwise, wait for a permission
            // callback from the map fragment before checking the region status.
            checkRegionStatus()
        }

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
        setupSurvey()

        // Carry out one-shot effects from the ViewModel (currently the GTFS wide-alert dialog).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is HomeEvent.ShowWideAlert -> GtfsAlertsHelper.showWideAlertDialog(
                            this@HomeActivity, event.alert.title, event.alert.message, event.alert.url
                        )
                        // Sheet / drawer commands are carried out by HomeScreen.
                        else -> Unit
                    }
                }
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
        // Make sure GoogleApiClient is connected, if available
        val client = mGoogleApiClient
        if (client != null && !client.isConnected) {
            client.connect()
        }
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val isTalkBackEnabled = am.isTouchExplorationEnabled
        ObaAnalytics.setAccessibility(mFirebaseAnalytics, isTalkBackEnabled)
    }

    override fun onResume() {
        super.onResume()

        // Re-snapshot preferences + app-global flags so the ViewModel recomputes the chrome/overlay
        // visibility gates (zoom controls, left-hand mode, layers FAB, weather, donation card).
        // (The arrivals panel's collapsed state is derived live from the sheet in HomeScreen now.)
        pushEnvironment()
    }

    override fun onPause() {
        ShowcaseViewUtils.hideShowcaseView()
        super.onPause()
    }

    override fun onStop() {
        // Tear down GoogleApiClient
        val client = mGoogleApiClient
        if (client != null && client.isConnected) {
            client.disconnect()
        }
        super.onStop()
    }

    private fun goToNavDrawerItem(item: Int) {
        // Selectable list tabs render as Compose overlays (HomeScreen reads selectedItem); only NEARBY
        // still drives the hosted map fragment. The title comes from selectedItem (HomeTopBar), so the
        // list cases just report analytics.
        when (item) {
            NAVDRAWER_ITEM_STARRED_STOPS -> {
                if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_STARRED_STOPS) {
                    mCurrentNavDrawerPosition = item
                    ObaAnalytics.reportUiEvent(
                        mFirebaseAnalytics,
                        Application.get().plausibleInstance,
                        PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                        getString(R.string.analytics_label_button_press_star),
                        null
                    )
                }
            }
            NAVDRAWER_ITEM_STARRED_ROUTES -> {
                if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_STARRED_ROUTES) {
                    mCurrentNavDrawerPosition = item
                    ObaAnalytics.reportUiEvent(
                        mFirebaseAnalytics,
                        Application.get().plausibleInstance,
                        PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                        getString(R.string.analytics_label_button_press_star),
                        null
                    )
                }
            }
            // below values are deprecated; fall through to NAVDRAWER_ITEM_NEARBY
            NAVDRAWER_ITEM_SIGN_IN,
            NAVDRAWER_ITEM_PROFILE,
            NAVDRAWER_ITEM_PINS,
            NAVDRAWER_ITEM_ACTIVITY_FEED,
            NAVDRAWER_ITEM_NEARBY -> {
                if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_NEARBY) {
                    showMapFragment()
                    mCurrentNavDrawerPosition = NAVDRAWER_ITEM_NEARBY
                    ObaAnalytics.reportUiEvent(
                        mFirebaseAnalytics,
                        Application.get().plausibleInstance,
                        PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                        getString(R.string.analytics_label_button_press_nearby),
                        null
                    )
                }
            }
            NAVDRAWER_ITEM_MY_REMINDERS -> {
                if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_MY_REMINDERS) {
                    mCurrentNavDrawerPosition = item
                    ObaAnalytics.reportUiEvent(
                        mFirebaseAnalytics,
                        Application.get().plausibleInstance,
                        PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                        getString(R.string.analytics_label_button_press_reminders),
                        null
                    )
                }
            }
            NAVDRAWER_ITEM_PLAN_TRIP -> {
                val planTrip = Intent(this@HomeActivity, TripPlanActivity::class.java)
                startActivity(planTrip)
                ObaAnalytics.reportUiEvent(
                    mFirebaseAnalytics,
                    Application.get().plausibleInstance,
                    PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                    getString(R.string.analytics_label_button_press_trip_plan),
                    null
                )
            }
            NAVDRAWER_ITEM_PAY_FARE -> {
                UIUtils.launchPayMyFareApp(this)
            }
            NAVDRAWER_ITEM_SETTINGS -> {
                val preferences = Intent(this@HomeActivity, SettingsActivity::class.java)
                startActivity(preferences)
                ObaAnalytics.reportUiEvent(
                    mFirebaseAnalytics,
                    Application.get().plausibleInstance,
                    PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                    getString(R.string.analytics_label_button_press_settings),
                    null
                )
            }
            NAVDRAWER_ITEM_HELP -> {
                // Hide "Contact Us" when a custom API URL is set (no contact email to use).
                viewModel.showHelp(TextUtils.isEmpty(Application.get().customApiUrl))
                ObaAnalytics.reportUiEvent(
                    mFirebaseAnalytics,
                    Application.get().plausibleInstance,
                    PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                    getString(R.string.analytics_label_button_press_help),
                    null
                )
            }
            NAVDRAWER_ITEM_SEND_FEEDBACK -> {
                ObaAnalytics.reportUiEvent(
                    mFirebaseAnalytics,
                    Application.get().plausibleInstance,
                    PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                    getString(R.string.analytics_label_button_press_feedback),
                    null
                )
                goToSendFeedBack()
            }
            NAVDRAWER_ITEM_OPEN_SOURCE -> {
                ObaAnalytics.reportUiEvent(
                    mFirebaseAnalytics,
                    Application.get().plausibleInstance,
                    PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                    getString(R.string.analytics_label_button_press_open_source),
                    null
                )
                val i = Intent(Intent.ACTION_VIEW)
                i.data = Uri.parse(getString(R.string.open_source_github))
                startActivity(i)
            }
        }
        if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_NEARBY) {
            // Hide survey view unless it's on the map (survey visibility isn't ViewModel state yet)
            SurveyViewUtils.hideSurveyView(mSurveyView)
        }
        // Recompute the donation / weather / layers gates for the new selection.
        pushEnvironment()
    }

    private fun showMapFragment() {
        val fm = supportFragmentManager
        // The list destinations are Compose overlays now, so there's nothing to hide — the map is the
        // only fragment and stays added. Create it on the first NEARBY selection.
        var mapFragment = mMapFragment
        if (mapFragment == null) {
            // First check to see if an instance of ObaMapFragment already exists (see #356)
            mapFragment = fm.findFragmentByTag(ObaMapFragment.TAG) as ObaMapFragment?

            if (mapFragment == null) {
                // No existing fragment was found, so create a new one
                Log.d(TAG, "Creating new ObaMapFragment")
                mapFragment = ObaMapFragment.newInstance()
                mapFragment.setOnLocationPermissionResultListener { _ ->
                    if (mInitialStartup) {
                        // Whether or not the user granted permissions, check region status
                        // (they'll be asked to manually pick region if they denied)
                        mInitialStartup = false
                        PreferenceUtils.saveBoolean(INITIAL_STARTUP, false)
                        checkRegionStatus()
                    }
                }
                fm.beginTransaction()
                    .add(R.id.main_fragment_container, mapFragment.asFragment(), ObaMapFragment.TAG)
                    .commit()
            }
            mMapFragment = mapFragment
        }

        // Register listener for map focus callbacks
        mapFragment.setOnFocusChangeListener(this)
        mapFragment.setOnProgressBarChangedListener(this)
        mapFragment.setRegionCallback(this)

        supportFragmentManager.beginTransaction().show(mapFragment.asFragment()).commit()

        // The map-loading bar + arrivals sheet are now derived from state (mapLoading; focusedStop +
        // the NEARBY tab), and the title comes from selectedItem (HomeTopBar), so no imperative work here.
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
            HelpAction.LEGEND -> showLegendDialog()
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
                    mFirebaseAnalytics,
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
     * The legend dialog stays a hosted MaterialAlertDialogBuilder (it inflates legend_dialog and
     * recolors the ETA chips); shown directly rather than via the deprecated showDialog() API.
     */
    private fun showLegendDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.main_help_legend_title)

        val resources = resources
        val inflater = LayoutInflater.from(applicationContext)
        val legendDialogView = inflater.inflate(R.layout.legend_dialog, null)
        val etaTextFontSize = 30f

        // On time view
        var etaAndMin = legendDialogView.findViewById<View>(R.id.eta_view_ontime)
        var d1 = etaAndMin.background as GradientDrawable
        d1.setColor(resources.getColor(R.color.stop_info_ontime))
        etaAndMin.findViewById<View>(R.id.eta_realtime_indicator).visibility = View.VISIBLE
        var etaTextView = etaAndMin.findViewById<TextView>(R.id.eta)
        etaTextView.textSize = etaTextFontSize
        etaTextView.text = "5"

        // Early View
        etaAndMin = legendDialogView.findViewById(R.id.eta_view_early)
        d1 = etaAndMin.background as GradientDrawable
        d1.setColor(resources.getColor(R.color.stop_info_early))
        etaAndMin.findViewById<View>(R.id.eta_realtime_indicator).visibility = View.VISIBLE
        etaTextView = etaAndMin.findViewById(R.id.eta)
        etaTextView.textSize = etaTextFontSize
        etaTextView.text = "5"

        // Delayed View
        etaAndMin = legendDialogView.findViewById(R.id.eta_view_delayed)
        d1 = etaAndMin.background as GradientDrawable
        d1.setColor(resources.getColor(R.color.stop_info_delayed))
        etaAndMin.findViewById<View>(R.id.eta_realtime_indicator).visibility = View.VISIBLE
        etaTextView = etaAndMin.findViewById(R.id.eta)
        etaTextView.textSize = etaTextFontSize
        etaTextView.text = "5"

        // Scheduled View
        etaAndMin = legendDialogView.findViewById(R.id.eta_view_scheduled)
        d1 = etaAndMin.background as GradientDrawable
        d1.setColor(resources.getColor(R.color.stop_info_scheduled_time))
        etaAndMin.findViewById<View>(R.id.eta_realtime_indicator).visibility = View.INVISIBLE
        etaTextView = etaAndMin.findViewById(R.id.eta)
        etaTextView.textSize = etaTextFontSize
        etaTextView.text = "5"

        // Canceled View
        etaAndMin = legendDialogView.findViewById(R.id.eta_view_canceled)
        d1 = etaAndMin.background as GradientDrawable
        d1.setColor(resources.getColor(R.color.stop_info_scheduled_time))
        etaAndMin.findViewById<View>(R.id.eta_realtime_indicator).visibility = View.INVISIBLE
        etaTextView = etaAndMin.findViewById(R.id.eta)
        etaTextView.textSize = etaTextFontSize
        etaTextView.text = "5"
        etaTextView.paintFlags = Paint.STRIKE_THRU_TEXT_FLAG
        val etaMin = etaAndMin.findViewById<TextView>(R.id.eta_min)
        etaMin.paintFlags = Paint.STRIKE_THRU_TEXT_FLAG

        builder.setView(legendDialogView)

        // The neutral button auto-dismisses the dialog (default AlertDialog behavior).
        builder.setNeutralButton(R.string.main_help_close) { _, _ -> }
        builder.show()
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
     * Called by the map fragment when a stop obtains focus, or no stops have focus
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
        // If the fragment manager's state has already been saved, don't change the focus state.
        if (supportFragmentManager.isStateSaved) {
            return
        }

        if (stop != null) {
            // A stop on the map was just tapped; the arrivals sheet shows itself from focusedStop.
            viewModel.onStopFocused(
                FocusedStop(stop.id, stop.name, stop.stopCode, stop.latitude, stop.longitude)
            )

            ObaAnalytics.reportUiEvent(
                mFirebaseAnalytics,
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
     * Called from the map fragment when a BikeRentalStation is clicked.
     */
    override fun onFocusChanged(bikeRentalStation: BikeRentalStation?) {
        Log.d(TAG, "Bike Station Clicked on map")

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

    /** Clears the map focus (back-press from a peeking sheet); the sheet then hides itself. */
    private fun onClearFocus() {
        mMapFragment?.setFocusStop(null, null)
    }

    /**
     * Called (from ArrivalsSheetHost's responses collector) when the panel has new arrival info:
     * recenter the map + add the focus marker on the first load after a restore/deep-link, and fire
     * the arrival tutorials.
     */
    private fun onArrivalsLoaded(response: ObaArrivalInfoResponse) {
        val stop = response.stop ?: return

        // On restore / deep-link the map fragment hasn't been told which stop is focused, so recenter
        // and add the focus marker when the first arrivals load arrives. A fresh map tap already has
        // the stop in view, so mPendingMapFocus is false there and this is skipped.
        if (mPendingMapFocus) {
            mPendingMapFocus = false
            if (mMapFragment != null) {
                mMapFragment?.setMapCenter(
                    stop.location, false, mLastSettledSheet == ArrivalsSheetState.Expanded
                )
            }
            mMapFragment?.setFocusStop(stop, response.routes)
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

        // If we can't see the map or sliding panel, we can't see the arrival info, so return
        val mapFrag = mMapFragment?.asFragment() ?: return
        if (mapFrag.isHidden || !mapFrag.isVisible || mLastSettledSheet == ArrivalsSheetState.Hidden) {
            return
        }

        // The arrival-header tutorials (arrival info / sliding panel / star route) anchored to the
        // legacy header's Views, which the Compose panel no longer exposes, so they've been retired.
        // The general "recent stops/routes" tutorial still applies.
        ShowcaseViewUtils.showTutorial(
            ShowcaseViewUtils.TUTORIAL_RECENT_STOPS_ROUTES, this, null, false
        )
    }

    /**
     * Called by the arrivals panel when the user selects "Show vehicles on map" for a route:
     * collapse the sheet and switch the existing map to route mode. (`onToggleExpand` /
     * `onPreferredHeight` go straight to the ViewModel — see the HomeScreen wiring in onCreate.)
     */
    private fun onShowRouteOnMap(routeId: String) {
        // Collapse the panel so the user can see the map
        viewModel.requestCollapseSheet()

        val bundle = Bundle()
        bundle.putBoolean(MapParams.ZOOM_TO_ROUTE, false)
        bundle.putBoolean(MapParams.ZOOM_INCLUDE_CLOSEST_VEHICLE, true)
        bundle.putString(MapParams.ROUTE_ID, routeId)
        mMapFragment?.setMapMode(MapParams.MODE_ROUTE, bundle)
    }

    /**
     * Redraw navigation drawer. This is necessary because we do not know whether to draw the
     * "Plan A Trip" option until a region is selected.
     */
    private fun redrawNavigationDrawerFragment() {
        refreshDrawerItems()
    }

    private fun goToSendFeedBack() {
        val focusedStop = viewModel.uiState.value.focusedStop
        if (focusedStop != null) {
            ReportActivity.start(
                this, focusedStop.id, focusedStop.name, focusedStop.code,
                focusedStop.lat, focusedStop.lon, mGoogleApiClient
            )
        } else {
            val loc = Application.getLastKnownLocation(this, mGoogleApiClient)
            if (loc != null) {
                ReportActivity.start(this, loc.latitude, loc.longitude, mGoogleApiClient)
            } else {
                ReportActivity.start(this, mGoogleApiClient)
            }
        }
    }

    /**
     * Checks region status, which can potentially including forcing a reload of region
     * info from the server.  Also includes auto-selection of closest region.
     */
    private fun checkRegionStatus() {
        // First check for custom API URL set by user via Preferences, since if that is set we don't
        // need region info from the REST API
        if (!TextUtils.isEmpty(Application.get().customApiUrl)) {
            return
        }

        // Check if region is hard-coded for this build flavor
        if (BuildConfig.USE_FIXED_REGION) {
            val r = RegionUtils.getRegionFromBuildFlavor()
            // Set the hard-coded region
            RegionUtils.saveToProvider(this, listOf(r))
            Application.get().currentRegion = r
            // Disable any region auto-selection in preferences
            PreferenceUtils.saveBoolean(
                getString(R.string.preference_key_auto_select_region), false
            )
            return
        }

        var forceReload = false
        var showProgressDialog = true

        // If we don't have region info selected, or if enough time has passed since last region info
        // update, force contacting the server again
        if (Application.get().currentRegion == null ||
            Date().time - Application.get().lastRegionUpdateDate > REGION_UPDATE_THRESHOLD
        ) {
            forceReload = true
            Log.d(
                TAG,
                "Region info has expired (or does not exist), forcing a reload from the server..."
            )
        }

        if (Application.get().currentRegion != null) {
            // We already have region info locally, so just check current region status quietly
            showProgressDialog = false
        }

        try {
            val appInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
            val settings: SharedPreferences = Application.getPrefs()
            val oldVer = settings.getInt(CHECK_REGION_VER, 0)
            @Suppress("DEPRECATION") val newVer = appInfo.versionCode

            if (oldVer < newVer) {
                forceReload = true
            }
            PreferenceUtils.saveInt(CHECK_REGION_VER, newVer)
        } catch (e: PackageManager.NameNotFoundException) {
            // Do nothing
        }

        // Check region status, possibly forcing a reload from server and checking proximity to region.
        // The map fragment may not be attached yet during the initial onCreate call (its creation is
        // posted), in which case only `this` is registered — matching the legacy null-skip behavior.
        val callbacks = ArrayList<ObaRegionsTask.Callback>()
        (mMapFragment as? ObaRegionsTask.Callback)?.let { callbacks.add(it) }
        callbacks.add(this)
        val task = ObaRegionsTask(this, callbacks, forceReload, showProgressDialog)
        task.execute()
    }

    //
    // Region Task Callback
    //
    override fun onRegionTaskFinished(currentRegionChanged: Boolean) {
        // Show "What's New" (which might need refreshed Regions API contents)
        val update = autoShowWhatsNew()

        // Redraw nav drawer if the region changed, or if we just installed a new version
        if (currentRegionChanged || update) {
            redrawNavigationDrawerFragment()
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
                donationAvailable = Application.getDonationsManager().shouldShowDonationUI()
            )
        )
    }

    // --- Map-chrome FAB actions (passed to HomeScreen as lambdas) ---

    private fun onMyLocation() {
        val mapFragment = mMapFragment ?: return
        // Reset the preference to ask user to enable location
        PreferenceUtils.saveBoolean(
            getString(R.string.preference_key_never_show_location_dialog), false
        )
        PreferenceUtils.setUserDeniedLocationPermissions(false)

        mapFragment.setMyLocation(true, true)
        ObaAnalytics.reportUiEvent(
            mFirebaseAnalytics,
            Application.get().plausibleInstance,
            PlausibleAnalytics.REPORT_MAP_EVENT_URL,
            getString(R.string.analytics_label_button_press_location),
            null
        )
    }

    private fun onZoomIn() {
        mMapFragment?.zoomIn()
    }

    private fun onZoomOut() {
        mMapFragment?.zoomOut()
    }

    private fun onToggleBikeshare() {
        val mapFragment = mMapFragment ?: return
        val active = LayerUtils.isBikeshareLayerVisible()
        val layer: LayerInfo = LayerUtils.bikeshareLayerInfo
        val mapLayers = mapFragment as LayerActivationListener
        if (active) {
            mapLayers.onDeactivateLayer(layer)
        } else {
            mapLayers.onActivateLayer(layer)
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
        // remembered tab (mirrors NavigationDrawerFragment's saved-position behavior).
        var initialPosition = Application.getPrefs()
            .getInt(STATE_SELECTED_POSITION, NAVDRAWER_ITEM_NEARBY)
        val bundle = intent.extras
        if (bundle != null &&
            (bundle.getString(MapParams.ROUTE_ID) != null ||
                bundle.getString(MapParams.STOP_ID) != null)
        ) {
            initialPosition = NAVDRAWER_ITEM_NEARBY
        }
        val position = initialPosition
        // Defer the first content selection until the island is attached (the AndroidView host
        // attaches it during composition, after onCreate), so the fragment commit finds its container.
        mMapContent.post { onHomeNavItemSelected(toHomeNavItem(position)) }
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

    /** Bridges a Compose-drawer selection to the legacy int-based routing + the ViewModel selection. */
    private fun onHomeNavItemSelected(item: HomeNavItem) {
        if (!item.launchesActivity) {
            viewModel.onNavItemSelected(item)
            Application.getPrefs().edit().putInt(STATE_SELECTED_POSITION, toPosition(item)).apply()
        }
        goToNavDrawerItem(toPosition(item))
    }

    private fun toPosition(item: HomeNavItem): Int = when (item) {
        HomeNavItem.STARRED_STOPS -> NAVDRAWER_ITEM_STARRED_STOPS
        HomeNavItem.STARRED_ROUTES -> NAVDRAWER_ITEM_STARRED_ROUTES
        HomeNavItem.MY_REMINDERS -> NAVDRAWER_ITEM_MY_REMINDERS
        HomeNavItem.PLAN_TRIP -> NAVDRAWER_ITEM_PLAN_TRIP
        HomeNavItem.PAY_FARE -> NAVDRAWER_ITEM_PAY_FARE
        HomeNavItem.SETTINGS -> NAVDRAWER_ITEM_SETTINGS
        HomeNavItem.HELP -> NAVDRAWER_ITEM_HELP
        HomeNavItem.SEND_FEEDBACK -> NAVDRAWER_ITEM_SEND_FEEDBACK
        HomeNavItem.OPEN_SOURCE -> NAVDRAWER_ITEM_OPEN_SOURCE
        HomeNavItem.NEARBY -> NAVDRAWER_ITEM_NEARBY
    }

    private fun toHomeNavItem(position: Int): HomeNavItem = when (position) {
        NAVDRAWER_ITEM_STARRED_STOPS -> HomeNavItem.STARRED_STOPS
        NAVDRAWER_ITEM_STARRED_ROUTES -> HomeNavItem.STARRED_ROUTES
        NAVDRAWER_ITEM_MY_REMINDERS -> HomeNavItem.MY_REMINDERS
        else -> HomeNavItem.NEARBY
    }

    private fun setupGooglePlayServices() {
        // Init Google Play Services as early as possible to give it time
        val api = GoogleApiAvailability.getInstance()
        if (api.isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
            val client = LocationUtils.getGoogleApiClientWithCallbacks(this)
            mGoogleApiClient = client
            client.connect()
        }
    }

    /**
     * Reacts to the arrivals sheet settling into a new resting state (reported by HomeScreen), with
     * [peekPx] the current header peek height. Recenters the map on EXPANDED, keeps the map's bottom
     * padding in sync with the peek, and drives the arrivals panel's preview-vs-full flag. The initial
     * reveal from HIDDEN is ignored (it's handled by the focus flow), matching the legacy behavior.
     */
    private fun onSheetSettled(state: ArrivalsSheetState, peekPx: Int) {
        val previous = mLastSettledSheet
        mLastSettledSheet = state
        if (previous == ArrivalsSheetState.Hidden) {
            return
        }
        when (state) {
            ArrivalsSheetState.Expanded -> {
                mMapFragment?.mapView?.setPadding(null, null, null, peekPx)
                val focusedStop = viewModel.uiState.value.focusedStop
                if (focusedStop != null && mMapFragment != null) {
                    val loc = Location("focusedStop").apply {
                        latitude = focusedStop.lat
                        longitude = focusedStop.lon
                    }
                    mMapFragment?.setMapCenter(loc, true, true)
                }
            }
            ArrivalsSheetState.Collapsed -> {
                mMapFragment?.mapView?.setPadding(null, null, null, peekPx)
            }
            ArrivalsSheetState.Hidden -> {
                mMapFragment?.mapView?.setPadding(null, null, null, 0)
            }
        }
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
            mPendingMapFocus = true
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
                    mPendingMapFocus = true
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

    // --- Donation-card actions (passed to HomeScreen as lambdas) ---

    private fun onDonationClose() {
        buildDismissDonationsDialog().show()
    }

    private fun onDonationLearnMore() {
        startActivity(Intent(this, DonationLearnMoreActivity::class.java))
    }

    private fun onDonationDonate() {
        val donationsManager = Application.getDonationsManager()
        donationsManager.dismissDonationRequests()
        startActivity(donationsManager.buildOpenDonationsPageIntent())
    }

    /**
     * Creates an AlertDialog that will give the user options for dismissing the donations UI.
     */
    private fun buildDismissDonationsDialog(): androidx.appcompat.app.AlertDialog {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.donation_dismiss_dialog_title)
            .setMessage(
                getString(R.string.donation_dismiss_dialog_body, getString(R.string.app_name))
            )
            .setNegativeButton(
                R.string.donation_dismiss_dialog_dont_want_to_help_button
            ) { _, _ ->
                Application.getDonationsManager().dismissDonationRequests()
                pushEnvironment()
            }
            .setNeutralButton(
                R.string.donation_dismiss_dialog_remind_me_later_button
            ) { _, _ ->
                Application.getDonationsManager().remindUserLater()
                pushEnvironment()
            }
            .setPositiveButton(R.string.donation_dismiss_dialog_cancel_button) { _, _ -> }
            .setCancelable(true)

        return builder.create()
    }

    private fun initSurveyView() {
        mSurveyView = mMapContent.findViewById(R.id.surveyView)
    }

    private fun setupSurvey() {
        if (Application.get().currentRegion == null ||
            mCurrentNavDrawerPosition != NAVDRAWER_ITEM_NEARBY
        ) {
            return
        }
        initSurveyView()
        initSurveyManager(mSurveyView)
    }

    private fun initSurveyManager(surveyView: View?) {
        val manager = SurveyManager(this, surveyView, false, object : SurveyListener {
            override fun onSurveyResponseReceived(response: StudyResponse?) {
                surveyManager?.onSurveyResponseReceived(response)
            }

            override fun onSurveyResponseFail() {
                surveyManager?.onSurveyResponseFail()
            }

            override fun onSubmitSurveyResponseReceived(response: SubmitSurveyResponse?) {
                surveyManager?.onSubmitSurveyResponseReceived(response)
            }

            override fun onSubmitSurveyFail() {
                surveyManager?.onSubmitSurveyFail()
            }

            override fun onSkipSurvey() {
                surveyManager?.onSkipSurvey()
            }

            override fun onRemindMeLater() {
                surveyManager?.onRemindMeLater()
            }

            override fun onCancelSurvey() {
                surveyManager?.onCancelSurvey()
            }
        })
        surveyManager = manager
        manager.requestSurveyData()
    }

    companion object {
        const val TWITTER_URL = "http://mobile.twitter.com/onebusaway"

        private const val WHATS_NEW_VER = "whatsNewVer"

        private const val CHECK_REGION_VER = "checkRegionVer"

        // The set of possible nav-drawer positions (relocated from the retired
        // NavigationDrawerFragment). The int model is kept because mCurrentNavDrawerPosition and
        // goToNavDrawerItem() are still int-based; the Compose drawer maps its HomeNavItem to these
        // via toPosition()/toHomeNavItem().
        private const val NAVDRAWER_ITEM_NEARBY = 0
        private const val NAVDRAWER_ITEM_STARRED_STOPS = 1
        private const val NAVDRAWER_ITEM_STARRED_ROUTES = 2
        private const val NAVDRAWER_ITEM_MY_REMINDERS = 3
        private const val NAVDRAWER_ITEM_SETTINGS = 4
        private const val NAVDRAWER_ITEM_HELP = 5
        private const val NAVDRAWER_ITEM_SEND_FEEDBACK = 6
        private const val NAVDRAWER_ITEM_PLAN_TRIP = 7
        private const val NAVDRAWER_ITEM_PINS = 8
        private const val NAVDRAWER_ITEM_ACTIVITY_FEED = 9
        private const val NAVDRAWER_ITEM_PROFILE = 10
        private const val NAVDRAWER_ITEM_SIGN_IN = 11
        private const val NAVDRAWER_ITEM_OPEN_SOURCE = 12
        private const val NAVDRAWER_ITEM_PAY_FARE = 13

        // One week, in milliseconds
        private const val REGION_UPDATE_THRESHOLD = (1000 * 60 * 60 * 24 * 7).toLong()

        private const val TAG = "HomeActivity"

        const val BATTERY_OPTIMIZATIONS_PERMISSION_REQUEST = 111

        // Matches NavigationDrawerFragment's remembered-tab pref key.
        private const val STATE_SELECTED_POSITION = "selected_navigation_drawer_position"

        private const val INITIAL_STARTUP = "initialStartup"

        /**
         * Starts the MapActivity with a particular stop focused with the center of
         * the map at a particular point.
         */
        @JvmStatic
        fun start(context: Context, focusId: String?, lat: Double, lon: Double) {
            context.startActivity(makeIntent(context, focusId, lat, lon))
        }

        /**
         * Starts the MapActivity with a particular stop focused with the center of
         * the map at a particular point.
         */
        @JvmStatic
        fun start(context: Context, stop: ObaStop) {
            context.startActivity(makeIntent(context, stop))
        }

        /**
         * Starts the MapActivity in "RouteMode", which shows stops along a route,
         * and does not get new stops when the user pans the map.
         */
        @JvmStatic
        fun start(context: Context, routeId: String) {
            context.startActivity(makeIntent(context, routeId))
        }

        /**
         * Returns an intent that will start the MapActivity with a particular stop
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
         * Returns an intent that will start the MapActivity with a particular stop
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
         * Returns an intent that starts the MapActivity in "RouteMode", which shows
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
