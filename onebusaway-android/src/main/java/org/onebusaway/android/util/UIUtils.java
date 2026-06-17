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

import android.content.Context;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;
import androidx.core.util.Pair;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.onebusaway.android.R;

import java.util.ArrayList;
import java.util.List;

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

}
