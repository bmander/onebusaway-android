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

package org.onebusaway.android.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import org.onebusaway.android.map.MapParams

/**
 * A class containing small, mostly-pure Android utility helpers.
 */
object AndroidUtils {

    /**
     * Converts screen dimension units from dp to pixels, based on algorithm defined in
     * http://developer.android.com/guide/practices/screens_support.html#dips-pels
     *
     * @param dp value in dp
     * @return value in pixels
     */
    @JvmStatic
    fun dpToPixels(context: Context, dp: Float): Int {
        // Get the screen's density scale
        val scale = context.resources.displayMetrics.density
        // Convert the dps to pixels, based on density scale
        return (dp * scale + 0.5f).toInt()
    }

    /**
     * Transforms a given opaque color into the same color but with the given alpha value
     *
     * @param solidColor hex color value that is completely opaque
     * @param alpha      Specify an alpha value. 0 means fully transparent, and 255 means fully
     *                   opaque.
     * @return the provided color with the given alpha value
     */
    @JvmStatic
    fun getTransparentColor(solidColor: Int, alpha: Int): Int {
        val r = Color.red(solidColor)
        val g = Color.green(solidColor)
        val b = Color.blue(solidColor)
        return Color.argb(alpha, r, g, b)
    }

    /**
     * Returns the current time for comparison against another current time, using
     * SystemClock.elapsedRealtimeNanos() since it's guaranteed monotonic.
     *
     * @return the current time for comparison against another current time, in nanoseconds
     */
    @JvmStatic
    fun getCurrentTimeForComparison(): Long {
        // Use elapsed real-time nanos, since its guaranteed monotonic
        return SystemClock.elapsedRealtimeNanos()
    }

    /**
     * Returns the location of the map center if it has been previously saved in the bundle, or
     * null if it wasn't saved in the bundle.
     *
     * @param b bundle to check for the map center
     * @return the location of the map center if it has been previously saved in the bundle, or null
     * if it wasn't saved in the bundle.
     */
    @JvmStatic
    fun getMapCenter(b: Bundle?): Location? {
        if (b == null) {
            return null
        }
        var center: Location? = null
        val lat = b.getDouble(MapParams.CENTER_LAT)
        val lon = b.getDouble(MapParams.CENTER_LON)

        if (lat != 0.0 && lon != 0.0) {
            center = LocationUtils.makeLocation(lat, lon)
        }
        return center
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
    @JvmStatic
    fun canManageDialog(activity: Activity?): Boolean {
        if (activity == null) {
            return false
        }

        return !activity.isFinishing && !activity.isDestroyed
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
    @JvmStatic
    fun canManageDialog(context: Context?): Boolean {
        if (context == null) {
            return false
        }

        return if (context is Activity) {
            canManageDialog(context)
        } else {
            // We really shouldn't be displaying dialogs from a Service, but if for some reason we
            // need to do this, we don't have any way of checking whether its possible
            true
        }
    }

    /**
     * Returns the first string for the query URI.
     */
    @JvmStatic
    fun stringForQuery(context: Context, uri: Uri, column: String): String {
        val cr = context.contentResolver
        val c = cr.query(uri, arrayOf(column), null, null, null)
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    return c.getString(0)
                }
            } finally {
                c.close()
            }
        }
        return ""
    }
}
