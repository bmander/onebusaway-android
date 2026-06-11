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
package org.onebusaway.android.ui;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.analytics.FirebaseAnalytics;

import org.onebusaway.android.BuildConfig;
import org.onebusaway.android.R;
import org.onebusaway.android.io.PlausibleAnalytics;
import org.onebusaway.android.widealerts.GtfsAlertCallBack;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.donations.DonationsManager;
import org.onebusaway.android.io.ObaAnalytics;
import org.onebusaway.android.io.elements.ObaRegion;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaStop;
import org.onebusaway.android.io.request.ObaArrivalInfoResponse;
import org.onebusaway.android.io.request.survey.SurveyListener;
import org.onebusaway.android.io.request.survey.model.StudyResponse;
import org.onebusaway.android.io.request.survey.model.SubmitSurveyResponse;
import org.onebusaway.android.io.request.weather.ObaWeatherRequest;
import org.onebusaway.android.io.request.weather.models.ObaWeatherResponse;
import org.onebusaway.android.io.request.weather.WeatherRequestListener;
import org.onebusaway.android.io.request.weather.WeatherRequestTask;
import org.onebusaway.android.map.MapModeController;
import org.onebusaway.android.map.MapParams;
import org.onebusaway.android.map.ObaMapFragment;
import org.onebusaway.android.map.LayerActivationListener;
import org.onebusaway.android.map.LayerInfo;
import org.onebusaway.android.region.ObaRegionsTask;
import org.onebusaway.android.report.ui.ReportActivity;
import org.onebusaway.android.travelbehavior.TravelBehaviorManager;
import org.onebusaway.android.travelbehavior.utils.TravelBehaviorUtils;
import org.onebusaway.android.ui.arrivals.ArrivalsPanelFragment;
import org.onebusaway.android.ui.survey.SurveyManager;
import org.onebusaway.android.ui.survey.utils.SurveyViewUtils;
import org.onebusaway.android.ui.weather.RegionCallback;
import org.onebusaway.android.ui.weather.WeatherUtils;
import org.onebusaway.android.util.LocationUtils;
import org.onebusaway.android.util.PermissionUtils;
import org.onebusaway.android.util.LayerUtils;
import org.onebusaway.android.util.PreferenceUtils;
import org.onebusaway.android.util.RegionUtils;
import org.onebusaway.android.util.ReminderUtils;
import org.onebusaway.android.util.ShowcaseViewUtils;
import org.onebusaway.android.util.UIUtils;
import org.onebusaway.android.widealerts.GtfsAlertsHelper;
import org.opentripplanner.routing.bike_rental.BikeRentalStation;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import org.onebusaway.android.ui.home.HomeNavItem;
import org.onebusaway.android.ui.home.HomeShellHost;

import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_ACTIVITY_FEED;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_HELP;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_MY_REMINDERS;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_NEARBY;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_OPEN_SOURCE;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_PAY_FARE;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_PINS;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_PLAN_TRIP;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_PROFILE;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_SEND_FEEDBACK;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_SETTINGS;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_SIGN_IN;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_STARRED_ROUTES;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NAVDRAWER_ITEM_STARRED_STOPS;
import static org.onebusaway.android.ui.NavigationDrawerFragment.NavigationDrawerCallbacks;
import static org.onebusaway.android.util.PermissionUtils.LOCATION_PERMISSIONS;

