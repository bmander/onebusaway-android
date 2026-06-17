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

/**
 * Small view/dialog-related Android utility helpers.
 */
object ViewUtils {

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
        return activity != null && !activity.isFinishing && !activity.isDestroyed
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
}
