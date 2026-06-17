/*
 * Copyright (C) 2013 Paul Watts (paulcwatts@gmail.com)
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
package org.onebusaway.android.ui.nav

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.onebusaway.android.ui.HomeActivity
import org.onebusaway.android.util.ShowcaseViewUtils

object NavHelp {

    //
    // Up mode. This controls whether or not the logo (Up) button
    // goes back or goes home. Activity support is required:
    // the only activity that supports it now is the ArrivalsList.
    //
    const val UP_MODE = ".UpMode"

    const val UP_MODE_BACK = "back"

    fun goUp(activity: Activity) {
        val mode = activity.intent.getStringExtra(UP_MODE)
        if (UP_MODE_BACK == mode) {
            activity.finish()
        } else {
            goHome(activity, false)
        }
    }

    /**
     * Go back to the HomeActivity
     *
     * @param showTutorial true if the welcome tutorial should be started, false if it should not
     */
    fun goHome(context: Context, showTutorial: Boolean) {
        val intent = Intent(context, HomeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (showTutorial) {
            intent.putExtra(ShowcaseViewUtils.TUTORIAL_WELCOME, true)
        }
        context.startActivity(intent)
    }
}
