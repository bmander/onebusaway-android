/*
 * Copyright (C) 2015-2026 University of South Florida (sjbarbeau@gmail.com),
 * Open Transit Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.android.ui

import android.content.ContentValues
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.loader.app.LoaderManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.onebusaway.android.R
import org.onebusaway.android.provider.ObaContract

// Selections need to match strings.xml "route_favorite_options"
private const val SELECTION_THIS_STOP = 0

private const val ROUTE_INFO_LOADER = 0

/**
 * Asks the user if they want to save (or remove) a route/headsign favorite for all stops, or just
 * this stop, then updates the database with their choice. [onSelectionComplete] receives true when
 * a choice was saved, false if the dialog was cancelled.
 */
fun showRouteFavoriteDialog(
    activity: FragmentActivity,
    routeId: String,
    routeShortName: String?,
    routeLongName: String?,
    headsign: String?,
    stopId: String?,
    favorite: Boolean,
    onSelectionComplete: (savedFavorite: Boolean) -> Unit
) {
    val routeUri = Uri.withAppendedPath(ObaContract.Routes.CONTENT_URI, routeId)
    val values = ContentValues().apply {
        put(ObaContract.Routes.SHORTNAME, routeShortName)
        put(ObaContract.Routes.LONGNAME, routeLongName)
    }
    val routeTitle = buildRouteTitle(routeShortName, headsign)
    val title = activity.getString(
        if (favorite) R.string.route_favorite_options_title_star
        else R.string.route_favorite_options_title_unstar,
        routeTitle
    )
    var selected = SELECTION_THIS_STOP
    MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setCancelable(false)
        .setSingleChoiceItems(R.array.route_favorite_options, selected) { _, which ->
            selected = which
        }
        .setPositiveButton(R.string.stop_info_save) { _, _ ->
            // "All stops" saves the favorite with a null stopId
            val selectedStopId = if (selected == SELECTION_THIS_STOP) stopId else null
            QueryUtils.setFavoriteRouteAndHeadsign(
                activity, routeUri, headsign, selectedStopId, values, favorite
            )
            // Request the full details of the starred route, so the long name can be shown later
            LoaderManager.getInstance(activity).restartLoader(
                ROUTE_INFO_LOADER, null, QueryUtils.RouteLoaderCallback(activity, routeId)
            )
            onSelectionComplete(true)
        }
        .setNegativeButton(R.string.stop_info_cancel) { _, _ ->
            // Nothing changed
            onSelectionComplete(false)
        }
        .show()
}

/** The route short name and headsign, each truncated with an ellipsis, for the dialog title. */
private fun buildRouteTitle(routeShortName: String?, headsign: String?): String = buildString {
    if (!routeShortName.isNullOrEmpty()) {
        if (routeShortName.length > 3) {
            append(routeShortName.substring(0, 3)).append("...")
        } else {
            append(routeShortName)
        }
        append(" - ")
    }
    if (!headsign.isNullOrEmpty()) {
        if (headsign.length > 8) {
            append(headsign.substring(0, 8)).append("...")
        } else {
            append(headsign)
        }
    }
}
