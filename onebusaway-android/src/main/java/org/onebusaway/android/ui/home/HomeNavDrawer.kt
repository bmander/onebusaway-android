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
package org.onebusaway.android.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onebusaway.android.R

/**
 * Compose `ModalNavigationDrawer` content replacing `NavigationDrawerFragment` + the navdrawer_*
 * XML. [items] is the already-region-gated, ordered item list (the host computes it from the region,
 * mirroring `populateNavDrawer()`); dividers are inserted before the Open-Source and Settings groups.
 */
@Composable
fun HomeNavDrawerSheet(
    items: List<HomeNavItem>,
    selected: HomeNavItem,
    onSelect: (HomeNavItem) -> Unit
) {
    // Match the legacy drawer width; the Material3 default (360dp) is noticeably wider.
    ModalDrawerSheet(Modifier.width(dimensionResource(R.dimen.navigation_drawer_width))) {
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            items.forEach { item ->
                if (item == HomeNavItem.OPEN_SOURCE || item == HomeNavItem.SETTINGS) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                NavigationDrawerItem(
                    label = { Text(stringResource(item.titleRes())) },
                    selected = !item.launchesActivity && item == selected,
                    icon = item.iconRes()?.let { res ->
                        // Pin to the standard 24dp; some drawer drawables are hi-res PNGs whose
                        // intrinsic size would otherwise render oversized.
                        { Icon(painterResource(res), contentDescription = null, modifier = Modifier.size(24.dp)) }
                    },
                    onClick = { onSelect(item) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    }
}

private fun HomeNavItem.titleRes(): Int = when (this) {
    HomeNavItem.NEARBY -> R.string.navdrawer_item_nearby
    HomeNavItem.STARRED_STOPS -> R.string.navdrawer_item_starred_stops
    HomeNavItem.STARRED_ROUTES -> R.string.navdrawer_item_starred_routes
    HomeNavItem.MY_REMINDERS -> R.string.navdrawer_item_my_reminders
    HomeNavItem.PLAN_TRIP -> R.string.navdrawer_item_plan_trip
    HomeNavItem.PAY_FARE -> R.string.navdrawer_item_pay_fare
    HomeNavItem.SETTINGS -> R.string.navdrawer_item_settings
    HomeNavItem.HELP -> R.string.navdrawer_item_help
    HomeNavItem.SEND_FEEDBACK -> R.string.navdrawer_item_send_feedback
    HomeNavItem.OPEN_SOURCE -> R.string.navdrawer_item_open_source
}

private fun HomeNavItem.iconRes(): Int? = when (this) {
    HomeNavItem.NEARBY -> R.drawable.ic_drawer_maps_place
    HomeNavItem.STARRED_STOPS -> R.drawable.ic_stop_flag_triangle
    HomeNavItem.STARRED_ROUTES -> R.drawable.ic_bus
    HomeNavItem.MY_REMINDERS -> R.drawable.ic_drawer_alarm
    HomeNavItem.PLAN_TRIP -> R.drawable.ic_maps_directions
    HomeNavItem.PAY_FARE -> R.drawable.ic_payment
    HomeNavItem.OPEN_SOURCE -> R.drawable.ic_drawer_github
    HomeNavItem.SETTINGS, HomeNavItem.HELP, HomeNavItem.SEND_FEEDBACK -> null
}
