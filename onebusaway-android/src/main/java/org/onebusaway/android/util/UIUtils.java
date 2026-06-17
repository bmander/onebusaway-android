/*
 * Copyright (C) 2010-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South  Florida (sjbarbeau@gmail.com), Microsoft Corporation
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

package org.onebusaway.android.util;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ContentQueryMap;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.util.Pair;
import androidx.core.view.MenuItemCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.analytics.FirebaseAnalytics;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.ObaAnalytics;
import org.onebusaway.android.io.ObaApi;
import org.onebusaway.android.io.PlausibleAnalytics;
import org.onebusaway.android.io.elements.ObaArrivalInfo;
import org.onebusaway.android.io.elements.ObaRegion;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaSituation;
import org.onebusaway.android.io.elements.ObaStop;
import org.onebusaway.android.io.elements.Occupancy;
import org.onebusaway.android.io.elements.OccupancyState;
import org.onebusaway.android.io.request.ObaArrivalInfoResponse;
import org.onebusaway.android.provider.ObaContract;
import org.onebusaway.android.ui.arrivals.ArrivalsListLauncher;
import org.onebusaway.android.ui.routeinfo.RouteInfoLauncher;
import org.onebusaway.util.comparators.AlphanumComparator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A class containing utility methods related to the user interface
 */
public final class UIUtils {

    /**
     * Generates the dialog text that is used to show detailed information about a particular stop
     *
     * @return a pair of Strings consisting of the <dialog title, dialog message>
     */
    public static Pair<String, String> createStopDetailsDialogText(Context context, String stopName,
            String stopUserName, String stopCode, String stopDirection,
            List<String> routeDisplayNames) {
        final String newLine = "\n";
        String title = "";
        StringBuilder message = new StringBuilder();

        if (!TextUtils.isEmpty(stopUserName)) {
            title = stopUserName;
            if (stopName != null) {
                // Show official stop name in addition to user name
                message.append(
                        context.getString(R.string.stop_info_official_stop_name_label, stopName))
                        .append(newLine);
            }
        } else if (stopName != null) {
            title = stopName;
        }

        if (stopCode != null) {
            message.append(context.getString(R.string.stop_details_code, stopCode) + newLine);
        }

        // Routes that serve this stop
        if (routeDisplayNames != null) {
            String routes = context.getString(R.string.stop_info_route_ids_label) + " " + RouteDisplay
                    .formatRouteDisplayNames(routeDisplayNames, new ArrayList<String>());
            message.append(routes);
        }

        if (!TextUtils.isEmpty(stopDirection)) {
            message.append(newLine)
                    .append(context.getString(DisplayFormat.getStopDirectionText(stopDirection)));
        }
        return new Pair(title, message.toString());
    }

