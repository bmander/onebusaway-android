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
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.location.Location;
import android.media.ExifInterface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
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
import org.onebusaway.android.map.MapParams;
import org.onebusaway.android.provider.ObaContract;
import org.onebusaway.android.ui.arrivals.ArrivalsListLauncher;
import org.onebusaway.android.ui.routeinfo.RouteInfoLauncher;
import org.onebusaway.util.comparators.AlphanumComparator;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A class containing utility methods related to the user interface
 */
public final class UIUtils {

    public static int getStopDirectionText(String direction) {
        if (direction.equals("N")) {
            return R.string.direction_n;
        } else if (direction.equals("NW")) {
            return R.string.direction_nw;
        } else if (direction.equals("W")) {
            return R.string.direction_w;
        } else if (direction.equals("SW")) {
            return R.string.direction_sw;
        } else if (direction.equals("S")) {
            return R.string.direction_s;
        } else if (direction.equals("SE")) {
            return R.string.direction_se;
        } else if (direction.equals("E")) {
            return R.string.direction_e;
        } else if (direction.equals("NE")) {
            return R.string.direction_ne;
        } else {
            return R.string.direction_none;
        }
    }

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
                    .append(context.getString(UIUtils.getStopDirectionText(stopDirection)));
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

        int backgroundInset = UIUtils.dpToPixels(context, 2.0f);
        layerDrawable.setLayerInset(0, backgroundInset, backgroundInset, backgroundInset, backgroundInset);
        int iconInset = UIUtils.dpToPixels(context, 7.0f);
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

    public static String getRouteErrorString(Context context, int code) {
        if (!isConnected(context)) {
            if (isAirplaneMode(context)) {
                return context.getString(R.string.airplane_mode_error);
            } else {
                return context.getString(R.string.no_network_error);
            }
        }
        switch (code) {
            case ObaApi.OBA_INTERNAL_ERROR:
                return context.getString(R.string.internal_error);
            case ObaApi.OBA_NOT_FOUND:
                ObaRegion r = Application.get().getCurrentRegion();
                if (r != null) {
                    return context.getString(R.string.route_not_found_error_with_region_name,
                            r.getName());
                } else {
                    return context.getString(R.string.route_not_found_error_no_region);
                }
            case ObaApi.OBA_BAD_GATEWAY:
                return context.getString(R.string.bad_gateway_error, context.getString(R.string.app_name));
            case ObaApi.OBA_OUT_OF_MEMORY:
                return context.getString(R.string.out_of_memory_error);
            default:
                return context.getString(R.string.generic_comm_error);
        }
    }

    public static String getStopErrorString(Context context, int code) {
        if (!isConnected(context)) {
            if (isAirplaneMode(context)) {
                return context.getString(R.string.airplane_mode_error);
            } else {
                return context.getString(R.string.no_network_error);
            }
        }
        switch (code) {
            case ObaApi.OBA_INTERNAL_ERROR:
                return context.getString(R.string.internal_error);
            case ObaApi.OBA_NOT_FOUND:
                ObaRegion r = Application.get().getCurrentRegion();
                if (r != null) {
                    return context
                            .getString(R.string.stop_not_found_error_with_region_name, r.getName());
                } else {
                    return context.getString(R.string.stop_not_found_error_no_region);
                }
            case ObaApi.OBA_BAD_GATEWAY:
                return context.getString(R.string.bad_gateway_error, context.getString(R.string.app_name));
            case ObaApi.OBA_OUT_OF_MEMORY:
                return context.getString(R.string.out_of_memory_error);
            default:
                return context.getString(R.string.generic_comm_error);
        }
    }

    /**
     * Returns the resource ID for a user-friendly error message based on device state (if a
     * network
     * connection is available or airplane mode is on) or an OBA REST API response code
     *
     * @param code The status code (one of the ObaApi.OBA_* constants)
     * @return the resource ID for a user-friendly error message based on device state (if a network
     * connection is available or airplane mode is on) or an OBA REST API response code
     */
    public static int getMapErrorString(Context context, int code) {
        if (!isConnected(context)) {
            if (isAirplaneMode(context)) {
                return R.string.airplane_mode_error;
            } else {
                return R.string.no_network_error;
            }
        }
        switch (code) {
            case ObaApi.OBA_INTERNAL_ERROR:
                return R.string.internal_error;
            case ObaApi.OBA_BAD_GATEWAY:
                return R.string.bad_gateway_error;
            case ObaApi.OBA_OUT_OF_MEMORY:
                return R.string.out_of_memory_error;
            default:
                return R.string.map_generic_error;
        }
    }

