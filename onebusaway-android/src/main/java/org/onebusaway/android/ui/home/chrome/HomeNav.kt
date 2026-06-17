/*
 * Copyright (C) 2026 Open Transit Software Foundation
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
package org.onebusaway.android.ui.home.chrome

import androidx.annotation.StringRes
import org.onebusaway.android.R
import org.onebusaway.android.ui.home.HomeNavItem

/**
 * The nav-drawer *policy*, split out of HomeActivity so it's pure + unit-testable: which items the
 * region-gated drawer shows, and each item's analytics label. The actual `startActivity` dispatch
 * stays in the Activity — that's its job as the navigation host — but the gating + the labels (the
 * easy-to-get-wrong parts) live here.
 */

/**
 * The region-gated drawer item list (ported from HomeActivity.refreshDrawerItems). Trip-planning and
 * fare payment only appear when the region supports them; reminders only when enabled.
 */
fun homeNavItems(
    showReminders: Boolean,
    planTripAvailable: Boolean,
    payFareAvailable: Boolean,
): List<HomeNavItem> = buildList {
    add(HomeNavItem.NEARBY)
    add(HomeNavItem.STARRED_STOPS)
    add(HomeNavItem.STARRED_ROUTES)
    if (showReminders) {
        add(HomeNavItem.MY_REMINDERS)
    }
    if (planTripAvailable) {
        add(HomeNavItem.PLAN_TRIP)
    }
    if (payFareAvailable) {
        add(HomeNavItem.PAY_FARE)
    }
    add(HomeNavItem.OPEN_SOURCE)
    add(HomeNavItem.SETTINGS)
    add(HomeNavItem.HELP)
    add(HomeNavItem.SEND_FEEDBACK)
}

/**
 * The analytics label for a drawer-item press, or null if none is reported (PAY_FARE intentionally
 * reports nothing — ported from HomeActivity.reportNavAnalytics).
 */
@get:StringRes
val HomeNavItem.analyticsLabelRes: Int?
    get() = when (this) {
        HomeNavItem.NEARBY -> R.string.analytics_label_button_press_nearby
        HomeNavItem.STARRED_STOPS, HomeNavItem.STARRED_ROUTES ->
            R.string.analytics_label_button_press_star
        HomeNavItem.MY_REMINDERS -> R.string.analytics_label_button_press_reminders
        HomeNavItem.PLAN_TRIP -> R.string.analytics_label_button_press_trip_plan
        HomeNavItem.SETTINGS -> R.string.analytics_label_button_press_settings
        HomeNavItem.HELP -> R.string.analytics_label_button_press_help
        HomeNavItem.SEND_FEEDBACK -> R.string.analytics_label_button_press_feedback
        HomeNavItem.OPEN_SOURCE -> R.string.analytics_label_button_press_open_source
        HomeNavItem.PAY_FARE -> null
    }