    /**
     * Builds an AlertDialog with the given title and message
     *
     * @return an AlertDialog with the given title and message
     */
    public static AlertDialog buildAlertDialog(Context context, String title, String message) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(title);
        builder.setMessage(message);
        return builder.create();
    }

    /**
     * Creates a new shortcut for the provided stop, and returns the ShortcutInfo for that shortcut
     * @param context Context used to create the shortcut
     * @param shortcutName the shortcutName for the stop shortcut
     * @param builder Instance of ArrivalsListLauncher.Builder for the provided stop
     * @return the ShortcutInfo for the created shortcut
     */
    public static ShortcutInfoCompat createStopShortcut(Context context, String shortcutName, ArrivalsListLauncher.Builder builder) {
        final ShortcutInfoCompat shortcut = UIUtils.makeShortcutInfo(context,
                shortcutName,
                builder.getIntent(),
                R.drawable.ic_stop_flag_triangle);
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null);
        return shortcut;
    }

    /**
     * Creates a new shortcut for the provided route, and returns the ShortcutInfo for that shortcut
     * @param context Context used to create the shortcut
     * @param routeId ID of the route
     * @param routeName short name of the route
     * @return the ShortcutInfo for the created shortcut
     */
    public static ShortcutInfoCompat createRouteShortcut(Context context, String routeId, String routeName) {
        final ShortcutInfoCompat shortcut = UIUtils.makeShortcutInfo(context,
                routeName,
                RouteInfoLauncher.makeIntent(context, routeId),
                R.drawable.ic_trip_details);
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null);
        return shortcut;
    }

    /**
     * Default implementation for making a ShortcutInfoCompat object.  Note that this method doesn't
     * create the actual shortcut on the launcher - ShortcutManagerCompat.requestPinShortcut() must
     * be called with the ShortcutInfoCompat returned from this method to create the shortcut
     * on the launcher.
     *
     * @param name       The name of the shortcut
     * @param destIntent The destination intent
     * @param icon       Resource ID for the shortcut icon - should be black so it can be tinted and
     *                   60dp (2dp of asset padding) for high resolution on launcher screens
     * @return ShortcutInfoCompat that can be used to request pinning the shortcut
     */
    public static ShortcutInfoCompat makeShortcutInfo(Context context, String name,
            Intent destIntent, @DrawableRes int icon) {
        // Launcher shortcuts must open a fresh task rooted at the destination; without
        // CLEAR_TASK, tapping a shortcut while the app is in the background just resumes
        // the app's last screen (#1564 — supersedes the CLEAR_TOP-only flag from #626).
        destIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        destIntent.setAction(Intent.ACTION_VIEW);

        Drawable drawableIcon = ResourcesCompat
                .getDrawable(context.getResources(), icon, context.getTheme());
        drawableIcon.setColorFilter(ContextCompat.getColor(context, R.color.shortcut_icon),
                PorterDuff.Mode.SRC_IN);
        Drawable drawableBackground = ResourcesCompat
                .getDrawable(context.getResources(), R.drawable.launcher_background, context.getTheme());

        final LayerDrawable layerDrawable = new LayerDrawable(
                new Drawable[]{drawableBackground, drawableIcon});

        int backgroundInset = AndroidUtils.dpToPixels(context, 2.0f);
        layerDrawable.setLayerInset(0, backgroundInset, backgroundInset, backgroundInset, backgroundInset);
        int iconInset = AndroidUtils.dpToPixels(context, 7.0f);
        layerDrawable.setLayerInset(1, iconInset, iconInset, iconInset, iconInset);

        final Bitmap b = Bitmap
                .createBitmap(layerDrawable.getIntrinsicWidth(), layerDrawable.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(b);
        layerDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        layerDrawable.draw(canvas);

        return new ShortcutInfoCompat.Builder(context, name)
                .setShortLabel(name)
                .setIcon(IconCompat.createWithBitmap(b))
                .setIntent(destIntent)
                .build();
    }

    public static void goToUrl(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, context.getString(R.string.browser_error), Toast.LENGTH_SHORT)
                    .show();
        }
    }

    public static void goToPhoneDialer(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.setData(Uri.parse(url));
        context.startActivity(intent);
    }

    /**
     * Opens email apps based on the given email address
     * @param email address
     * @param location string that shows the current location
     */
    public static void sendEmail(Context context, String email, String location) {
        sendEmail(context, email, location, null, false);
    }

    /**
     * Opens email apps based on the given email address
     * @param email address
     * @param location string that shows the current location
     * @param tripPlanUrl trip planning URL that failed, if this is a trip problem error report, or null if it's not
     */
    public static void sendEmail(Context context, String email, String location,
            String tripPlanUrl, boolean tripPlanFail) {
        String obaRegionName = RegionUtils.getObaRegionName();
        boolean autoRegion = PreferenceUtils
                .getBoolean(context.getString(R.string.preference_key_auto_select_region), true);
        String regionSelectionMethod;
        if (autoRegion) {
            regionSelectionMethod = context.getString(R.string.region_selected_auto);
        } else {
            regionSelectionMethod = context.getString(R.string.region_selected_manually);
        }

        UIUtils.sendEmail(context, email, location, obaRegionName, regionSelectionMethod,
                tripPlanUrl, tripPlanFail);
    }

    /**
     * Opens email apps based on the given email address
     * @param email address
     * @param location string that shows the current location
     * @param regionName name of the current api region
     * @param regionSelectionMethod string that shows if the current api region selected manually or
     *                              automatically
     * @param tripPlanUrl trip planning URL that failed, if this is a trip problem error report, or null if it's not
     */
    private static void sendEmail(Context context, String email, String location, String regionName,
            String regionSelectionMethod, String tripPlanUrl, boolean tripPlanFail) {
        PackageManager pm = context.getPackageManager();
        PackageInfo appInfoOba;
        PackageInfo appInfoGps;
        String obaVersion = "";
        String googlePlayServicesAppVersion = "";
        try {
            appInfoOba = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_META_DATA);
            obaVersion = appInfoOba.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // Leave version as empty string
        }
        try {
            appInfoGps = pm.getPackageInfo(GoogleApiAvailability.GOOGLE_PLAY_SERVICES_PACKAGE, 0);
            googlePlayServicesAppVersion = appInfoGps.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // Leave version as empty string
        }
        String body;
        if (location != null) {
            // Have location
            if (tripPlanUrl == null) {
                // No trip plan
                body = context.getString(R.string.bug_report_body,
                        obaVersion,
                        Build.MODEL,
                        Build.VERSION.RELEASE,
                        Build.VERSION.SDK_INT,
                        googlePlayServicesAppVersion,
                        GoogleApiAvailability.GOOGLE_PLAY_SERVICES_VERSION_CODE,
                        regionName,
                        regionSelectionMethod,
                        location);
            } else {
                // Trip plan
                if (tripPlanFail) {
                    body = context.getString(R.string.bug_report_body_trip_plan_fail,
                            obaVersion,
                            Build.MODEL,
                            Build.VERSION.RELEASE,
                            Build.VERSION.SDK_INT,
                            googlePlayServicesAppVersion,
                            GoogleApiAvailability.GOOGLE_PLAY_SERVICES_VERSION_CODE,
                            regionName,
                            regionSelectionMethod,
                            location,
                            tripPlanUrl);
                } else {
                    body = context.getString(R.string.bug_report_body_trip_plan,
                            obaVersion,
                            Build.MODEL,
                            Build.VERSION.RELEASE,
                            Build.VERSION.SDK_INT,
                            googlePlayServicesAppVersion,
                            GoogleApiAvailability.GOOGLE_PLAY_SERVICES_VERSION_CODE,
                            regionName,
                            regionSelectionMethod,
                            location,
                            tripPlanUrl);
                }
            }
        } else {
            // No location
            if (tripPlanUrl == null) {
                // No trip plan
                body = context.getString(R.string.bug_report_body_without_location,
                        obaVersion,
                        Build.MODEL,
                        Build.VERSION.RELEASE,
                        Build.VERSION.SDK_INT);
            } else {
                // Trip plan
                if (tripPlanFail) {
                    body = context.getString(R.string.bug_report_body_trip_plan_without_location_fail,
                            obaVersion,
                            Build.MODEL,
                            Build.VERSION.RELEASE,
                            Build.VERSION.SDK_INT,
                            tripPlanUrl);
                } else {
                    body = context.getString(R.string.bug_report_body_trip_plan_without_location,
                            obaVersion,
                            Build.MODEL,
                            Build.VERSION.RELEASE,
                            Build.VERSION.SDK_INT,
                            tripPlanUrl);
                }
            }
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.putExtra(Intent.EXTRA_EMAIL,
                new String[]{email});
        // Show trip planner subject line if we have a trip planning URL
        String appName = context.getString(R.string.app_name);
        String subject;
        if (tripPlanUrl == null) {
            if (tripPlanFail) {
                subject = context.getString(R.string.bug_report_subject_trip_plan, appName);
            } else {
                subject = context.getString(R.string.bug_report_subject, appName);
            }
        } else {
            if (tripPlanFail) {
                subject = context.getString(R.string.bug_report_subject_trip_plan_fail, appName);
            } else {
                subject = context.getString(R.string.bug_report_subject_trip_plan, appName);
            }
        }
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.putExtra(Intent.EXTRA_TEXT, body);
        send.setType("message/rfc822");
        try {
            context.startActivity(Intent.createChooser(send, subject));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.bug_report_error, Toast.LENGTH_LONG)
                    .show();
        }
    }

    /**
     * Builds the list of Strings that should be shown for a given trip "Bus Options" menu,
     * provided the arguments for that trip
     *
     * @param c                 Context
     * @param isRouteFavorite   true if this route is a user favorite, false if it is not
     * @param hasUrl            true if the route provides a URL for schedule data, false if it does
     *                          not
     * @param isReminderVisible true if the reminder is currently visible for a trip, false if it
     *                          is
     *                          not
     * @param occupancy occupancy of this trip
     * @param occupancyState occupanceState of this trip
     * @return the list of Strings that should be shown for a given trip, provided the arguments for
     * that trip
     */
    public static List<String> buildTripOptions(Context c, boolean isRouteFavorite, boolean hasUrl,
                                                boolean isReminderVisible, boolean hasRouteFilter, Occupancy occupancy, OccupancyState occupancyState) {
        ArrayList<String> list = new ArrayList<>();
        if (!isRouteFavorite) {
            list.add(c.getString(R.string.bus_options_menu_add_star));
        } else {
            list.add(c.getString(R.string.bus_options_menu_remove_star));
        }

        list.add(c.getString(R.string.bus_options_menu_show_vehicles_on_map));
        list.add(c.getString(R.string.bus_options_menu_show_trip_details));

        if (!isReminderVisible) {
            list.add(c.getString(R.string.bus_options_menu_set_reminder));
        } else {
            list.add(c.getString(R.string.bus_options_menu_edit_reminder));
        }

        if (!hasRouteFilter) {
            list.add(c.getString(R.string.bus_options_menu_show_only_this_route));
        } else {
            list.add(c.getString(R.string.bus_options_menu_show_all_routes));
        }

        if (hasUrl) {
            list.add(c.getString(R.string.bus_options_menu_show_route_schedule));
        }

        list.add(c.getString(R.string.bus_options_menu_report_trip_problem));

        if (occupancy != null) {
            if (occupancyState == OccupancyState.HISTORICAL) {
                list.add(c.getString(R.string.menu_title_about_historical_occupancy));
            } else {
                list.add(c.getString(R.string.menu_title_about_occupancy));
            }
        }

        return list;
    }

    /**
     * Returns a list of all situations (service alerts) that are specific to the stop, routes, and
     * agency for the provided arrivals-and-departures-for-stop response.  For route-specific alerts, this
     * involves looping through the routes and checking the references element to see if there are
     * any route-specific alerts, and adding them to the list to be shown above the list of
     * arrivals for a stop.  See #700.
     *
     * @param response response from arrivals-and-departures-for-stop API
     * @param filter   list of route_ids to retrieve service alerts for, or null to retrieve service
     *                 alerts for all routes. Note that this filter only affects alerts scoped to
     *                 routes - it does not affect alerts scoped to stops or agencies
     * @return a list of all situations (service alerts) that are specific to the stop, routes, and
     * agency. If a route filter list is provided, situations for all stops and agencies are included
     * in the returned list, but only situations scoped for route_ids in the provided filter list are
     * included in the returned list (i.e., situations specified for route_ids that aren't in the
     * filter list are excluded).
     */
    public static List<ObaSituation> getAllSituations(final ObaArrivalInfoResponse response, List<String> filter) {
        List<ObaSituation> allSituations = new ArrayList<>();

        if (response == null) {
            return allSituations;
        }

        // Add agency-wide and stop-specific alerts
        allSituations.addAll(response.getSituations());

        // Add all existing Ids to a HashSet for O(1) retrieval (vs. list)
        HashSet<String> allIds = new HashSet<>();
        for (ObaSituation s : allSituations) {
            allIds.add(s.getId());
        }

        // Do the same for filtered routes
        HashSet<String> filterIds = new HashSet<>();
        if (filter != null && !filter.isEmpty()) {
            for (String routeId : filter) {
                filterIds.add(routeId);
            }
        }

        // Scan through the routes, and if a route-specific situation hasn't been added yet, add it
        // If a filter list exists and a route_id is not included in the filter list, don't included
        // it's situations in the returned list.
        ObaArrivalInfo[] info = response.getArrivalInfo();
        if (info == null) {
            return allSituations;
        }
        for (ObaArrivalInfo i : info) {
            String[] situationIds = i.getSituationIds();
            if (situationIds == null) {
                continue;
            }
            if (filterIds.isEmpty() || filterIds.contains(i.getRouteId())) {
                for (String situationId : situationIds) {
                    if (!allIds.contains(situationId)) {
                        allIds.add(situationId);
                        allSituations.add(response.getSituation(situationId));
                    }
                }
            }
        }
        return allSituations;
    }

    /**
     * Returns true if the provided currentTime falls within the situation's (i.e., alert's) active
     * windows or if the situation does not provide an active window, and false if the currentTime
     * falls outside of the situation's active windows
     *
     * @param currentTime the time to compare to the situation's windows, in milliseconds between
     *                    the current time and midnight, January 1, 1970 UTC
     * @return true if the provided currentTime falls within the situation's (i.e., alert's) active
     * windows or if the situation does not provide an active window, and false if the currentTime
     * falls outside of the situation's active windows
     */
    public static boolean isActiveWindowForSituation(ObaSituation situation, long currentTime) {
        if (situation.getActiveWindows().length == 0) {
            // We assume a situation is active if it doesn't contain any active window information
            return true;
        }
        // Active window times are in seconds or milliseconds since epoch
        long currentTimeConverted = TimeUnit.MILLISECONDS.toSeconds(currentTime);
        boolean isActiveWindowForSituation = false;
        for (ObaSituation.ActiveWindow activeWindow : situation.getActiveWindows()) {
            long from = activeWindow.getFrom();
            long to = activeWindow.getTo();

            if(!isTimestampInSeconds(from)){
                currentTimeConverted = TimeUnit.MILLISECONDS.toMillis(currentTime);
            }
            // 0 is a valid end time that means no end to the window - see #990
            if (from <= currentTimeConverted && (to == 0 || currentTimeConverted <= to)) {
                isActiveWindowForSituation = true;
                break;
            }
        }
        return isActiveWindowForSituation;
    }

    /**
     * Checks if the given timestamp is in seconds.
     *
     * @param timestamp the timestamp to check
     * @return true if the timestamp is in seconds, false if it is in milliseconds
     */
    public static boolean isTimestampInSeconds(long timestamp) {
        // Get the current time in milliseconds
        long currentTimeMillis = System.currentTimeMillis();

        // If the timestamp is smaller than the current time divided by 1000, it's likely in seconds
        return timestamp < currentTimeMillis / 1000L;
    }

    /**
     * Set smaller text size if the route short name has more than 3 characters
     *
     * @param view Text view
     * @param routeShortName Route short name
     */
    public static void maybeShrinkRouteName(Context context, TextView view, String routeShortName) {
        if (routeShortName.length() < 4) {
            // No-op if text is short enough to fit
            return;
        } else if (routeShortName.length() == 4) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().
                    getDimension(R.dimen.route_name_text_size_medium));
        } else if (routeShortName.length() > 4) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().
                    getDimension(R.dimen.route_name_text_size_small));
        }
    }

    /**
     * Begins the fare-payment flow for the currently selected region. If the region has a fare
     * payment-app warning the user hasn't opted out of, returns that region so the caller can show
     * the warning dialog and then call {@link #startPaymentIntent}; otherwise launches the payment
     * intent directly (installed app, else the Google Play listing) and returns null. Returns null
     * when there is no current region (e.g. a custom API URL is set).
     * @param activity activity to launch the fare payment app or Google Play store from
     * @return the region whose payment warning must be shown first, or null if already handled
     */
    public static ObaRegion payFareOrWarningRegion(@NonNull Activity activity) {
        ObaRegion region = Application.get().getCurrentRegion();
        if (region == null) {
            // If a custom API URL is set (i.e., no region), then no op
            return null;
        }

        boolean hasWarning = !TextUtils.isEmpty(region.getPaymentWarningTitle())
                || !TextUtils.isEmpty(region.getPaymentWarningBody());
        if (hasWarning && !PreferenceUtils.getBoolean(
                activity.getString(R.string.preference_key_never_show_payment_warning_dialog), false)) {
            // Caller shows the warning dialog, then calls startPaymentIntent on confirm.
            return region;
        }
        // No warning (or opted out) - start the Intent directly.
        startPaymentIntent(activity, region);
        return null;
    }

    /**
     * Launches the payment app for the provided region if it's already installed, and if not
     * directs the user to the listing in Google Play where it can be downloaded
     * @param activity Activity to use to launch the Intent
     * @param region region to launch a payment Intent for
     */
    public static void startPaymentIntent(@NonNull Activity activity, @NonNull ObaRegion region) {
        PackageManager manager = activity.getPackageManager();
        Intent intent = manager.getLaunchIntentForPackage(region.getPaymentAndroidAppId());
        if (intent != null) {
            // Launch installed app
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            activity.startActivity(intent);
            ObaAnalytics.reportUiEvent(
                    FirebaseAnalytics.getInstance(activity),
                    Application.get().getPlausibleInstance(),
                    PlausibleAnalytics.REPORT_FARE_PAYMENT_EVENT_URL,
                    Application.get().getString(R.string.analytics_label_button_fare_payment),
                    Application.get().getString(R.string.analytics_label_open_app));
        } else {
            // Go to Play Store listing to download app
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(Application.get().getString(R.string.google_play_listing_prefix, region.getPaymentAndroidAppId())));
            activity.startActivity(intent);
            ObaAnalytics.reportUiEvent(FirebaseAnalytics.getInstance(activity),
                    Application.get().getPlausibleInstance(),
                    PlausibleAnalytics.REPORT_FARE_PAYMENT_EVENT_URL,
                    Application.get().getString(R.string.analytics_label_button_fare_payment),
                    Application.get().getString(R.string.analytics_label_download_app));
        }
    }

    /**
     * Launches the HOPR bikeshare app for Tampa if the app is installed, otherwise directs the user
     * to the Google Play store listing to download it.
     *
     * @param context context to launch the fare payment app or Google Play store from
     */
    public static void launchTampaHoprApp(@NonNull Context context) {
        PackageManager manager = context.getPackageManager();
        Intent intent = manager.getLaunchIntentForPackage(context.getString(R.string.hopr_android_app_id));
        if (intent != null) {
            // Launch installed app
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            context.startActivity(intent);
            ObaAnalytics.reportUiEvent(FirebaseAnalytics.getInstance(context),
                    Application.get().getPlausibleInstance(),
                    PlausibleAnalytics.REPORT_FARE_PAYMENT_EVENT_URL,
                    Application.get().getString(R.string.analytics_label_button_bike_share),
                    Application.get().getString(R.string.analytics_label_open_app));
        } else {
            // Go to Play Store listing to download app
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(Application.get().getString(R.string.google_play_listing_prefix, context.getString(R.string.hopr_android_app_id))));
            context.startActivity(intent);
            ObaAnalytics.reportUiEvent(FirebaseAnalytics.getInstance(context),
                    Application.get().getPlausibleInstance(),
                    PlausibleAnalytics.REPORT_FARE_PAYMENT_EVENT_URL,
                    Application.get().getString(R.string.analytics_label_button_bike_share),
                    Application.get().getString(R.string.analytics_label_download_app));
        }
    }

    public static void setAppTheme(String themeValue) {
        if (themeValue.equalsIgnoreCase(Application.get().getString(R.string.preferences_app_theme_option_system_default))) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
        if (themeValue.equalsIgnoreCase(Application.get().getString(R.string.preferences_app_theme_option_dark))) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
        if (themeValue.equalsIgnoreCase(Application.get().getString(R.string.preferences_app_theme_option_light))) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }

}