public class HomeActivity extends AppCompatActivity
        implements ObaMapFragment.OnFocusChangedListener,
        ObaMapFragment.OnProgressBarChangedListener,
        ArrivalsPanelFragment.Listener, NavigationDrawerCallbacks, WeatherRequestListener , RegionCallback,
        ObaRegionsTask.Callback, HomeShellHost.MapActionListener {


    public static final String TWITTER_URL = "http://mobile.twitter.com/onebusaway";

    private static final String WHATS_NEW_VER = "whatsNewVer";

    private static final String CHECK_REGION_VER = "checkRegionVer";

    private static final int HELP_DIALOG = 1;

    private static final int WHATSNEW_DIALOG = 2;

    private static final int LEGEND_DIALOG = 3;

    //One week, in milliseconds
    private static final long REGION_UPDATE_THRESHOLD = 1000 * 60 * 60 * 24 * 7;

    private static final String TAG = "HomeActivity";

    WeakReference<AppCompatActivity> mActivityWeakRef;

    ArrivalsPanelFragment mArrivalsPanelFragment;


    View mSurveyView;

    View mDonationView;

    /**
     * GoogleApiClient being used for Location Services
     */
    protected GoogleApiClient mGoogleApiClient;

    public static final int BATTERY_OPTIMIZATIONS_PERMISSION_REQUEST = 111;

    /**
     * Fragment managing the behaviors, interactions and presentation of the navigation drawer.
     */
    private NavigationDrawerFragment mNavigationDrawerFragment;

    // Compose shell (P1 drawer + P2 BottomSheetScaffold): the inflated map content + arrivals sheet
    // content are hosted inside mHomeShell, which replaces the DrawerLayout + SlidingUpPanelLayout.
    private View mMapContent;

    private View mSheetContent;

    private HomeShellHost mHomeShell;

    // Current arrivals-sheet peek height in px (mirrors the old SlidingUpPanel.getPanelHeight()), used
    // to offset the FABs above the collapsed sheet.
    private int mSheetPeekPx;

    // Previous arrivals-sheet resting state, so onSheetState() can ignore the initial reveal.
    private HomeShellHost.Sheet mLastSheetState = HomeShellHost.Sheet.HIDDEN;

    // Matches NavigationDrawerFragment's remembered-tab pref key.
    private static final String STATE_SELECTED_POSITION = "selected_navigation_drawer_position";

    /**
     * Currently selected navigation drawer position (so we don't unnecessarily swap fragments
     * if the same item is selected).  Initialized to -1 so the initial callback from
     * NavigationDrawerFragment always instantiates the fragments
     */
    private int mCurrentNavDrawerPosition = -1;

    /**
     * Fragments that can be selected as main content via the NavigationDrawer
     */
    MyStarredStopsFragment mMyStarredStopsFragment;
    MyStarredRoutesFragment mMyStarredRoutesFragment;

    ObaMapFragment mMapFragment;

    MyRemindersFragment mMyRemindersFragment;

    /**
     * Control which menu options are shown per fragment menu groups
     */
    private boolean mShowStarredStopsMenu = false;

    private boolean mShowStarredRoutesMenu = false;

    /**
     * Stop that has current focus on the map.  We retain a reference to the StopId,
     * since during rapid rotations its possible that a reference to a ObaStop object in
     * mFocusedStop can still be null, and we don't want to lose the state of which stopId is in
     * focus.  We also need access to the focused stop properties, hence why we also have
     * mFocusedStop
     */
    String mFocusedStopId = null;

    /**
     * Bike rental station ID that has the focus currently.
     */
    String mBikeRentalStationId = null;

    ObaStop mFocusedStop = null;

    ProgressBar mMapProgressBar = null;

    boolean mLastMapProgressBarState = true;

    private static final String INITIAL_STARTUP = "initialStartup";

    boolean mInitialStartup = true;

    private FirebaseAnalytics mFirebaseAnalytics;

    //private ActivityResultLauncher<String> travelBehaviorPermissionsLauncher;

    private ObaWeatherResponse weatherResponse;

    private SurveyManager surveyManager;
    /**
     * Starts the MapActivity with a particular stop focused with the center of
     * the map at a particular point.
     *
     * @param context The context of the activity.
     * @param focusId The stop to focus.
     * @param lat     The latitude of the map center.
     * @param lon     The longitude of the map center.
     */
    public static void start(Context context,
                             String focusId,
                             double lat,
                             double lon) {
        context.startActivity(makeIntent(context, focusId, lat, lon));
    }

    /**
     * Starts the MapActivity with a particular stop focused with the center of
     * the map at a particular point.
     *
     * @param context The context of the activity.
     * @param stop    The stop to focus on.
     */
    public static void start(Context context, ObaStop stop) {
        context.startActivity(makeIntent(context, stop));
    }

    /**
     * Starts the MapActivity in "RouteMode", which shows stops along a route,
     * and does not get new stops when the user pans the map.
     *
     * @param context The context of the activity.
     * @param routeId The route to show.
     */
    public static void start(Context context, String routeId) {
        context.startActivity(makeIntent(context, routeId));
    }

    /**
     * Returns an intent that will start the MapActivity with a particular stop
     * focused with the center of the map at a particular point.
     *
     * @param context The context of the activity.
     * @param focusId The stop to focus.
     * @param lat     The latitude of the map center.
     * @param lon     The longitude of the map center.
     */
    public static Intent makeIntent(Context context,
                                    String focusId,
                                    double lat,
                                    double lon) {
        Intent myIntent = new Intent(context, HomeActivity.class);
        myIntent.putExtra(MapParams.STOP_ID, focusId);
        myIntent.putExtra(MapParams.CENTER_LAT, lat);
        myIntent.putExtra(MapParams.CENTER_LON, lon);
        return myIntent;
    }

    /**
     * Returns an intent that will start the MapActivity with a particular stop
     * focused with the center of the map at a particular point.
     *
     * @param context The context of the activity.
     * @param stop    The stop to focus on.
     */
    public static Intent makeIntent(Context context, ObaStop stop) {
        Intent myIntent = new Intent(context, HomeActivity.class);
        myIntent.putExtra(MapParams.STOP_ID, stop.getId());
        myIntent.putExtra(MapParams.STOP_NAME, stop.getName());
        myIntent.putExtra(MapParams.STOP_CODE, stop.getStopCode());
        myIntent.putExtra(MapParams.CENTER_LAT, stop.getLatitude());
        myIntent.putExtra(MapParams.CENTER_LON, stop.getLongitude());
        return myIntent;
    }

    /**
     * Returns an intent that starts the MapActivity in "RouteMode", which shows
     * stops along a route, and does not get new stops when the user pans the
     * map.
     *
     * @param context The context of the activity.
     * @param routeId The route to show.
     */
    public static Intent makeIntent(Context context, String routeId) {
        Intent myIntent = new Intent(context, HomeActivity.class);
        myIntent.putExtra(MapParams.MODE, MapParams.MODE_ROUTE);
        myIntent.putExtra(MapParams.ZOOM_TO_ROUTE, true);
        myIntent.putExtra(MapParams.ROUTE_ID, routeId);
        return myIntent;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        // Host the legacy content inside a Compose ModalNavigationDrawer + BottomSheetScaffold with a
        // hosted toolbar, replacing the XML DrawerLayout + NavigationDrawerFragment + main.xml chrome
        // and the third-party SlidingUpPanelLayout. The map chrome is the scaffold content; the
        // arrivals panel is the scaffold's bottom sheet.
        mMapContent = getLayoutInflater().inflate(R.layout.home_map_content, null);
        mSheetContent = getLayoutInflater().inflate(R.layout.home_arrivals_sheet, null);
        Toolbar toolbar = (Toolbar) getLayoutInflater().inflate(R.layout.include_toolbar, null);
        mHomeShell = new HomeShellHost(this, toolbar, mMapContent, mSheetContent,
                this::onHomeNavItemSelected, this::onSheetState, this);
        setContentView(mHomeShell.getView());
        setSupportActionBar(toolbar);
        // Drive the drawer from the toolbar's own navigation icon. (The action-bar home button
        // doesn't route reliably here since the toolbar is hosted in a ComposeView and isn't
        // attached when setSupportActionBar runs.)
        toolbar.setNavigationIcon(R.drawable.ic_menu_hamburger);
        toolbar.setNavigationContentDescription(R.string.navigation_drawer_open);
        toolbar.setNavigationOnClickListener(v -> mHomeShell.openDrawer());

        mActivityWeakRef = new WeakReference<>(this);

        mInitialStartup = Application.getPrefs().getBoolean(INITIAL_STARTUP, true);

        setupNavigationDrawer();

        setupSlidingPanel();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Collapse the panel when the user presses the back button
                if (mHomeShell != null) {
                    // Collapse the sliding panel if it's expanded
                    if (mHomeShell.isSheetExpanded()) {
                        mHomeShell.collapseSheet();
                        return;
                    }
                    // Clear focused stop and close the sliding panel if it's collapsed (peeking)
                    if (!mHomeShell.isSheetHidden()) {
                        // Clear the stop focus in map fragment, which will trigger a callback to
                        // close the panel via ObaMapFragment.OnFocusChangedListener in onFocusChanged()
                        mMapFragment.setFocusStop(null, null);
                        return;
                    }
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        setupMapState(savedInstanceState);

        setupMapChrome();

        setupGooglePlayServices();

        //setupPermissions(this);

        UIUtils.setupActionBar(this);

        setupDonationView(this);

        // To enable checkBatteryOptimizations, also uncomment the
        // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission in AndroidManifest.xml
        // See https://github.com/OneBusAway/onebusaway-android/pull/988#discussion_r299950506
//        checkBatteryOptimizations();

        new TravelBehaviorManager(this, getApplicationContext()).
                registerTravelBehaviorParticipant();

        if (!mInitialStartup || PermissionUtils.hasGrantedAtLeastOnePermission(this, LOCATION_PERMISSIONS)) {
            // It's not the first startup or if the user has already granted location permissions (Android L and lower), then check the region status
            // Otherwise, wait for a permission callback from the map fragment before checking the region status
            checkRegionStatus();
        }

        // Check to see if we should show the welcome tutorial
        Bundle b = getIntent().getExtras();
        if (b != null) {
            if (b.getBoolean(ShowcaseViewUtils.TUTORIAL_WELCOME)) {
                // Show the welcome tutorial
                ShowcaseViewUtils.showTutorial(ShowcaseViewUtils.TUTORIAL_WELCOME, this, null, false);
            }
        }

        // Handle deep link from background FCM notification tap (only on fresh launch, not config change)
        if (savedInstanceState == null) {
            handleFcmNotificationIntent(getIntent());
        }
        setupSurvey();
    }

    /**
     * If this activity was launched by tapping an FCM notification (background delivery),
     * the data payload is in the intent extras. Extract stop_id and deep-link to ArrivalsListActivity.
     */
    private void handleFcmNotificationIntent(Intent intent) {
        if (intent == null || intent.getExtras() == null) {
            return;
        }
        String arrivalJson = intent.getStringExtra("arrival_and_departure");
        String stopId = ReminderUtils.getStopIdFromPayload(arrivalJson);
        if (stopId != null) {
            ReminderUtils.handleArrivalPayload(getApplicationContext(), arrivalJson);
            Intent arrivalsIntent = new ArrivalsListActivity.Builder(this, stopId).getIntent();
            arrivalsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(arrivalsIntent);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // Make sure GoogleApiClient is connected, if available
        if (mGoogleApiClient != null && !mGoogleApiClient.isConnected()) {
            mGoogleApiClient.connect();
        }
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        Boolean isTalkBackEnabled = am.isTouchExplorationEnabled();
        ObaAnalytics.setAccessibility(mFirebaseAnalytics, isTalkBackEnabled);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Check if weather view visibility is changed to hidden
        if (WeatherUtils.isWeatherViewHiddenPref() && mHomeShell != null) {
            mHomeShell.hideWeather();
        }
        // Make sure the panel has the current sliding-panel state
        if (mArrivalsPanelFragment != null && mHomeShell != null) {
            mArrivalsPanelFragment.setPanelCollapsed(isSlidingPanelCollapsed());
        }

        // Check if the map zoom controls should be displayed
        if (mCurrentNavDrawerPosition == NAVDRAWER_ITEM_NEARBY) {
            checkDisplayZoomControls();
        } else {
            showZoomControls(false);
        }
        checkLeftHandMode();
        updateLayersFab();

        updateDonationsUIVisibility();
    }

    @Override
    protected void onPause() {
        ShowcaseViewUtils.hideShowcaseView();
        super.onPause();
    }

    @Override
    public void onStop() {
        // Tear down GoogleApiClient
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mFocusedStopId != null) {
            outState.putString(MapParams.STOP_ID, mFocusedStopId);

            if (mFocusedStop != null) {
                outState.putString(MapParams.STOP_CODE, mFocusedStop.getStopCode());
                outState.putString(MapParams.STOP_NAME, mFocusedStop.getName());
            }
        }
        if (mBikeRentalStationId != null) {
            outState.putString(MapParams.BIKE_STATION_ID, mBikeRentalStationId);
        }
    }

    @Override
    public void onNavigationDrawerItemSelected(int position) {
        goToNavDrawerItem(position);
    }

    private void goToNavDrawerItem(int item) {
        // Update the main content by replacing fragments
        switch (item) {
            case NAVDRAWER_ITEM_STARRED_STOPS:
                if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_STARRED_STOPS) {
                    showStarredStopsFragment();
                    mCurrentNavDrawerPosition = item;
                    ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                            Application.get().getPlausibleInstance(),
                            PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                            getString(R.string.analytics_label_button_press_star),
                            null);
                }
                break;
            case NAVDRAWER_ITEM_STARRED_ROUTES:
                if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_STARRED_ROUTES) {
                    showStarredRoutesFragment();
                    mCurrentNavDrawerPosition = item;
                    ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                            Application.get().getPlausibleInstance(),
                            PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                            getString(R.string.analytics_label_button_press_star),
                            null);
                }
                break;
            // below values are deprecated; fall through to NAVDRAWER_ITEM_NEARBY
            case NAVDRAWER_ITEM_SIGN_IN:
            case NAVDRAWER_ITEM_PROFILE:
            case NAVDRAWER_ITEM_PINS:
            case NAVDRAWER_ITEM_ACTIVITY_FEED:
            case NAVDRAWER_ITEM_NEARBY:
                if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_NEARBY) {
                    showMapFragment();
                    mCurrentNavDrawerPosition = NAVDRAWER_ITEM_NEARBY;
                    ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                            Application.get().getPlausibleInstance(),
                            PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                            getString(R.string.analytics_label_button_press_nearby),
                            null);
                }
                break;
            case NAVDRAWER_ITEM_MY_REMINDERS:
                if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_MY_REMINDERS) {
                    showMyRemindersFragment();
                    mCurrentNavDrawerPosition = item;
                    ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                            Application.get().getPlausibleInstance(),
                            PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                            getString(R.string.analytics_label_button_press_reminders),
                            null);
                }
                break;
            case NAVDRAWER_ITEM_PLAN_TRIP:
                Intent planTrip = new Intent(HomeActivity.this, TripPlanActivity.class);
                startActivity(planTrip);
                ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                        Application.get().getPlausibleInstance(),
                        PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                        getString(R.string.analytics_label_button_press_trip_plan),
                        null);
                break;
            case NAVDRAWER_ITEM_PAY_FARE:
                UIUtils.launchPayMyFareApp(this);
                break;
            case NAVDRAWER_ITEM_SETTINGS:
                Intent preferences = new Intent(HomeActivity.this, SettingsActivity.class);
                startActivity(preferences);
                ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                        Application.get().getPlausibleInstance(),
                        PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                        getString(R.string.analytics_label_button_press_settings),
                        null);
                break;
            case NAVDRAWER_ITEM_HELP:
                showDialog(HELP_DIALOG);
                ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                        Application.get().getPlausibleInstance(),
                        PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                        getString(R.string.analytics_label_button_press_help),
                        null);
                break;
            case NAVDRAWER_ITEM_SEND_FEEDBACK:
                ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                        Application.get().getPlausibleInstance(),
                        PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                        getString(R.string.analytics_label_button_press_feedback),
                        null);
                goToSendFeedBack();
                break;
            case NAVDRAWER_ITEM_OPEN_SOURCE:
                ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                        Application.get().getPlausibleInstance(),
                        PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                        getString(R.string.analytics_label_button_press_open_source),
                        null);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(getString(R.string.open_source_github)));
                startActivity(i);
                break;
        }
        updateDonationsUIVisibility();
        if (mCurrentNavDrawerPosition != NAVDRAWER_ITEM_NEARBY) {
            // Hide survey view unless it's on the map
            SurveyViewUtils.hideSurveyView(mSurveyView);
            if (mHomeShell != null) {
                mHomeShell.hideWeather();
            }
        }else{
            setWeatherData();
        }
        invalidateOptionsMenu();
    }

    private void handleNearbySelection() {
    }

    private void showMapFragment() {
        FragmentManager fm = getSupportFragmentManager();
        /**
         * Hide everything that shouldn't be shown
         */
        hideStarredRoutesFragment();
        hideStarredStopsFragment();
        hideReminderFragment();
        mShowStarredStopsMenu = false;
        /**
         * Show fragment (we use show instead of replace to keep the map state)
         */
        if (mMapFragment == null) {
            // First check to see if an instance of ObaMapFragment already exists (see #356)
            mMapFragment = (ObaMapFragment) fm.findFragmentByTag(ObaMapFragment.TAG);

            if (mMapFragment == null) {
                // No existing fragment was found, so create a new one
                Log.d(TAG, "Creating new ObaMapFragment");
                mMapFragment = ObaMapFragment.newInstance();
                mMapFragment.setOnLocationPermissionResultListener(result -> {
                            if (mInitialStartup) {
                                // Whether or not the user granted permissions, check region status
                                // (they'll be asked to manually pick region if they denied)
                                mInitialStartup = false;
                                PreferenceUtils.saveBoolean(INITIAL_STARTUP, false);
                                checkRegionStatus();
                            }
                        });
                fm.beginTransaction()
                        .add(R.id.main_fragment_container, mMapFragment.asFragment(), ObaMapFragment.TAG)
                        .commit();
            }
        }

        // Register listener for map focus callbacks
        mMapFragment.setOnFocusChangeListener(this);
        mMapFragment.setOnProgressBarChangedListener(this);
        mMapFragment.setRegionCallback(this);

        getSupportFragmentManager().beginTransaction().show(mMapFragment.asFragment()).commit();

        showFloatingActionButtons();
        if (mLastMapProgressBarState) {
            showMapProgressBar();
        }        if (mFocusedStopId != null && mHomeShell != null) {
            // if we've focused on a stop, then show the panel that was previously hidden
            mHomeShell.collapseSheet();
        }
        setTitle(getResources().getString(R.string.navdrawer_item_nearby));

        checkDisplayZoomControls();
    }

    private void showStarredStopsFragment() {
        FragmentManager fm = getSupportFragmentManager();
        /**
         * Hide everything that shouldn't be shown
         */
        hideFloatingActionButtons();
        hideMapProgressBar();
        hideMapFragment();
        hideReminderFragment();
        hideStarredRoutesFragment();
        hideSlidingPanel();        showZoomControls(false);

        /**
         * Show fragment (we use show instead of replace to keep the map state)
         */
        mShowStarredStopsMenu = true;
        if (mMyStarredStopsFragment == null) {
            // First check to see if an instance of MyStarredStopsFragment already exists (see #356)
            mMyStarredStopsFragment = (MyStarredStopsFragment) fm
                    .findFragmentByTag(MyStarredStopsFragment.TAG);

            if (mMyStarredStopsFragment == null) {
                // No existing fragment was found, so create a new one
                Log.d(TAG, "Creating new MyStarredStopsFragment");
                mMyStarredStopsFragment = new MyStarredStopsFragment();
                fm.beginTransaction().add(R.id.main_fragment_container, mMyStarredStopsFragment,
                        MyStarredStopsFragment.TAG).commit();
            }
        }
        fm.beginTransaction().show(mMyStarredStopsFragment).commit();
        setTitle(getResources().getString(R.string.navdrawer_item_starred_stops));
    }

    private void showStarredRoutesFragment() {
        FragmentManager fm = getSupportFragmentManager();
        /**
         * Hide everything that shouldn't be shown
         */
        hideFloatingActionButtons();
        hideMapProgressBar();
        hideMapFragment();
        hideReminderFragment();
        hideSlidingPanel();
        hideStarredStopsFragment();        showZoomControls(false);

        /**
         * Show fragment (we use show instead of replace to keep the map state)
         */
        mShowStarredRoutesMenu = true;
        if (mMyStarredRoutesFragment == null) {
            // First check to see if an instance of MyStarredRoutesFragment already exists
            mMyStarredRoutesFragment = (MyStarredRoutesFragment) fm
                    .findFragmentByTag(MyStarredRoutesFragment.TAG);

            if (mMyStarredRoutesFragment == null) {
                // No existing fragment was found, so create a new one
                Log.d(TAG, "Creating new MyStarredRoutesFragment");
                mMyStarredRoutesFragment = new MyStarredRoutesFragment();
                fm.beginTransaction().add(R.id.main_fragment_container, mMyStarredRoutesFragment,
                        MyStarredRoutesFragment.TAG).commit();
            }
        }
        fm.beginTransaction().show(mMyStarredRoutesFragment).commit();
        setTitle(getResources().getString(R.string.navdrawer_item_starred_routes));
    }

    private void showMyRemindersFragment() {
        FragmentManager fm = getSupportFragmentManager();
        /**
         * Hide everything that shouldn't be shown
         */
        hideFloatingActionButtons();
        hideMapProgressBar();
        hideStarredRoutesFragment();
        hideStarredStopsFragment();
        hideMapFragment();
        hideSlidingPanel();        mShowStarredStopsMenu = false;
        showZoomControls(false);
        /**
         * Show fragment (we use show instead of replace to keep the map state)
         */
        if (mMyRemindersFragment == null) {
            // First check to see if an instance of MyRemindersFragment already exists (see #356)
            mMyRemindersFragment = (MyRemindersFragment) fm
                    .findFragmentByTag(MyRemindersFragment.TAG);

            if (mMyRemindersFragment == null) {
                // No existing fragment was found, so create a new one
                Log.d(TAG, "Creating new MyRemindersFragment");
                mMyRemindersFragment = new MyRemindersFragment();
                fm.beginTransaction().add(R.id.main_fragment_container, mMyRemindersFragment,
                        MyRemindersFragment.TAG).commit();
            }
        }
        fm.beginTransaction().show(mMyRemindersFragment).commit();
        setTitle(getResources().getString(R.string.navdrawer_item_my_reminders));
    }

    private void hideMapFragment() {
        FragmentManager fm = getSupportFragmentManager();
        mMapFragment = (ObaMapFragment) fm.findFragmentByTag(ObaMapFragment.TAG);
        if (mMapFragment != null && !mMapFragment.asFragment().isHidden()) {
            fm.beginTransaction().hide(mMapFragment.asFragment()).commit();
        }
    }

    private void hideStarredStopsFragment() {
        FragmentManager fm = getSupportFragmentManager();
        mMyStarredStopsFragment = (MyStarredStopsFragment) fm.findFragmentByTag(
                MyStarredStopsFragment.TAG);
        if (mMyStarredStopsFragment != null && !mMyStarredStopsFragment.isHidden()) {
            fm.beginTransaction().hide(mMyStarredStopsFragment).commit();
        }
    }

    private void hideStarredRoutesFragment() {
        FragmentManager fm = getSupportFragmentManager();
        mMyStarredRoutesFragment = (MyStarredRoutesFragment) fm.findFragmentByTag(
                MyStarredRoutesFragment.TAG);
        if (mMyStarredRoutesFragment != null && !mMyStarredRoutesFragment.isHidden()) {
            fm.beginTransaction().hide(mMyStarredRoutesFragment).commit();
        }
    }

    private void hideReminderFragment() {
        FragmentManager fm = getSupportFragmentManager();
        mMyRemindersFragment = (MyRemindersFragment) fm
                .findFragmentByTag(MyRemindersFragment.TAG);
        if (mMyRemindersFragment != null && !mMyRemindersFragment.isHidden()) {
            fm.beginTransaction().hide(mMyRemindersFragment).commit();
        }
    }

    private void hideSlidingPanel() {
        if (mHomeShell != null) {
            mHomeShell.hideSheet();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_options, menu);

        UIUtils.setupSearch(this, menu);

        // Initialize fragment menu visibility here, so we don't have overlap between the various fragments
        setupOptionsMenu(menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        // Manage fragment menu visibility here, so we don't have overlap between the various fragments
        setupOptionsMenu(menu);

        return true;
    }

    private void setupOptionsMenu(Menu menu) {
        menu.setGroupVisible(R.id.main_options_menu_group, true);
        menu.setGroupVisible(R.id.starred_stop_menu_group, mShowStarredStopsMenu);
        menu.setGroupVisible(R.id.starred_route_menu_group, mShowStarredRoutesMenu);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(TAG, "onOptionsItemSelected");
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            // The toolbar up indicator opens the Compose navigation drawer.
            mHomeShell.openDrawer();
            return true;
        }
        // Note: there is no handler for R.id.action_search here — it's an action-view menu
        // item (SearchView), which expands inline rather than firing onOptionsItemSelected
        if (id == R.id.recent_stops_routes) {
            ShowcaseViewUtils.doNotShowTutorial(ShowcaseViewUtils.TUTORIAL_RECENT_STOPS_ROUTES);
            Intent myIntent = new Intent(this, MyRecentStopsAndRoutesActivity.class);
            startActivity(myIntent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case HELP_DIALOG:
                return createHelpDialog();

            case WHATSNEW_DIALOG:
                return createWhatsNewDialog();

            case LEGEND_DIALOG:
                return createLegendDialog();
        }
        return super.onCreateDialog(id);
    }

    @SuppressWarnings("deprecation")
    private Dialog createHelpDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.main_help_title);
        // If a custom API URL is set, hide Contact Us, as we don't have a contact email to use
        int options;
        if (TextUtils.isEmpty(Application.get().getCustomApiUrl())) {
            options = R.array.main_help_options;
        } else {
            // Hide "Contact Us"
            options = R.array.main_help_options_no_contact_us;
        }
        builder.setItems(options,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                ShowcaseViewUtils.resetAllTutorials(HomeActivity.this);
                                NavHelp.goHome(HomeActivity.this, true);
                                break;
                            case 1:
                                showDialog(LEGEND_DIALOG);
                                break;
                            case 2:
                                showDialog(WHATSNEW_DIALOG);
                                break;
                            case 3:
                                AgenciesActivity.start(HomeActivity.this);
                                break;
                            case 4:
                                String twitterUrl = TWITTER_URL;
                                if (Application.get().getCurrentRegion() != null &&
                                        !TextUtils.isEmpty(Application.get().getCurrentRegion()
                                                .getTwitterUrl())) {
                                    twitterUrl = Application.get().getCurrentRegion()
                                            .getTwitterUrl();
                                }
                                UIUtils.goToUrl(HomeActivity.this, twitterUrl);
                                ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                                        Application.get().getPlausibleInstance(),
                                        PlausibleAnalytics.REPORT_MENU_EVENT_URL,
                                        getString(R.string.analytics_label_twitter),
                                        null);
                                break;
                            case 5:
                                // Contact us
                                goToSendFeedBack();
                                break;
                        }
                    }
                }
        );
        return builder.create();
    }

    @SuppressWarnings("deprecation")
    private Dialog createWhatsNewDialog() {
        TextView textView = (TextView) getLayoutInflater().inflate(R.layout.whats_new_dialog, null);
        textView.setText(R.string.main_help_whatsnew);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.main_help_whatsnew_title);
        builder.setIcon(R.mipmap.ic_launcher);
        builder.setView(textView);
        builder.setNeutralButton(R.string.main_help_close,
                (dialog, which) -> dismissDialog(WHATSNEW_DIALOG)
        );
        builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                boolean showOptOut = Application.getPrefs()
                        .getBoolean(ShowcaseViewUtils.TUTORIAL_OPT_OUT_DIALOG, true);
                if (showOptOut) {
                    ShowcaseViewUtils.showOptOutDialog(HomeActivity.this);
                }
            }
        });
        return builder.create();
    }

    @SuppressWarnings("deprecation")
    private Dialog createLegendDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.main_help_legend_title);

        Resources resources = getResources();
        LayoutInflater inflater = LayoutInflater.from(getApplicationContext());
        View legendDialogView = inflater.inflate(R.layout.legend_dialog, null);
        final float etaTextFontSize = 30;

        // On time view
        View etaAndMin = legendDialogView.findViewById(R.id.eta_view_ontime);
        GradientDrawable d1 = (GradientDrawable) etaAndMin.getBackground();
        d1.setColor(resources.getColor(R.color.stop_info_ontime));
        etaAndMin.findViewById(R.id.eta_realtime_indicator).setVisibility(View.VISIBLE);
        TextView etaTextView = etaAndMin.findViewById(R.id.eta);
        etaTextView.setTextSize(etaTextFontSize);
        etaTextView.setText("5");

        // Early View
        etaAndMin = legendDialogView.findViewById(R.id.eta_view_early);
        d1 = (GradientDrawable) etaAndMin.getBackground();
        d1.setColor(resources.getColor(R.color.stop_info_early));
        etaAndMin.findViewById(R.id.eta_realtime_indicator).setVisibility(View.VISIBLE);
        etaTextView = etaAndMin.findViewById(R.id.eta);
        etaTextView.setTextSize(etaTextFontSize);
        etaTextView.setText("5");

        // Delayed View
        etaAndMin = legendDialogView.findViewById(R.id.eta_view_delayed);
        d1 = (GradientDrawable) etaAndMin.getBackground();
        d1.setColor(resources.getColor(R.color.stop_info_delayed));
        etaAndMin.findViewById(R.id.eta_realtime_indicator).setVisibility(View.VISIBLE);
        etaTextView = etaAndMin.findViewById(R.id.eta);
        etaTextView.setTextSize(etaTextFontSize);
        etaTextView.setText("5");

        // Scheduled View
        etaAndMin = legendDialogView.findViewById(R.id.eta_view_scheduled);
        d1 = (GradientDrawable) etaAndMin.getBackground();
        d1.setColor(resources.getColor(R.color.stop_info_scheduled_time));
        etaAndMin.findViewById(R.id.eta_realtime_indicator).setVisibility(View.INVISIBLE);
        etaTextView = etaAndMin.findViewById(R.id.eta);
        etaTextView.setTextSize(etaTextFontSize);
        etaTextView.setText("5");

        // Canceled View
        etaAndMin = legendDialogView.findViewById(R.id.eta_view_canceled);
        d1 = (GradientDrawable) etaAndMin.getBackground();
        d1.setColor(resources.getColor(R.color.stop_info_scheduled_time));
        etaAndMin.findViewById(R.id.eta_realtime_indicator).setVisibility(View.INVISIBLE);
        etaTextView = etaAndMin.findViewById(R.id.eta);
        etaTextView.setTextSize(etaTextFontSize);
        etaTextView.setText("5");
        etaTextView.setPaintFlags(Paint.STRIKE_THRU_TEXT_FLAG);
        TextView etaMin = etaAndMin.findViewById(R.id.eta_min);
        etaMin.setPaintFlags(Paint.STRIKE_THRU_TEXT_FLAG);

        builder.setView(legendDialogView);

        builder.setNeutralButton(R.string.main_help_close,
                (dialog, which) -> dismissDialog(LEGEND_DIALOG)
        );
        return builder.create();
    }

    /**
     * Show the "What's New" message if a new version was just installed
     *
     * @return true if a new version was just installed, false if not
     */
    @SuppressWarnings("deprecation")
    private boolean autoShowWhatsNew() {
        SharedPreferences settings = Application.getPrefs();

        // Get the current app version.
        PackageManager pm = getPackageManager();
        PackageInfo appInfo = null;
        try {
            appInfo = pm.getPackageInfo(getPackageName(),
                    PackageManager.GET_META_DATA);
        } catch (NameNotFoundException e) {
            // Do nothing, perhaps we'll get to show it again? Or never.
            return false;
        }

        final int oldVer = settings.getInt(WHATS_NEW_VER, 0);
        final int newVer = appInfo.versionCode;

        if (oldVer < newVer && mActivityWeakRef.get() != null && !mActivityWeakRef.get().isFinishing()) {
            mActivityWeakRef.get().showDialog(WHATSNEW_DIALOG);
            PreferenceUtils.saveInt(WHATS_NEW_VER, appInfo.versionCode);
            return true;
        }
        return false;
    }

    /**
     * Called by the map fragment when a stop obtains focus, or no stops have focus
     *
     * @param stop     the ObaStop that obtained focus, or null if no stop is in focus
     * @param routes   a HashMap of all route display names that serve this stop - key is routeId
     * @param location the user touch location on the map, or null if the focus was otherwise
     *                 cleared programmatically
     */
    @Override
    public void onFocusChanged(ObaStop stop, HashMap<String, ObaRoute> routes, Location location) {
        // Check to see if we're already focused on this same stop - if so, we shouldn't do anything
        if (mFocusedStopId != null && stop != null &&
                mFocusedStopId.equalsIgnoreCase(stop.getId())) {
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        // If the fragment's state has already been saved, then don't change the state (return)
        if (fm.isStateSaved()) {
            return;
        }

        mFocusedStop = stop;

        if (stop != null) {
            mBikeRentalStationId = null;
            mFocusedStopId = stop.getId();
            // A stop on the map was just tapped, show it in the sliding panel
            updateArrivalListFragment(stop.getId(), stop.getName());

            ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                    Application.get().getPlausibleInstance(),
                    PlausibleAnalytics.REPORT_MAP_EVENT_URL,
                    getString(R.string.analytics_label_button_press_map_icon),
                    null);
        } else {
            // No stop is in focus (e.g., user tapped on the map), so hide the panel
            // and clear the currently focused stopId
            mFocusedStopId = null;
            mHomeShell.hideSheet();
            if (mArrivalsPanelFragment != null) {
                fm.beginTransaction().remove(mArrivalsPanelFragment).commit();
                mArrivalsPanelFragment = null;
            }
        }
    }

    /**
     * Called from the map fragment when a BikeRentalStation is clicked.
     *
     * @param bikeRentalStation the bike rental station that was clicked.
     */
    @Override
    public void onFocusChanged(BikeRentalStation bikeRentalStation) {
        Log.d(TAG, "Bike Station Clicked on map");

        // Check to see if we're already focused on this same bike rental station - if so, we shouldn't do anything
        if (mBikeRentalStationId != null && bikeRentalStation != null &&
                mBikeRentalStationId.equalsIgnoreCase(bikeRentalStation.id)) {
            return;
        }

        if (bikeRentalStation == null) {
            mBikeRentalStationId = null;
        } else {
            mBikeRentalStationId = bikeRentalStation.id;
        }
    }

    @Override
    public void onProgressBarChanged(boolean showProgressBar) {
        mLastMapProgressBarState = showProgressBar;
        if (showProgressBar) {
            showMapProgressBar();
        } else {
            hideMapProgressBar();
        }
    }

    /**
     * Called by the ArrivalsPanelFragment when we have new updated arrival information
     *
     * @param response new arrival information
     */
    @Override
    public void onArrivalsLoaded(ObaArrivalInfoResponse response) {
        if (response == null || response.getStop() == null) {
            return;
        }

        // If we're missing any local references (e.g., if orientation just changed), store the values
        if (mFocusedStopId == null) {
            mFocusedStopId = response.getStop().getId();
        }
        if (mFocusedStop == null) {
            mFocusedStop = response.getStop();

            // Since mFocusedStop was null, the layout changed, and we should recenter map on stop
            if (mMapFragment != null && mHomeShell != null) {
                mMapFragment.setMapCenter(mFocusedStop.getLocation(), false,
                        mHomeShell.isSheetExpanded());
            }

            // ...and we should add a focus marker for this stop
            if (mMapFragment != null) {
                mMapFragment.setFocusStop(mFocusedStop, response.getRoutes());
            }
        }

        // Show arrival info related tutorials
        showArrivalInfoTutorials(response);
    }

    /**
     * Triggers the various tutorials related to arrival info and the sliding panel header
     *
     * @param response arrival info, which is required for some tutorials
     */
    private void showArrivalInfoTutorials(ObaArrivalInfoResponse response) {
        // If we're already showing a ShowcaseView, we don't want to stack another on top
        if (ShowcaseViewUtils.isShowcaseViewShowing()) {
            return;
        }

        // If we can't see the map or sliding panel, we can't see the arrival info, so return
        Fragment mapFrag = mMapFragment.asFragment();
        if (mapFrag.isHidden() || !mapFrag.isVisible() ||
                mHomeShell.isSheetHidden()) {
            return;
        }

        // The arrival-header tutorials (arrival info / sliding panel / star route) anchored to the
        // legacy header's Views, which the Compose panel no longer exposes, so they've been retired.
        // The general "recent stops/routes" tutorial still applies.
        ShowcaseViewUtils.showTutorial(ShowcaseViewUtils.TUTORIAL_RECENT_STOPS_ROUTES, this, null, false);
    }

    /**
     * Called by the ArrivalsPanelFragment when the user selects "Show vehicles on map" for a
     * route. Collapses the panel and switches the existing map to route mode.
     */
    @Override
    public void onShowRouteOnMap(String routeId) {
        // Collapse the panel so the user can see the map
        if (mHomeShell != null) {
            mHomeShell.collapseSheet();
        }

        Bundle bundle = new Bundle();
        bundle.putBoolean(MapParams.ZOOM_TO_ROUTE, false);
        bundle.putBoolean(MapParams.ZOOM_INCLUDE_CLOSEST_VEHICLE, true);
        bundle.putString(MapParams.ROUTE_ID, routeId);
        mMapFragment.setMapMode(MapParams.MODE_ROUTE, bundle);
    }

    /**
     * Called when the user taps the panel header/chevron: toggle between collapsed and anchored.
     */
    @Override
    public void onToggleExpand() {
        if (mHomeShell == null) {
            return;
        }
        if (isSlidingPanelCollapsed()) {
            mHomeShell.expandSheet();
        } else {
            mHomeShell.collapseSheet();
        }
    }

    /**
     * Called by the panel as the preferred-arrival preview changes, so the collapsed peek height
     * matches the legacy header (no-arrivals / one / two, plus a filter-indicator offset).
     */
    @Override
    public void onPreferredHeight(int previewCount, boolean filtering) {
        int heightDimen;
        if (previewCount >= 2) {
            heightDimen = R.dimen.arrival_header_height_two_arrivals;
        } else if (previewCount == 1) {
            heightDimen = R.dimen.arrival_header_height_one_arrival;
        } else {
            heightDimen = R.dimen.arrival_header_height_no_arrivals;
        }
        int px = getResources().getDimensionPixelSize(heightDimen);
        if (filtering) {
            px += getResources().getDimensionPixelSize(
                    R.dimen.arrival_header_height_offset_filter_routes);
        }
        mSheetPeekPx = px;
        if (mHomeShell != null) {
            mHomeShell.setSheetPeekHeightPx(px);
        }
    }

    /**
     * Redraw navigation drawer. This is necessary because we do not know whether to draw the
     * "Plan A Trip" option until a region is selected.
     */
    private void redrawNavigationDrawerFragment() {
        refreshDrawerItems();
    }

    /**
     * Create a new fragment to show the arrivals list for the given stop.  An ObaStop object
     * should
     * be passed in if available.  In all cases a stopId, stopName, and stopCode must be provided.
     *
     * @param stopId   Stop ID of the stop to show arrivals for
     * @param stopName Stop name of the stop to show arrivals for
     * @param stopCode Stop Code (rider-facing ID) of the stop to show arrivals for
     * @param stop     The ObaStop object for the stop to show arrivals for, or null if we don't
     *                 have
     *                 this yet.
     * @param routes   A HashMap of all route display names that serve this stop - key is routeId,
     *                 or
     *                 null if we don't have this yet.
     */
    private void updateArrivalListFragment(@NonNull String stopId, @NonNull String stopName) {
        FragmentManager fm = getSupportFragmentManager();

        // The Compose panel loads its own data from the stop id (it shows a brief loading state
        // until the first response), so no stop/route objects need to be pre-populated.
        mArrivalsPanelFragment = ArrivalsPanelFragment.newInstance(stopId, stopName);
        mArrivalsPanelFragment.setListener(this);
        fm.beginTransaction().replace(R.id.slidingFragment, mArrivalsPanelFragment).commit();
        showSlidingPanel();
    }

    private void showSlidingPanel() {
        if (mHomeShell != null && mHomeShell.isSheetHidden()) {
            mHomeShell.collapseSheet();
        }
    }

    private void goToSendFeedBack() {
        if (mFocusedStop != null) {
            ReportActivity.start(this, mFocusedStopId, mFocusedStop.getName(), mFocusedStop.getStopCode(),
                    mFocusedStop.getLatitude(), mFocusedStop.getLongitude(), mGoogleApiClient);
        } else {
            Location loc = Application.getLastKnownLocation(this, mGoogleApiClient);
            if (loc != null) {
                ReportActivity.start(this, loc.getLatitude(), loc.getLongitude(), mGoogleApiClient);
            } else {
                ReportActivity.start(this, mGoogleApiClient);
            }
        }
    }

    /**
     * Checks region status, which can potentially including forcing a reload of region
     * info from the server.  Also includes auto-selection of closest region.
     */
    private void checkRegionStatus() {
        //First check for custom API URL set by user via Preferences, since if that is set we don't need region info from the REST API
        if (!TextUtils.isEmpty(Application.get().getCustomApiUrl())) {
            return;
        }

        // Check if region is hard-coded for this build flavor
        if (BuildConfig.USE_FIXED_REGION) {
            ObaRegion r = RegionUtils.getRegionFromBuildFlavor();
            // Set the hard-coded region
            RegionUtils.saveToProvider(this, Collections.singletonList(r));
            Application.get().setCurrentRegion(r);
            // Disable any region auto-selection in preferences
            PreferenceUtils
                    .saveBoolean(getString(R.string.preference_key_auto_select_region), false);
            return;
        }

        boolean forceReload = false;
        boolean showProgressDialog = true;

        //If we don't have region info selected, or if enough time has passed since last region info update,
        //force contacting the server again
        if (Application.get().getCurrentRegion() == null ||
                new Date().getTime() - Application.get().getLastRegionUpdateDate()
                        > REGION_UPDATE_THRESHOLD) {
            forceReload = true;
            Log.d(TAG,
                    "Region info has expired (or does not exist), forcing a reload from the server...");
        }

        if (Application.get().getCurrentRegion() != null) {
            //We already have region info locally, so just check current region status quietly in the background
            showProgressDialog = false;
        }

        try {
            PackageInfo appInfo = getPackageManager().getPackageInfo(getPackageName(),
                    PackageManager.GET_META_DATA);
            SharedPreferences settings = Application.getPrefs();
            final int oldVer = settings.getInt(CHECK_REGION_VER, 0);
            final int newVer = appInfo.versionCode;

            if (oldVer < newVer) {
                forceReload = true;
            }
            PreferenceUtils.saveInt(CHECK_REGION_VER, appInfo.versionCode);
        } catch (NameNotFoundException e) {
            // Do nothing
        }

        //Check region status, possibly forcing a reload from server and checking proximity to current region
        List<ObaRegionsTask.Callback> callbacks = new ArrayList<>();
        callbacks.add((ObaRegionsTask.Callback) mMapFragment);
        callbacks.add(this);
        ObaRegionsTask task = new ObaRegionsTask(this, callbacks, forceReload, showProgressDialog);
        task.execute();
    }

    //
    // Region Task Callback
    //
    @Override
    public void onRegionTaskFinished(boolean currentRegionChanged) {
        // Show "What's New" (which might need refreshed Regions API contents)
        boolean update = autoShowWhatsNew();

        // Redraw nav drawer if the region changed, or if we just installed a new version
        if (currentRegionChanged || update) {
            redrawNavigationDrawerFragment();
        }

        // If region changed and was auto-selected, show user what region we're using
        if (currentRegionChanged
                && Application.getPrefs()
                .getBoolean(getString(R.string.preference_key_auto_select_region), true)
                && Application.get().getCurrentRegion() != null
                && UIUtils.canManageDialog(this)) {
            Toast.makeText(getApplicationContext(),
                    getString(R.string.region_region_found,
                            Application.get().getCurrentRegion().getName()),
                    Toast.LENGTH_LONG
            ).show();
        }
        updateLayersFab();
    }

    /**
     * Initializes the Compose map chrome (my-location FAB, zoom controls, layers FAB). The Views now
     * live in the Compose MapChrome overlay (HomeShellHost); here we just push the initial visibility
     * + left-hand-mode based on the nav selection and region.
     */
    private void setupMapChrome() {
        checkLeftHandMode();
        if (mCurrentNavDrawerPosition == NAVDRAWER_ITEM_NEARBY) {
            showFloatingActionButtons();
            showMapProgressBar();
        } else {
            hideFloatingActionButtons();
            hideMapProgressBar();
        }
        updateLayersFab();
    }

    private void checkDisplayZoomControls() {
        boolean displayZoom = Application.getPrefs().getBoolean(
                getString(R.string.preference_key_show_zoom_controls), false);
        showZoomControls(displayZoom);
    }

    /**
     * Shows zoom controls if state is true, hides the zoom controls if state is false
     * @param showZoom true if the zoom controls should be visible, false if they should be hidden
     */
    private void showZoomControls(boolean showZoom) {
        if (mHomeShell != null) {
            mHomeShell.setZoomVisible(showZoom);
        }
    }

    private void checkLeftHandMode() {
        boolean leftHandMode = Application.getPrefs().getBoolean(
                getString(R.string.preference_key_left_hand_mode), false);
        if (mHomeShell != null) {
            mHomeShell.setLeftHandMode(leftHandMode);
        }
    }

    private void showFloatingActionButtons() {
        if (mHomeShell != null) {
            mHomeShell.setFabsVisible(true);
            // This is the NEARBY path (showMapFragment), so the layers FAB shows whenever bikeshare
            // is available. We gate on bikeshare only (not the nav position) because goToNavDrawerItem
            // sets mCurrentNavDrawerPosition *after* calling showMapFragment; updateLayersFab() applies
            // the position gate later (onResume / region updates).
            mHomeShell.setLayersVisible(Application.isBikeshareEnabled());
            mHomeShell.setBikeshareActive(LayerUtils.isBikeshareLayerVisible());
        }
    }

    private void hideFloatingActionButtons() {
        if (mHomeShell != null) {
            mHomeShell.setFabsVisible(false);
        }
    }

    // --- HomeShellHost.MapActionListener: the Compose map-chrome FAB actions ---

    @Override
    public void onMyLocation() {
        if (mMapFragment != null) {
            // Reset the preference to ask user to enable location
            PreferenceUtils.saveBoolean(getString(R.string.preference_key_never_show_location_dialog), false);
            PreferenceUtils.setUserDeniedLocationPermissions(false);

            mMapFragment.setMyLocation(true, true);
            ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                    Application.get().getPlausibleInstance(),
                    PlausibleAnalytics.REPORT_MAP_EVENT_URL,
                    getString(R.string.analytics_label_button_press_location),
                    null);
        }
    }

    @Override
    public void onZoomIn() {
        if (mMapFragment != null) {
            mMapFragment.zoomIn();
        }
    }

    @Override
    public void onZoomOut() {
        if (mMapFragment != null) {
            mMapFragment.zoomOut();
        }
    }

    @Override
    public void onToggleBikeshare() {
        if (mMapFragment == null) {
            return;
        }
        boolean active = LayerUtils.isBikeshareLayerVisible();
        LayerInfo layer = LayerUtils.bikeshareLayerInfo;
        LayerActivationListener mapLayers = (LayerActivationListener) mMapFragment;
        if (active) {
            mapLayers.onDeactivateLayer(layer);
        } else {
            mapLayers.onActivateLayer(layer);
        }
        // Persist + reflect the toggled state (mirrors the legacy LayersSpeedDialAdapter).
        Application.getPrefs().edit()
                .putBoolean(layer.getSharedPreferenceKey(), !active).apply();
        mHomeShell.setBikeshareActive(!active);
    }

    private void showMapProgressBar() {
        if (mMapProgressBar == null) {
            return;
        }
        if (mMapProgressBar.getVisibility() != View.VISIBLE) {
            mMapProgressBar.setVisibility(View.VISIBLE);
        }
    }

    private void hideMapProgressBar() {
        if (mMapProgressBar == null) {
            return;
        }
        if (mMapProgressBar.getVisibility() != View.GONE) {
            mMapProgressBar.setVisibility(View.GONE);
        }
    }

    private void setupNavigationDrawer() {
        refreshDrawerItems();

        // Determine the initial selection: NEARBY if launched to show a route/stop, else the last
        // remembered tab (mirrors NavigationDrawerFragment's saved-position behavior).
        int initialPosition = Application.getPrefs()
                .getInt(STATE_SELECTED_POSITION, NAVDRAWER_ITEM_NEARBY);
        Bundle bundle = getIntent().getExtras();
        if (bundle != null
                && (bundle.getString(MapParams.ROUTE_ID) != null
                || bundle.getString(MapParams.STOP_ID) != null)) {
            initialPosition = NAVDRAWER_ITEM_NEARBY;
        }
        final int position = initialPosition;
        // Defer the first content selection until the island is attached (the AndroidView host
        // attaches it during composition, after onCreate), so the fragment commit finds its container.
        mMapContent.post(() -> onHomeNavItemSelected(toHomeNavItem(position)));
    }

    /** Rebuilds the region-gated drawer item list (mirrors NavigationDrawerFragment.populateNavDrawer). */
    private void refreshDrawerItems() {
        if (mHomeShell == null) {
            return;
        }
        ObaRegion region = Application.get().getCurrentRegion();
        java.util.List<HomeNavItem> items = new java.util.ArrayList<>();
        items.add(HomeNavItem.NEARBY);
        items.add(HomeNavItem.STARRED_STOPS);
        items.add(HomeNavItem.STARRED_ROUTES);
        if (ReminderUtils.shouldShowReminders()) {
            items.add(HomeNavItem.MY_REMINDERS);
        }
        if (region != null) {
            if (!TextUtils.isEmpty(region.getOtpBaseUrl())
                    || !TextUtils.isEmpty(Application.get().getCustomOtpApiUrl())) {
                items.add(HomeNavItem.PLAN_TRIP);
            }
            if (!TextUtils.isEmpty(region.getPaymentAndroidAppId())) {
                items.add(HomeNavItem.PAY_FARE);
            }
        }
        items.add(HomeNavItem.OPEN_SOURCE);
        items.add(HomeNavItem.SETTINGS);
        items.add(HomeNavItem.HELP);
        items.add(HomeNavItem.SEND_FEEDBACK);
        mHomeShell.setItems(items);
    }

    /** Bridges a Compose-drawer selection to the legacy int-based routing. */
    private void onHomeNavItemSelected(HomeNavItem item) {
        if (!item.getLaunchesActivity()) {
            mHomeShell.setSelected(item);
            Application.getPrefs().edit().putInt(STATE_SELECTED_POSITION, toPosition(item)).apply();
        }
        goToNavDrawerItem(toPosition(item));
    }

    private int toPosition(HomeNavItem item) {
        switch (item) {
            case STARRED_STOPS: return NAVDRAWER_ITEM_STARRED_STOPS;
            case STARRED_ROUTES: return NAVDRAWER_ITEM_STARRED_ROUTES;
            case MY_REMINDERS: return NAVDRAWER_ITEM_MY_REMINDERS;
            case PLAN_TRIP: return NAVDRAWER_ITEM_PLAN_TRIP;
            case PAY_FARE: return NAVDRAWER_ITEM_PAY_FARE;
            case SETTINGS: return NAVDRAWER_ITEM_SETTINGS;
            case HELP: return NAVDRAWER_ITEM_HELP;
            case SEND_FEEDBACK: return NAVDRAWER_ITEM_SEND_FEEDBACK;
            case OPEN_SOURCE: return NAVDRAWER_ITEM_OPEN_SOURCE;
            case NEARBY:
            default: return NAVDRAWER_ITEM_NEARBY;
        }
    }

    private HomeNavItem toHomeNavItem(int position) {
        switch (position) {
            case NAVDRAWER_ITEM_STARRED_STOPS: return HomeNavItem.STARRED_STOPS;
            case NAVDRAWER_ITEM_STARRED_ROUTES: return HomeNavItem.STARRED_ROUTES;
            case NAVDRAWER_ITEM_MY_REMINDERS: return HomeNavItem.MY_REMINDERS;
            default: return HomeNavItem.NEARBY;
        }
    }

    private void setupGooglePlayServices() {
        // Init Google Play Services as early as possible in the Fragment lifecycle to give it time
        GoogleApiAvailability api = GoogleApiAvailability.getInstance();
        if (api.isGooglePlayServicesAvailable(this)
                == ConnectionResult.SUCCESS) {
            mGoogleApiClient = LocationUtils.getGoogleApiClientWithCallbacks(this);
            mGoogleApiClient.connect();
        }
    }

    /**
     * (Re)displays the Compose layers FAB and syncs its active tint when the activity restarts or
     * region data updates. The FAB shows only for bikeshare-enabled regions on the NEARBY tab.
     */
    private void updateLayersFab() {
        if (mHomeShell == null) {
            return;
        }
        mHomeShell.setLayersVisible(Application.isBikeshareEnabled()
                && mCurrentNavDrawerPosition == NAVDRAWER_ITEM_NEARBY);
        mHomeShell.setBikeshareActive(LayerUtils.isBikeshareLayerVisible());
    }


    private void setupSlidingPanel() {
        // The Compose BottomSheetScaffold (in mHomeShell) starts hidden; seed its peek height with
        // the legacy default (two arrivals) so the first reveal doesn't flash an undersized peek.
        // onPreferredHeight() then keeps it in sync with the actual arrival preview.
        mSheetPeekPx = getResources().getDimensionPixelSize(
                R.dimen.arrival_header_height_two_arrivals);
        if (mHomeShell != null) {
            mHomeShell.setSheetPeekHeightPx(mSheetPeekPx);
        }
    }

    /**
     * Reacts to the arrivals sheet settling into a new state, replacing the legacy
     * SlidingUpPanel PanelSlideListener. As before, the initial hidden->collapsed reveal is ignored
     * here (it's handled by updateArrivalListFragment()/onArrivalsLoaded()); the legacy ANCHORED
     * behavior (recenter on the focused stop) now happens on EXPANDED, since the half-anchor is gone.
     */
    private void onSheetState(HomeShellHost.Sheet state) {
        HomeShellHost.Sheet previous = mLastSheetState;
        mLastSheetState = state;
        if (previous == HomeShellHost.Sheet.HIDDEN) {
            return;
        }
        switch (state) {
            case EXPANDED:
                if (mMapFragment != null) {
                    mMapFragment.getMapView().setPadding(null, null, null, mSheetPeekPx);
                }
                if (mFocusedStop != null && mMapFragment != null) {
                    mMapFragment.setMapCenter(mFocusedStop.getLocation(), true, true);
                }
                if (mArrivalsPanelFragment != null) {
                    mArrivalsPanelFragment.setPanelCollapsed(false);
                }
                break;
            case COLLAPSED:
                if (mMapFragment != null) {
                    mMapFragment.getMapView().setPadding(null, null, null, mSheetPeekPx);
                }
                if (mArrivalsPanelFragment != null) {
                    mArrivalsPanelFragment.setPanelCollapsed(true);
                }
                break;
            case HIDDEN:
                // We hide the panel when switching fragments via the navdrawer, so we shouldn't do
                // anything here that loses the map/arrivals state (e.g. removing the panel fragment).
                if (mMapFragment != null) {
                    mMapFragment.getMapView().setPadding(null, null, null, 0);
                }
                break;
        }
    }

    /**
     * Sets up the initial map state, based on a previous savedInstanceState for this activity,
     * or an Intent that was passed into this activity
     */
    private void setupMapState(Bundle savedInstanceState) {
        String stopId;
        String stopName;
        String stopCode;
        // Check savedInstanceState to see if there is a previous state for this activity
        if (savedInstanceState != null) {
            // We're recreating an instance with a previous state, so show the focused stop in panel
            stopId = savedInstanceState.getString(MapParams.STOP_ID);
            stopName = savedInstanceState.getString(MapParams.STOP_NAME);
            stopCode = savedInstanceState.getString(MapParams.STOP_CODE);

            if (stopId != null) {
                mFocusedStopId = stopId;
                updateArrivalListFragment(stopId, stopName);
            }
        } else {
            // Check intent passed into Activity
            Bundle bundle = getIntent().getExtras();
            if (bundle != null) {
                // Did this activity start to focus on a stop?  If so, set focus and show arrival info
                stopId = bundle.getString(MapParams.STOP_ID);
                stopName = bundle.getString(MapParams.STOP_NAME);
                stopCode = bundle.getString(MapParams.STOP_CODE);
                double lat = bundle.getDouble(MapParams.CENTER_LAT);
                double lon = bundle.getDouble(MapParams.CENTER_LON);

                if (stopId != null && lat != 0.0 && lon != 0.0) {
                    mFocusedStopId = stopId;
                    updateArrivalListFragment(stopId, stopName);
                }
            }
        }
        mMapProgressBar = mMapContent.findViewById(R.id.progress_horizontal);
    }

    /**
     * Setup permissions that are only requested if the user joins the travel behavior study. This
     * method must be called from #onCreate().
     *
     * A call to #requestPhysicalActivityPermission() invokes the permission request, and should only
     * be called in the case when the user opts into the study.
     * @param activity
     */
    private void setupPermissions(AppCompatActivity activity) {
//        travelBehaviorPermissionsLauncher =
//                registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
//                    if (isGranted) {
//                        // User opt-ed into study and granted physical activity tracking - now request background location permissions (when targeting Android 11 we can't request both simultaneously)
//                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                            activity.requestPermissions(TravelBehaviorConstants.BACKGROUND_LOCATION_PERMISSION, BACKGROUND_LOCATION_PERMISSION_REQUEST);
//                        }
//                    }
//                });
    }

    /**
     * Requests physical activity permissions, and then subsequently background location
     * permissions (based on the initialization in #setupPermissions() if the user grants physical
     * activity permissions. This method should only be called after the user opts into the travel behavior study.
     */
    public void requestPhysicalActivityPermission() {
//        if (travelBehaviorPermissionsLauncher != null){
//            travelBehaviorPermissionsLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION);
//        }
    }

    /**
     * Collapsed means the arrivals sheet isn't fully expanded (it's peeking or hidden). This drives
     * the preview-vs-full state of the Compose arrivals panel.
     *
     * @return true if the sheet isn't fully expanded, false if it is
     */
    private boolean isSlidingPanelCollapsed() {
        return mHomeShell == null || mHomeShell.isSheetCollapsed();
    }

    private void checkBatteryOptimizations() {
        if (PreferenceUtils.getBoolean(getString(R.string.not_request_battery_optimizations_key),
                false) || !TravelBehaviorUtils.isUserParticipatingInStudy()) {
            return;
        }

        Boolean ignoringBatteryOptimizations = Application.isIgnoringBatteryOptimizations(getApplicationContext());
        if (ignoringBatteryOptimizations != null && !ignoringBatteryOptimizations) {
            showIgnoreBatteryOptimizationDialog();
        }
    }

    private void showIgnoreBatteryOptimizationDialog() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.application_ignoring_battery_opt_message, getString(R.string.app_name)))
                .setTitle(R.string.application_ignoring_battery_opt_title)
                .setIcon(R.drawable.ic_alert_warning)
                .setCancelable(false)
                .setPositiveButton(R.string.travel_behavior_dialog_yes,
                        (dialog, which) -> {
                            if (PermissionUtils.hasGrantedAllPermissions(this, new String[]{Manifest.
                                    permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS})) {
                                UIUtils.openBatteryIgnoreIntent(this);
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    requestPermissions(new String[]{Manifest.
                                                    permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS},
                                            BATTERY_OPTIMIZATIONS_PERMISSION_REQUEST);
                                }
                            }
                            PreferenceUtils.saveBoolean(getString(R.string.not_request_battery_optimizations_key),
                                    true);
                        })
                .setNegativeButton(R.string.travel_behavior_dialog_no,
                        (dialog, which) -> {
                            PreferenceUtils.saveBoolean(getString(R.string.not_request_battery_optimizations_key),
                                    true);
                        })
                .create().show();
    }

    // Getting a callback from the map fragment to check if we are in a valid region or not
    @Override
    public void onValidRegion(boolean isValid) {
        if(isValid){
            makeWeatherRequest();
            getGtfsAlerts();
        }else{
            if (mHomeShell != null) {
                mHomeShell.hideWeather();
            }
            weatherResponse = null;
        }
    }

    private void setWeatherData() {
        if (weatherResponse == null || mCurrentNavDrawerPosition != NAVDRAWER_ITEM_NEARBY
                || WeatherUtils.isWeatherViewHiddenPref() || mHomeShell == null) {
            return;
        }
        String weatherIcon = weatherResponse.getCurrent_forecast().getIcon();
        double weatherTemp = weatherResponse.getCurrent_forecast().getTemperature();
        String icon = weatherIcon != null ? weatherIcon : "";
        mHomeShell.showWeather(
                WeatherUtils.getWeatherIconRes(icon),
                WeatherUtils.formatTemperature(weatherTemp),
                WeatherUtils.isFitIcon(icon));
    }

    @Override
    public void onWeatherClick() {
        if (weatherResponse == null || weatherResponse.getCurrent_forecast() == null) {
            return;
        }
        String summary = weatherResponse.getCurrent_forecast().getSummary();
        if (summary != null) {
            Toast.makeText(getApplicationContext(), summary.trim(), Toast.LENGTH_SHORT).show();
        }
    }

    private void makeWeatherRequest(){
        if(WeatherUtils.isWeatherViewHiddenPref()) return;
        // If weather response is null that means we need to call the weather api to get the new data
        // Adding this will avoid doing multiple requests to the weather API when updating the map in real-time
        if(weatherResponse == null){
            ObaWeatherRequest weatherRequest = ObaWeatherRequest.newRequest(Application.get().getCurrentRegion().getId());
            WeatherRequestTask task = new WeatherRequestTask(this);
            task.execute(weatherRequest);
            Log.d(TAG,"Weather requested");
        }else{
            // We have a weather data no need to make a request
            setWeatherData();
        }

    }

    @Override
    public void onWeatherResponseReceived(ObaWeatherResponse response) {
        if(response != null && response.getCurrent_forecast() != null){
            weatherResponse = response;
            setWeatherData();
        }
    }

    @Override
    public void onWeatherRequestFailed() {
        Log.d(TAG,"Weather Request Fail");
    }

    private void setupDonationView(HomeActivity homeActivity) {
        mDonationView = mMapContent.findViewById(R.id.donationView);
        AppCompatImageButton closeButton = mDonationView.findViewById(R.id.btnDonationViewClose);
        Button learnMoreButton = mDonationView.findViewById(R.id.btnDonationViewLearnMore);
        Button donateButton = mDonationView.findViewById(R.id.btnDonationViewDonate);

        // Update title with app name for white-label support
        TextView titleView = mDonationView.findViewById(R.id.textView2);
        titleView.setText(getString(R.string.donation_view_title, getString(R.string.app_name)));

        closeButton.setOnClickListener(b -> {
            AlertDialog dismissDialog = buildDismissDonationsDialog();
            dismissDialog.show();
        });

        learnMoreButton.setOnClickListener(b -> {
            Intent intent = new Intent(this, DonationLearnMoreActivity.class);
            startActivity(intent);
        });

        donateButton.setOnClickListener(b -> {
            DonationsManager donationsManager = Application.getDonationsManager();
            donationsManager.dismissDonationRequests();

            Intent intent = donationsManager.buildOpenDonationsPageIntent();
            startActivity(intent);
        });

        updateDonationsUIVisibility();
    }

    private void updateDonationsUIVisibility() {
        mDonationView = mMapContent.findViewById(R.id.donationView);
        if(mDonationView == null) return;
        DonationsManager donationsManager = Application.getDonationsManager();

        if (donationsManager.shouldShowDonationUI() && mCurrentNavDrawerPosition == NAVDRAWER_ITEM_NEARBY) {
            mDonationView.setVisibility(View.VISIBLE);
        }
        else {
            mDonationView.setVisibility(View.GONE);
        }
    }

    /**
     * Creates an AlertDialog that will give the user options for dismissing the donations UI.
     * @return the AlertDialog for presentation.
     */
    private AlertDialog buildDismissDonationsDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.donation_dismiss_dialog_title)
                .setMessage(getString(R.string.donation_dismiss_dialog_body, getString(R.string.app_name)))
                .setNegativeButton(
                        R.string.donation_dismiss_dialog_dont_want_to_help_button,
                        (dialog, which) -> {
                            Application.getDonationsManager().dismissDonationRequests();
                            updateDonationsUIVisibility();
                        }
                )
                .setNeutralButton(R.string.donation_dismiss_dialog_remind_me_later_button,
                        (dialog, which) -> {
                            Application.getDonationsManager().remindUserLater();
                            updateDonationsUIVisibility();
                        })
                .setPositiveButton(R.string.donation_dismiss_dialog_cancel_button, (d, w) -> {})
                .setCancelable(true);

        return builder.create();
    }
    private void initSurveyView(){
        mSurveyView = mMapContent.findViewById(R.id.surveyView);
    }
    private void setupSurvey() {
        if(Application.get().getCurrentRegion() == null || mCurrentNavDrawerPosition != NAVDRAWER_ITEM_NEARBY) return;
        initSurveyView();
        initSurveyManager(mSurveyView);
    }

    private void initSurveyManager(View surveyView){
        surveyManager = new SurveyManager(this, surveyView,false, new SurveyListener() {
            @Override
            public void onSurveyResponseReceived(StudyResponse response) {
                surveyManager.onSurveyResponseReceived(response);
            }

            @Override
            public void onSurveyResponseFail() {
                surveyManager.onSurveyResponseFail();
            }

            @Override
            public void onSubmitSurveyResponseReceived(SubmitSurveyResponse response) {
                surveyManager.onSubmitSurveyResponseReceived(response);
            }

            @Override
            public void onSubmitSurveyFail() {
                surveyManager.onSubmitSurveyFail();
            }


            @Override
            public void onSkipSurvey() {
                surveyManager.onSkipSurvey();
            }

            @Override
            public void onRemindMeLater() {
                surveyManager.onRemindMeLater();
            }

            @Override
            public void onCancelSurvey() {
                surveyManager.onCancelSurvey();
            }

        });
        surveyManager.requestSurveyData();
    }

    private void getGtfsAlerts() {
        String regionId = String.valueOf(Application.get().getCurrentRegion().getId());
        Application.getGtfsAlerts().fetchAlerts(regionId, new GtfsAlertCallBack() {
            @Override
            public void onAlert(String title, String message, String url) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    GtfsAlertsHelper.showWideAlertDialog(HomeActivity.this, title, message, url);
                });
            }
        });
    }

}
