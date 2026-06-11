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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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
import org.onebusaway.android.io.request.weather.ObaWeatherRequest
import org.onebusaway.android.io.request.weather.WeatherRequestListener
import org.onebusaway.android.io.request.weather.WeatherRequestTask
import org.onebusaway.android.io.request.weather.models.ObaWeatherResponse
import org.onebusaway.android.map.LayerActivationListener
import org.onebusaway.android.map.LayerInfo
import org.onebusaway.android.map.MapParams
import org.onebusaway.android.map.ObaMapFragment
import org.onebusaway.android.region.ObaRegionsTask
import org.onebusaway.android.report.ui.ReportActivity
import org.onebusaway.android.travelbehavior.TravelBehaviorManager
import org.onebusaway.android.ui.arrivals.ArrivalsPanelFragment
import org.onebusaway.android.ui.home.HelpAction
import org.onebusaway.android.ui.home.HomeNavItem
import org.onebusaway.android.ui.home.HomeShellHost
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
import org.onebusaway.android.widealerts.GtfsAlertCallBack
import org.onebusaway.android.widealerts.GtfsAlertsHelper
import org.opentripplanner.routing.bike_rental.BikeRentalStation
import java.util.Date

class HomeActivity : AppCompatActivity(),
    ObaMapFragment.OnFocusChangedListener,
    ObaMapFragment.OnProgressBarChangedListener,
    ArrivalsPanelFragment.Listener,
    WeatherRequestListener,
    RegionCallback,
    ObaRegionsTask.Callback,
    HomeShellHost.MapActionListener,
    HomeShellHost.DialogActionListener {

    private var mArrivalsPanelFragment: ArrivalsPanelFragment? = null

    private var mSurveyView: View? = null

    /** GoogleApiClient being used for Location Services. TODO(PR #1569): migrate to FusedLocation. */
    private var mGoogleApiClient: GoogleApiClient? = null

    // Compose shell (P1 drawer + P2 BottomSheetScaffold): the inflated map content + arrivals sheet
    // content are hosted inside mHomeShell, which replaces the DrawerLayout + SlidingUpPanelLayout.
    private lateinit var mMapContent: View

    private lateinit var mSheetContent: View

    private var mHomeShell: HomeShellHost? = null

    // Current arrivals-sheet peek height in px (mirrors the old SlidingUpPanel.getPanelHeight()), used
    // to offset the FABs above the collapsed sheet.
    private var mSheetPeekPx = 0

    // Previous arrivals-sheet resting state, so onSheetState() can ignore the initial reveal.
    private var mLastSheetState = HomeShellHost.Sheet.HIDDEN

    /**
     * Currently selected navigation drawer position (so we don't unnecessarily swap fragments
     * if the same item is selected).  Initialized to -1 so the initial callback always
     * instantiates the fragments.
     */
    private var mCurrentNavDrawerPosition = -1

    private var mMyStarredStopsFragment: MyStarredStopsFragment? = null
    private var mMyStarredRoutesFragment: MyStarredRoutesFragment? = null

    private var mMapFragment: ObaMapFragment? = null

    private var mMyRemindersFragment: MyRemindersFragment? = null

    private var mShowStarredStopsMenu = false

    private var mShowStarredRoutesMenu = false

    /**
     * Stop that has current focus on the map.  We retain a reference to the StopId, since during
     * rapid rotations it's possible that a reference to a ObaStop object in mFocusedStop can still
     * be null, and we don't want to lose the state of which stopId is in focus.
     */
    private var mFocusedStopId: String? = null

    /** Bike rental station ID that has the focus currently. */
    private var mBikeRentalStationId: String? = null

    private var mFocusedStop: ObaStop? = null

    private var mMapProgressBar: ProgressBar? = null

    private var mLastMapProgressBarState = true

    private var mInitialStartup = true

    private lateinit var mFirebaseAnalytics: FirebaseAnalytics

    private var weatherResponse: ObaWeatherResponse? = null

    private var surveyManager: SurveyManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this)

        // Host the legacy content inside a Compose ModalNavigationDrawer + BottomSheetScaffold with a
        // hosted toolbar, replacing the XML DrawerLayout + NavigationDrawerFragment + main.xml chrome
        // and the third-party SlidingUpPanelLayout. The map chrome is the scaffold content; the
        // arrivals panel is the scaffold's bottom sheet.
        mMapContent = layoutInflater.inflate(R.layout.home_map_content, null)
        mSheetContent = layoutInflater.inflate(R.layout.home_arrivals_sheet, null)
        val toolbar = layoutInflater.inflate(R.layout.include_toolbar, null) as Toolbar
        val shell = HomeShellHost(
            this, toolbar, mMapContent, mSheetContent,
            ::onHomeNavItemSelected, ::onSheetState, this, this
        )
        mHomeShell = shell
        setContentView(shell.view)
        setSupportActionBar(toolbar)
        // Drive the drawer from the toolbar's own navigation icon. (The action-bar home button
        // doesn't route reliably here since the toolbar is hosted in a ComposeView and isn't
        // attached when setSupportActionBar runs.)
        toolbar.setNavigationIcon(R.drawable.ic_menu_hamburger)
        toolbar.setNavigationContentDescription(R.string.navigation_drawer_open)
        toolbar.setNavigationOnClickListener { mHomeShell?.openDrawer() }

        mInitialStartup = Application.getPrefs().getBoolean(INITIAL_STARTUP, true)

        setupNavigationDrawer()

        setupSlidingPanel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Collapse the panel when the user presses the back button
                val shell = mHomeShell
                if (shell != null) {
                    // Collapse the sliding panel if it's expanded
                    if (shell.isSheetExpanded()) {
                        shell.collapseSheet()
                        return
                    }
                    // Clear focused stop and close the sliding panel if it's collapsed (peeking)
                    if (!shell.isSheetHidden()) {
                        // Clear the stop focus in map fragment, which will trigger a callback to
                        // close the panel via ObaMapFragment.OnFocusChangedListener in onFocusChanged()
                        mMapFragment?.setFocusStop(null, null)
                        return
                    }
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        setupMapState(savedInstanceState)

        setupMapChrome()

        setupGooglePlayServices()

        UIUtils.setupActionBar(this)

        updateDonationsUIVisibility()

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

        // Check if weather view visibility is changed to hidden
        if (WeatherUtils.isWeatherViewHiddenPref() && mHomeShell != null) {
            mHomeShell?.hideWeather()
        }
        // Make sure the panel has the current sliding-panel state
        if (mArrivalsPanelFragment != null && mHomeShell != null) {
            mArrivalsPanelFragment?.setPanelCollapsed(isSlidingPanelCollapsed)
        }

        // Check if the map zoom controls should be displayed
        if (mCurrentNavDrawerPosition == NAVDRAWER_ITEM_NEARBY) {
            checkDisplayZoomControls()
        } else {
            showZoomControls(false)
        }
        checkLeftHandMode()
        updateLayersFab()

        updateDonationsUIVisibility()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val focusedId = mFocusedStopId
        if (focusedId != null) {
            outState.putString(MapParams.STOP_ID, focusedId)

            val focusedStop = mFocusedStop
            if (focusedStop != null) {
                outState.putString(MapParams.STOP_CODE, focusedStop.stopCode)
                outState.putString(MapParams.STOP_NAME, focusedStop.name)
            }
        }
        val bikeId = mBikeRentalStationId
        if (bikeId != null) {
            outState.putString(MapParams.BIKE_STATION_ID, bikeId)
        }
    }

    private fun goToNavDrawerItem(item: Int) {
        // Update the main content by replacing fragments
        when (item) {
            NAVDRAWER_ITEM_STARRED_STOPS -> {
                if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_STARRED_STOPS) {
                    showStarredStopsFragment()
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
                    showStarredRoutesFragment()
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
                    showMyRemindersFragment()
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
                mHomeShell?.showHelpDialog(TextUtils.isEmpty(Application.get().customApiUrl))
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
        updateDonationsUIVisibility()
        if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_NEARBY) {
            // Hide survey view unless it's on the map
            SurveyViewUtils.hideSurveyView(mSurveyView)
            mHomeShell?.hideWeather()
        } else {
            setWeatherData()
        }
        invalidateOptionsMenu()
    }

    private fun showMapFragment() {
        val fm = supportFragmentManager
        // Hide everything that shouldn't be shown
        hideStarredRoutesFragment()
        hideStarredStopsFragment()
        hideReminderFragment()
        mShowStarredStopsMenu = false
        // Show fragment (we use show instead of replace to keep the map state)
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

        showFloatingActionButtons()
        if (mLastMapProgressBarState) {
            showMapProgressBar()
        }
        if (mFocusedStopId != null && mHomeShell != null) {
            // if we've focused on a stop, then show the panel that was previously hidden
            mHomeShell?.collapseSheet()
        }
        title = resources.getString(R.string.navdrawer_item_nearby)

        checkDisplayZoomControls()
    }

    private fun showStarredStopsFragment() {
        val fm = supportFragmentManager
        // Hide everything that shouldn't be shown
        hideFloatingActionButtons()
        hideMapProgressBar()
        hideMapFragment()
        hideReminderFragment()
        hideStarredRoutesFragment()
        hideSlidingPanel()
        showZoomControls(false)

        // Show fragment (we use show instead of replace to keep the map state)
        mShowStarredStopsMenu = true
        var fragment = mMyStarredStopsFragment
        if (fragment == null) {
            // First check to see if an instance of MyStarredStopsFragment already exists (see #356)
            fragment = fm.findFragmentByTag(MyStarredStopsFragment.TAG) as MyStarredStopsFragment?

            if (fragment == null) {
                // No existing fragment was found, so create a new one
                Log.d(TAG, "Creating new MyStarredStopsFragment")
                fragment = MyStarredStopsFragment()
                fm.beginTransaction()
                    .add(R.id.main_fragment_container, fragment, MyStarredStopsFragment.TAG)
                    .commit()
            }
            mMyStarredStopsFragment = fragment
        }
        fm.beginTransaction().show(fragment).commit()
        title = resources.getString(R.string.navdrawer_item_starred_stops)
    }

    private fun showStarredRoutesFragment() {
        val fm = supportFragmentManager
        // Hide everything that shouldn't be shown
        hideFloatingActionButtons()
        hideMapProgressBar()
        hideMapFragment()
        hideReminderFragment()
        hideSlidingPanel()
        hideStarredStopsFragment()
        showZoomControls(false)

        // Show fragment (we use show instead of replace to keep the map state)
        mShowStarredRoutesMenu = true
        var fragment = mMyStarredRoutesFragment
        if (fragment == null) {
            // First check to see if an instance of MyStarredRoutesFragment already exists
            fragment = fm.findFragmentByTag(MyStarredRoutesFragment.TAG) as MyStarredRoutesFragment?

            if (fragment == null) {
                // No existing fragment was found, so create a new one
                Log.d(TAG, "Creating new MyStarredRoutesFragment")
                fragment = MyStarredRoutesFragment()
                fm.beginTransaction()
                    .add(R.id.main_fragment_container, fragment, MyStarredRoutesFragment.TAG)
                    .commit()
            }
            mMyStarredRoutesFragment = fragment
        }
        fm.beginTransaction().show(fragment).commit()
        title = resources.getString(R.string.navdrawer_item_starred_routes)
    }

    private fun showMyRemindersFragment() {
        val fm = supportFragmentManager
        // Hide everything that shouldn't be shown
        hideFloatingActionButtons()
        hideMapProgressBar()
        hideStarredRoutesFragment()
        hideStarredStopsFragment()
        hideMapFragment()
        hideSlidingPanel()
        mShowStarredStopsMenu = false
        showZoomControls(false)
        // Show fragment (we use show instead of replace to keep the map state)
        var fragment = mMyRemindersFragment
        if (fragment == null) {
            // First check to see if an instance of MyRemindersFragment already exists (see #356)
            fragment = fm.findFragmentByTag(MyRemindersFragment.TAG) as MyRemindersFragment?

            if (fragment == null) {
                // No existing fragment was found, so create a new one
                Log.d(TAG, "Creating new MyRemindersFragment")
                fragment = MyRemindersFragment()
                fm.beginTransaction()
                    .add(R.id.main_fragment_container, fragment, MyRemindersFragment.TAG)
                    .commit()
            }
            mMyRemindersFragment = fragment
        }
        fm.beginTransaction().show(fragment).commit()
        title = resources.getString(R.string.navdrawer_item_my_reminders)
    }

    private fun hideMapFragment() {
        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentByTag(ObaMapFragment.TAG) as ObaMapFragment?
        mMapFragment = mapFragment
        if (mapFragment != null && !mapFragment.asFragment().isHidden) {
            fm.beginTransaction().hide(mapFragment.asFragment()).commit()
        }
    }

    private fun hideStarredStopsFragment() {
        val fm = supportFragmentManager
        val fragment = fm.findFragmentByTag(MyStarredStopsFragment.TAG) as MyStarredStopsFragment?
        mMyStarredStopsFragment = fragment
        if (fragment != null && !fragment.isHidden) {
            fm.beginTransaction().hide(fragment).commit()
        }
    }

    private fun hideStarredRoutesFragment() {
        val fm = supportFragmentManager
        val fragment = fm.findFragmentByTag(MyStarredRoutesFragment.TAG) as MyStarredRoutesFragment?
        mMyStarredRoutesFragment = fragment
        if (fragment != null && !fragment.isHidden) {
            fm.beginTransaction().hide(fragment).commit()
        }
    }

    private fun hideReminderFragment() {
        val fm = supportFragmentManager
        val fragment = fm.findFragmentByTag(MyRemindersFragment.TAG) as MyRemindersFragment?
        mMyRemindersFragment = fragment
        if (fragment != null && !fragment.isHidden) {
            fm.beginTransaction().hide(fragment).commit()
        }
    }

    private fun hideSlidingPanel() {
        mHomeShell?.hideSheet()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_options, menu)

        UIUtils.setupSearch(this, menu)

        // Initialize fragment menu visibility here, so we don't have overlap between fragments
        setupOptionsMenu(menu)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        // Manage fragment menu visibility here, so we don't have overlap between fragments
        setupOptionsMenu(menu)

        return true
    }

    private fun setupOptionsMenu(menu: Menu) {
        menu.setGroupVisible(R.id.main_options_menu_group, true)
        menu.setGroupVisible(R.id.starred_stop_menu_group, mShowStarredStopsMenu)
        menu.setGroupVisible(R.id.starred_route_menu_group, mShowStarredRoutesMenu)
    }

    @Suppress("DEPRECATION")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Log.d(TAG, "onOptionsItemSelected")
        val id = item.itemId
        if (id == android.R.id.home) {
            // The toolbar up indicator opens the Compose navigation drawer.
            mHomeShell?.openDrawer()
            return true
        }
        // Note: there is no handler for R.id.action_search here — it's an action-view menu
        // item (SearchView), which expands inline rather than firing onOptionsItemSelected
        if (id == R.id.recent_stops_routes) {
            ShowcaseViewUtils.doNotShowTutorial(ShowcaseViewUtils.TUTORIAL_RECENT_STOPS_ROUTES)
            val myIntent = Intent(this, MyRecentStopsAndRoutesActivity::class.java)
            startActivity(myIntent)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // --- HomeShellHost.DialogActionListener: the Compose Help / What's-New dialog actions ---

    override fun onHelpAction(action: HelpAction) {
        when (action) {
            HelpAction.TUTORIALS -> {
                ShowcaseViewUtils.resetAllTutorials(this)
                NavHelp.goHome(this, true)
            }
            HelpAction.LEGEND -> showLegendDialog()
            HelpAction.WHATS_NEW -> mHomeShell?.showWhatsNewDialog()
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

    override fun onWhatsNewDismissed() {
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
            mHomeShell?.showWhatsNewDialog()
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
        val focusedId = mFocusedStopId
        if (focusedId != null && stop != null && focusedId.equals(stop.id, ignoreCase = true)) {
            return
        }
        val fm = supportFragmentManager
        // If the fragment's state has already been saved, then don't change the state (return)
        if (fm.isStateSaved) {
            return
        }

        mFocusedStop = stop

        if (stop != null) {
            mBikeRentalStationId = null
            mFocusedStopId = stop.id
            // A stop on the map was just tapped, show it in the sliding panel
            updateArrivalListFragment(stop.id, stop.name)

            ObaAnalytics.reportUiEvent(
                mFirebaseAnalytics,
                Application.get().plausibleInstance,
                PlausibleAnalytics.REPORT_MAP_EVENT_URL,
                getString(R.string.analytics_label_button_press_map_icon),
                null
            )
        } else {
            // No stop is in focus (e.g., user tapped on the map), so hide the panel
            // and clear the currently focused stopId
            mFocusedStopId = null
            mHomeShell?.hideSheet()
            val panel = mArrivalsPanelFragment
            if (panel != null) {
                fm.beginTransaction().remove(panel).commit()
                mArrivalsPanelFragment = null
            }
        }
    }

    /**
     * Called from the map fragment when a BikeRentalStation is clicked.
     */
    override fun onFocusChanged(bikeRentalStation: BikeRentalStation?) {
        Log.d(TAG, "Bike Station Clicked on map")

        // Check to see if we're already focused on this same bike rental station
        val bikeId = mBikeRentalStationId
        if (bikeId != null && bikeRentalStation != null &&
            bikeId.equals(bikeRentalStation.id, ignoreCase = true)
        ) {
            return
        }

        mBikeRentalStationId = bikeRentalStation?.id
    }

    override fun onProgressBarChanged(showProgressBar: Boolean) {
        mLastMapProgressBarState = showProgressBar
        if (showProgressBar) {
            showMapProgressBar()
        } else {
            hideMapProgressBar()
        }
    }

    /**
     * Called by the ArrivalsPanelFragment when we have new updated arrival information
     */
    override fun onArrivalsLoaded(response: ObaArrivalInfoResponse) {
        if (response.stop == null) {
            return
        }

        // If we're missing any local references (e.g., if orientation just changed), store the values
        if (mFocusedStopId == null) {
            mFocusedStopId = response.stop.id
        }
        if (mFocusedStop == null) {
            val focusedStop = response.stop
            mFocusedStop = focusedStop

            // Since mFocusedStop was null, the layout changed, and we should recenter map on stop
            if (mMapFragment != null && mHomeShell != null) {
                mMapFragment?.setMapCenter(
                    focusedStop.location, false, mHomeShell?.isSheetExpanded() == true
                )
            }

            // ...and we should add a focus marker for this stop
            mMapFragment?.setFocusStop(focusedStop, response.routes)
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
        if (mapFrag.isHidden || !mapFrag.isVisible || mHomeShell?.isSheetHidden() != false) {
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
     * Called by the ArrivalsPanelFragment when the user selects "Show vehicles on map" for a
     * route. Collapses the panel and switches the existing map to route mode.
     */
    override fun onShowRouteOnMap(routeId: String) {
        // Collapse the panel so the user can see the map
        mHomeShell?.collapseSheet()

        val bundle = Bundle()
        bundle.putBoolean(MapParams.ZOOM_TO_ROUTE, false)
        bundle.putBoolean(MapParams.ZOOM_INCLUDE_CLOSEST_VEHICLE, true)
        bundle.putString(MapParams.ROUTE_ID, routeId)
        mMapFragment?.setMapMode(MapParams.MODE_ROUTE, bundle)
    }

    /**
     * Called when the user taps the panel header/chevron: toggle between collapsed and anchored.
     */
    override fun onToggleExpand() {
        val shell = mHomeShell ?: return
        if (isSlidingPanelCollapsed) {
            shell.expandSheet()
        } else {
            shell.collapseSheet()
        }
    }

    /**
     * Called by the panel as the preferred-arrival preview changes, so the collapsed peek height
     * matches the legacy header (no-arrivals / one / two, plus a filter-indicator offset).
     */
    override fun onPreferredHeight(previewCount: Int, filtering: Boolean) {
        val heightDimen = when {
            previewCount >= 2 -> R.dimen.arrival_header_height_two_arrivals
            previewCount == 1 -> R.dimen.arrival_header_height_one_arrival
            else -> R.dimen.arrival_header_height_no_arrivals
        }
        var px = resources.getDimensionPixelSize(heightDimen)
        if (filtering) {
            px += resources.getDimensionPixelSize(R.dimen.arrival_header_height_offset_filter_routes)
        }
        mSheetPeekPx = px
        mHomeShell?.setSheetPeekHeightPx(px)
    }

    /**
     * Redraw navigation drawer. This is necessary because we do not know whether to draw the
     * "Plan A Trip" option until a region is selected.
     */
    private fun redrawNavigationDrawerFragment() {
        refreshDrawerItems()
    }

    /**
     * Create a new fragment to show the arrivals list for the given stop.
     */
    private fun updateArrivalListFragment(stopId: String, stopName: String?) {
        val fm = supportFragmentManager

        // The Compose panel loads its own data from the stop id (it shows a brief loading state
        // until the first response), so no stop/route objects need to be pre-populated.
        val panel = ArrivalsPanelFragment.newInstance(stopId, stopName)
        mArrivalsPanelFragment = panel
        panel.setListener(this)
        fm.beginTransaction().replace(R.id.slidingFragment, panel).commit()
        showSlidingPanel()
    }

    private fun showSlidingPanel() {
        val shell = mHomeShell
        if (shell != null && shell.isSheetHidden()) {
            shell.collapseSheet()
        }
    }

    private fun goToSendFeedBack() {
        val focusedStop = mFocusedStop
        if (focusedStop != null) {
            ReportActivity.start(
                this, mFocusedStopId, focusedStop.name, focusedStop.stopCode,
                focusedStop.latitude, focusedStop.longitude, mGoogleApiClient
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
        updateLayersFab()
    }

    /**
     * Initializes the Compose map chrome (my-location FAB, zoom controls, layers FAB).
     */
    private fun setupMapChrome() {
        checkLeftHandMode()
        if (mCurrentNavDrawerPosition == NAVDRAWER_ITEM_NEARBY) {
            showFloatingActionButtons()
            showMapProgressBar()
        } else {
            hideFloatingActionButtons()
            hideMapProgressBar()
        }
        updateLayersFab()
    }

    private fun checkDisplayZoomControls() {
        val displayZoom = Application.getPrefs().getBoolean(
            getString(R.string.preference_key_show_zoom_controls), false
        )
        showZoomControls(displayZoom)
    }

    /**
     * Shows zoom controls if state is true, hides the zoom controls if state is false
     */
    private fun showZoomControls(showZoom: Boolean) {
        mHomeShell?.setZoomVisible(showZoom)
    }

    private fun checkLeftHandMode() {
        val leftHandMode = Application.getPrefs().getBoolean(
            getString(R.string.preference_key_left_hand_mode), false
        )
        mHomeShell?.setLeftHandMode(leftHandMode)
    }

    private fun showFloatingActionButtons() {
        val shell = mHomeShell ?: return
        shell.setFabsVisible(true)
        // This is the NEARBY path (showMapFragment), so the layers FAB shows whenever bikeshare
        // is available. We gate on bikeshare only (not the nav position) because goToNavDrawerItem
        // sets mCurrentNavDrawerPosition *after* calling showMapFragment; updateLayersFab() applies
        // the position gate later (onResume / region updates).
        shell.setLayersVisible(Application.isBikeshareEnabled())
        shell.setBikeshareActive(LayerUtils.isBikeshareLayerVisible())
    }

    private fun hideFloatingActionButtons() {
        mHomeShell?.setFabsVisible(false)
    }

    // --- HomeShellHost.MapActionListener: the Compose map-chrome FAB actions ---

    override fun onMyLocation() {
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

    override fun onZoomIn() {
        mMapFragment?.zoomIn()
    }

    override fun onZoomOut() {
        mMapFragment?.zoomOut()
    }

    override fun onToggleBikeshare() {
        val mapFragment = mMapFragment ?: return
        val active = LayerUtils.isBikeshareLayerVisible()
        val layer: LayerInfo = LayerUtils.bikeshareLayerInfo
        val mapLayers = mapFragment as LayerActivationListener
        if (active) {
            mapLayers.onDeactivateLayer(layer)
        } else {
            mapLayers.onActivateLayer(layer)
        }
        // Persist + reflect the toggled state (mirrors the legacy LayersSpeedDialAdapter).
        Application.getPrefs().edit()
            .putBoolean(layer.sharedPreferenceKey, !active).apply()
        mHomeShell?.setBikeshareActive(!active)
    }

    private fun showMapProgressBar() {
        val bar = mMapProgressBar ?: return
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
        }
    }

    private fun hideMapProgressBar() {
        val bar = mMapProgressBar ?: return
        if (bar.visibility != View.GONE) {
            bar.visibility = View.GONE
        }
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
        val shell = mHomeShell ?: return
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
        shell.setItems(items)
    }

    /** Bridges a Compose-drawer selection to the legacy int-based routing. */
    private fun onHomeNavItemSelected(item: HomeNavItem) {
        if (!item.launchesActivity) {
            mHomeShell?.setSelected(item)
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
     * (Re)displays the Compose layers FAB and syncs its active tint when the activity restarts or
     * region data updates. The FAB shows only for bikeshare-enabled regions on the NEARBY tab.
     */
    private fun updateLayersFab() {
        val shell = mHomeShell ?: return
        shell.setLayersVisible(
            Application.isBikeshareEnabled() && mCurrentNavDrawerPosition == NAVDRAWER_ITEM_NEARBY
        )
        shell.setBikeshareActive(LayerUtils.isBikeshareLayerVisible())
    }

    private fun setupSlidingPanel() {
        // The Compose BottomSheetScaffold (in mHomeShell) starts hidden; seed its peek height with
        // the legacy default (two arrivals) so the first reveal doesn't flash an undersized peek.
        // onPreferredHeight() then keeps it in sync with the actual arrival preview.
        mSheetPeekPx = resources.getDimensionPixelSize(R.dimen.arrival_header_height_two_arrivals)
        mHomeShell?.setSheetPeekHeightPx(mSheetPeekPx)
    }

    /**
     * Reacts to the arrivals sheet settling into a new state, replacing the legacy
     * SlidingUpPanel PanelSlideListener.
     */
    private fun onSheetState(state: HomeShellHost.Sheet) {
        val previous = mLastSheetState
        mLastSheetState = state
        if (previous == HomeShellHost.Sheet.HIDDEN) {
            return
        }
        when (state) {
            HomeShellHost.Sheet.EXPANDED -> {
                mMapFragment?.mapView?.setPadding(null, null, null, mSheetPeekPx)
                val focusedStop = mFocusedStop
                if (focusedStop != null && mMapFragment != null) {
                    mMapFragment?.setMapCenter(focusedStop.location, true, true)
                }
                mArrivalsPanelFragment?.setPanelCollapsed(false)
            }
            HomeShellHost.Sheet.COLLAPSED -> {
                mMapFragment?.mapView?.setPadding(null, null, null, mSheetPeekPx)
                mArrivalsPanelFragment?.setPanelCollapsed(true)
            }
            HomeShellHost.Sheet.HIDDEN -> {
                // We hide the panel when switching fragments via the navdrawer, so we shouldn't do
                // anything here that loses the map/arrivals state (e.g. removing the panel fragment).
                mMapFragment?.mapView?.setPadding(null, null, null, 0)
            }
        }
    }

    /**
     * Sets up the initial map state, based on a previous savedInstanceState for this activity,
     * or an Intent that was passed into this activity
     */
    private fun setupMapState(savedInstanceState: Bundle?) {
        val stopId: String?
        val stopName: String?
        // Check savedInstanceState to see if there is a previous state for this activity
        if (savedInstanceState != null) {
            // We're recreating an instance with a previous state, so show the focused stop in panel
            stopId = savedInstanceState.getString(MapParams.STOP_ID)
            stopName = savedInstanceState.getString(MapParams.STOP_NAME)

            if (stopId != null) {
                mFocusedStopId = stopId
                updateArrivalListFragment(stopId, stopName)
            }
        } else {
            // Check intent passed into Activity
            val bundle = intent.extras
            if (bundle != null) {
                // Did this activity start to focus on a stop?  If so, set focus and show arrival info
                stopId = bundle.getString(MapParams.STOP_ID)
                stopName = bundle.getString(MapParams.STOP_NAME)
                val lat = bundle.getDouble(MapParams.CENTER_LAT)
                val lon = bundle.getDouble(MapParams.CENTER_LON)

                if (stopId != null && lat != 0.0 && lon != 0.0) {
                    mFocusedStopId = stopId
                    updateArrivalListFragment(stopId, stopName)
                }
            }
        }
        mMapProgressBar = mMapContent.findViewById(R.id.progress_horizontal)
    }

    /**
     * Collapsed means the arrivals sheet isn't fully expanded (it's peeking or hidden). This drives
     * the preview-vs-full state of the Compose arrivals panel.
     */
    private val isSlidingPanelCollapsed: Boolean
        get() = mHomeShell?.isSheetCollapsed() ?: true

    // Getting a callback from the map fragment to check if we are in a valid region or not
    override fun onValidRegion(isValid: Boolean) {
        if (isValid) {
            makeWeatherRequest()
            getGtfsAlerts()
        } else {
            mHomeShell?.hideWeather()
            weatherResponse = null
        }
    }

    private fun setWeatherData() {
        val response = weatherResponse
        if (response == null || mCurrentNavDrawerPosition != NAVDRAWER_ITEM_NEARBY ||
            WeatherUtils.isWeatherViewHiddenPref() || mHomeShell == null
        ) {
            return
        }
        val weatherIcon = response.current_forecast.icon
        val weatherTemp = response.current_forecast.temperature
        val icon = weatherIcon ?: ""
        mHomeShell?.showWeather(
            WeatherUtils.getWeatherIconRes(icon),
            WeatherUtils.formatTemperature(weatherTemp),
            WeatherUtils.isFitIcon(icon)
        )
    }

    override fun onWeatherClick() {
        val response = weatherResponse
        if (response?.current_forecast == null) {
            return
        }
        val summary = response.current_forecast.summary
        if (summary != null) {
            Toast.makeText(applicationContext, summary.trim(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun makeWeatherRequest() {
        if (WeatherUtils.isWeatherViewHiddenPref()) return
        // If weather response is null that means we need to call the weather api to get the new data
        // Adding this will avoid doing multiple requests to the weather API when updating the map.
        if (weatherResponse == null) {
            val weatherRequest = ObaWeatherRequest.newRequest(Application.get().currentRegion.id)
            val task = WeatherRequestTask(this)
            task.execute(weatherRequest)
            Log.d(TAG, "Weather requested")
        } else {
            // We have weather data, no need to make a request
            setWeatherData()
        }
    }

    override fun onWeatherResponseReceived(response: ObaWeatherResponse?) {
        if (response != null && response.current_forecast != null) {
            weatherResponse = response
            setWeatherData()
        }
    }

    override fun onWeatherRequestFailed() {
        Log.d(TAG, "Weather Request Fail")
    }

    private fun updateDonationsUIVisibility() {
        val shell = mHomeShell ?: return
        val donationsManager = Application.getDonationsManager()
        shell.setDonationVisible(
            donationsManager.shouldShowDonationUI() &&
                mCurrentNavDrawerPosition == NAVDRAWER_ITEM_NEARBY
        )
    }

    // --- HomeShellHost.MapActionListener: the Compose donation-card actions ---

    override fun onDonationClose() {
        buildDismissDonationsDialog().show()
    }

    override fun onDonationLearnMore() {
        startActivity(Intent(this, DonationLearnMoreActivity::class.java))
    }

    override fun onDonationDonate() {
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
                updateDonationsUIVisibility()
            }
            .setNeutralButton(
                R.string.donation_dismiss_dialog_remind_me_later_button
            ) { _, _ ->
                Application.getDonationsManager().remindUserLater()
                updateDonationsUIVisibility()
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

    private fun getGtfsAlerts() {
        val regionId = Application.get().currentRegion.id.toString()
        Application.getGtfsAlerts().fetchAlerts(regionId, object : GtfsAlertCallBack {
            override fun onAlert(title: String, message: String, url: String?) {
                Handler(Looper.getMainLooper()).post {
                    GtfsAlertsHelper.showWideAlertDialog(this@HomeActivity, title, message, url)
                }
            }
        })
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