    /**
     * Returns true if the device is in Airplane Mode, and false if the device isn't in Airplane
     * mode or if it can't be determined
     * @param context
     * @return true if the device is in Airplane Mode, and false if the device isn't in Airplane
     * mode or if it can't be determined
     */
    public static boolean isAirplaneMode(Context context) {
        if (context == null) {
            // If the context is null, we can't get airplane mode state - assume no
            return false;
        }
        ContentResolver cr = context.getContentResolver();
        return Settings.System.getInt(cr, Settings.System.AIRPLANE_MODE_ON, 0) != 0;
    }

    /**
     * Returns true if the device is connected to a network, and false if the device isn't or if it
     * can't be determined
     * @param context
     * @return true if the device is connected to a network, and false if the device isn't or if it
     * can't be determined
     */
    public static boolean isConnected(Context context) {
        if (context == null) {
            // If the context is null, we can't get connected state - assume yes
            return true;
        }
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return (activeNetwork != null) && activeNetwork.isConnectedOrConnecting();
    }

    /**
     * Returns the first string for the query URI.
     */
    public static String stringForQuery(Context context, Uri uri, String column) {
        ContentResolver cr = context.getContentResolver();
        Cursor c = cr.query(uri, new String[]{column}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    return c.getString(0);
                }
            } finally {
                c.close();
            }
        }
        return "";
    }

    public static final int MINUTES_IN_HOUR = 60;

    /**
     * Takes the number of minutes, and returns a user-readable string
     * saying the number of minutes in which no arrivals are coming,
     * or the number of hours and minutes if minutes if minutes > 60
     *
     * @param minutes            number of minutes for which there are no upcoming arrivals
     * @param additionalArrivals true if the response should include the word additional, false if
     *                           it should not
     * @param shortFormat        true if the format should be abbreviated, false if it should be
     *                           long
     * @return a user-readable string saying the number of minutes in which no arrivals are coming,
     * or the number of hours and minutes if minutes > 60
     */
    public static String getNoArrivalsMessage(Context context, int minutes,
            boolean additionalArrivals, boolean shortFormat) {
        if (minutes <= MINUTES_IN_HOUR) {
            // Return just minutes
            if (additionalArrivals) {
                if (shortFormat) {
                    // Abbreviated version
                    return context
                            .getString(R.string.stop_info_no_additional_data_minutes_short_format,
                                    minutes);
                } else {
                    // Long version
                    return context
                            .getString(R.string.stop_info_no_additional_data_minutes, minutes);
                }
            } else {
                if (shortFormat) {
                    // Abbreviated version
                    return context
                            .getString(R.string.stop_info_nodata_minutes_short_format, minutes);
                } else {
                    // Long version
                    return context.getString(R.string.stop_info_nodata_minutes, minutes);
                }
            }
        } else {
            // Return hours and minutes
            if (additionalArrivals) {
                if (shortFormat) {
                    // Abbreviated version
                    return context.getResources()
                            .getQuantityString(
                                    R.plurals.stop_info_no_additional_data_hours_minutes_short_format,
                                    minutes / 60, minutes % 60, minutes / 60);
                } else {
                    // Long version
                    return context.getResources()
                            .getQuantityString(R.plurals.stop_info_no_additional_data_hours_minutes,
                                    minutes / 60, minutes % 60, minutes / 60);
                }
            } else {
                if (shortFormat) {
                    // Abbreviated version
                    return context.getResources()
                            .getQuantityString(
                                    R.plurals.stop_info_nodata_hours_minutes_short_format,
                                    minutes / 60,
                                    minutes % 60, minutes / 60);
                } else {
                    // Long version
                    return context.getResources()
                            .getQuantityString(R.plurals.stop_info_nodata_hours_minutes,
                                    minutes / 60,
                                    minutes % 60, minutes / 60);
                }
            }
        }
    }

    /**
     * Returns true if the activity is still active and dialogs can be managed (i.e., displayed
     * or dismissed), or false if it is
     * not
     *
     * @param activity Activity to check for displaying/dismissing a dialog
     * @return true if the activity is still active and dialogs can be managed, or false if it is
     * not
     */
    public static boolean canManageDialog(Activity activity) {
        if (activity == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return !activity.isFinishing() && !activity.isDestroyed();
        } else {
            return !activity.isFinishing();
        }
    }

    /**
     * Returns true if the context is an Activity and is still active and dialogs can be managed
     * (i.e., displayed or dismissed) OR the context is not an Activity, or false if the Activity
     * is
     * no longer active.
     *
     * NOTE: We really shouldn't display dialogs from a Service - a notification is a better way
     * to communicate with the user.
     *
     * @param context Context to check for displaying/dismissing a dialog
     * @return true if the context is an Activity and is still active and dialogs can be managed
     * (i.e., displayed or dismissed) OR the context is not an Activity, or false if the Activity
     * is
     * no longer active
     */
    public static boolean canManageDialog(Context context) {
        if (context == null) {
            return false;
        }

        if (context instanceof Activity) {
            return canManageDialog((Activity) context);
        } else {
            // We really shouldn't be displaying dialogs from a Service, but if for some reason we
            // need to do this, we don't have any way of checking whether its possible
            return true;
        }
    }

    /**
     * Returns true if the API level supports our Arrival Info Style B (sort by route) views, false
     * if it does not.  See #350 and #275.
     *
     * @return true if the API level supports our Arrival Info Style B (sort by route) views, false
     * if it does not
     */
    public static boolean canSupportArrivalInfoStyleB() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH;
    }

    /**
     * Converts screen dimension units from dp to pixels, based on algorithm defined in
     * http://developer.android.com/guide/practices/screens_support.html#dips-pels
     *
     * @param dp value in dp
     * @return value in pixels
     */
    public static int dpToPixels(Context context, float dp) {
        // Get the screen's density scale
        final float scale = context.getResources().getDisplayMetrics().density;
        // Convert the dps to pixels, based on density scale
        return (int) (dp * scale + 0.5f);
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
     * Creates a new Bitmap, with the black color of the source image changed to the given color.
     * The source Bitmap isn't modified.
     *
     * @param source the source Bitmap with a black background
     * @param color  the color to change the black color to
     * @return the resulting Bitmap that has the black changed to the color
     */
    public static Bitmap colorBitmap(Bitmap source, int color) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int x = 0; x < pixels.length; ++x) {
            pixels[x] = (pixels[x] == Color.BLACK) ? color : pixels[x];
        }

        Bitmap out = Bitmap.createBitmap(width, height, source.getConfig());
        out.setPixels(pixels, 0, width, 0, 0, width, height);
        return out;
    }

    /**
     * Returns the current time for comparison against another current time.  For API levels >=
     * Jelly Bean MR1 the SystemClock.getElapsedRealtimeNanos() method is used, and for API levels
     * <
     * Jelly Bean MR1 System.currentTimeMillis() is used.
     *
     * @return the current time for comparison against another current time, in nanoseconds
     */
    public static long getCurrentTimeForComparison() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // Use elapsed real-time nanos, since its guaranteed monotonic
            return SystemClock.elapsedRealtimeNanos();
        } else {
            return TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
        }
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
     * Returns the time formatting as "1:10pm" to be displayed as an absolute time for an
     * arrival/departure
     *
     * @param time an arrival or departure time (e.g., from ArrivalInfo)
     * @return the time formatting as "1:10pm" to be displayed as an absolute time for an
     * arrival/departure
     */
    public static String formatTime(Context context, long time) {
        return DateUtils.formatDateTime(context,
                time,
                DateUtils.FORMAT_SHOW_TIME |
                        DateUtils.FORMAT_NO_NOON |
                        DateUtils.FORMAT_NO_MIDNIGHT
        );
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
     * Transforms a given opaque color into the same color but with the given alpha value
     *
     * @param solidColor hex color value that is completely opaque
     * @param alpha      Specify an alpha value. 0 means fully transparent, and 255 means fully
     *                   opaque.
     * @return the provided color with the given alpha value
     */
    public static int getTransparentColor(int solidColor, int alpha) {
        int r = Color.red(solidColor);
        int g = Color.green(solidColor);
        int b = Color.blue(solidColor);
        return Color.argb(alpha, r, g, b);
    }

    /**
     * Returns the location of the map center if it has been previously saved in the bundle, or
     * null if it wasn't saved in the bundle.
     *
     * @param b bundle to check for the map center
     * @return the location of the map center if it has been previously saved in the bundle, or null
     * if it wasn't saved in the bundle.
     */
    public static Location getMapCenter(Bundle b) {
        if (b == null) {
            return null;
        }
        Location center = null;
        double lat = b.getDouble(MapParams.CENTER_LAT);
        double lon = b.getDouble(MapParams.CENTER_LON);

        if (lat != 0.0 && lon != 0.0) {
            center = LocationUtils.makeLocation(lat, lon);
        }
        return center;
    }

    /**
     * Creates a JPEG image file with the current date/time as the name
     *
     * @param nameSuffix A string that will be added to the end of the file name, or null if
     *                   nothing
     *                   should be added
     * @return a JPEG image file with the current date/time as the name
     */
    public static File createImageFile(Context context, String nameSuffix) throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        StringBuilder imageFileName = new StringBuilder();
        imageFileName.append("JPEG_");
        imageFileName.append(timeStamp);
        imageFileName.append("_");
        if (nameSuffix != null) {
            imageFileName.append(nameSuffix);
        }
        File storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile(
                imageFileName.toString(),  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );
    }

    /**
     * Decode a smaller sampled bitmap given a large bitmap.
     * Adapted from https://developer.android.com/training/displaying-bitmaps/load-bitmap.html and
     * http://stackoverflow.com/a/31720143/937715.
     *
     * @param pathName  path to the full size image file
     * @param reqWidth  desired width
     * @param reqHeight desired height
     * @return a smaller version of the image at pathName, given the desired width and height
     */
    public static Bitmap decodeSampledBitmapFromFile(String pathName, int reqWidth, int reqHeight)
            throws IOException {
        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(pathName, options);

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;

        Bitmap b = BitmapFactory.decodeFile(pathName, options);
        return rotateImageIfRequired(b, pathName);
    }

    /**
     * Calculate an inSampleSize for use in a {@link BitmapFactory.Options} object when decoding
     * bitmaps using the decode* methods from {@link BitmapFactory}. This implementation calculates
     * the closest inSampleSize that will result in the final decoded bitmap having a width and
     * height equal to or larger than the requested width and height. This implementation does not
     * ensure a power of 2 is returned for inSampleSize which can be faster when decoding but
     * results in a larger bitmap which isn't as useful for caching purposes.
     *
     * From http://stackoverflow.com/a/31720143/937715.
     *
     * @param options   An options object with out* params already populated (run through a decode*
     *                  method with inJustDecodeBounds==true
     * @param reqWidth  The requested width of the resulting bitmap
     * @param reqHeight The requested height of the resulting bitmap
     * @return The value to be used for inSampleSize
     */
    private static int calculateInSampleSize(BitmapFactory.Options options,
            int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            // Calculate ratios of height and width to requested height and width
            final int heightRatio = Math.round((float) height / (float) reqHeight);
            final int widthRatio = Math.round((float) width / (float) reqWidth);

            // Choose the smallest ratio as inSampleSize value, this will guarantee a final image
            // with both dimensions larger than or equal to the requested height and width.
            inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;

            // This offers some additional logic in case the image has a strange
            // aspect ratio. For example, a panorama may have a much larger
            // width than height. In these cases the total pixels might still
            // end up being too large to fit comfortably in memory, so we should
            // be more aggressive with sample down the image (=larger inSampleSize).

            final float totalPixels = width * height;

            // Anything more than 2x the requested pixels we'll sample down further
            final float totalReqPixelsCap = reqWidth * reqHeight * 2;

            while (totalPixels / (inSampleSize * inSampleSize) > totalReqPixelsCap) {
                inSampleSize++;
            }
        }
        return inSampleSize;
    }

    /**
     * Rotate an image if required.
     *
     * @param img       The image bitmap
     * @param imagePath Path to image
     * @return The resulted Bitmap after manipulation
     */
    private static Bitmap rotateImageIfRequired(Bitmap img, String imagePath) throws IOException {
        ExifInterface ei = new ExifInterface(imagePath);
        int orientation = ei
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);

        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return rotateImage(img, 90);
            case ExifInterface.ORIENTATION_ROTATE_180:
                return rotateImage(img, 180);
            case ExifInterface.ORIENTATION_ROTATE_270:
                return rotateImage(img, 270);
            default:
                return img;
        }
    }

    /**
     * Rotate the given bitmap
     *
     * @param img    image to rotate
     * @param degree number of degrees to rotate, from 0-360
     * @return the provided bitmap rotated by the given number of degrees
     */
    private static Bitmap rotateImage(Bitmap img, int degree) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degree);
        Bitmap rotatedImg = Bitmap
                .createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), matrix, true);
        img.recycle();
        return rotatedImg;
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
