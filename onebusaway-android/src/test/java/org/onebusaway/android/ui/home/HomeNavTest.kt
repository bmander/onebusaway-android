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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onebusaway.android.ui.home.chrome.analyticsLabelRes
import org.onebusaway.android.ui.home.chrome.homeNavItems

/**
 * Unit tests for the nav-drawer policy ([homeNavItems] + [analyticsLabelRes]) extracted from
 * HomeActivity — the region/feature gating + the per-item analytics labels.
 */
class HomeNavTest {

    @Test
    fun `the base items are always present, in order`() {
        val items = homeNavItems(showReminders = false, planTripAvailable = false, payFareAvailable = false)
        assertEquals(
            listOf(
                HomeNavItem.NEARBY,
                HomeNavItem.STARRED_STOPS,
                HomeNavItem.STARRED_ROUTES,
                HomeNavItem.OPEN_SOURCE,
                HomeNavItem.SETTINGS,
                HomeNavItem.HELP,
                HomeNavItem.SEND_FEEDBACK,
            ),
            items,
        )
    }

    @Test
    fun `reminders, trip-planning and fare payment are gated in`() {
        val items = homeNavItems(showReminders = true, planTripAvailable = true, payFareAvailable = true)
        assertTrue(items.contains(HomeNavItem.MY_REMINDERS))
        assertTrue(items.contains(HomeNavItem.PLAN_TRIP))
        assertTrue(items.contains(HomeNavItem.PAY_FARE))
        // MY_REMINDERS sits after the starred tabs; PLAN_TRIP/PAY_FARE before the trailing menu items.
        assertTrue(items.indexOf(HomeNavItem.MY_REMINDERS) < items.indexOf(HomeNavItem.PLAN_TRIP))
        assertTrue(items.indexOf(HomeNavItem.PAY_FARE) < items.indexOf(HomeNavItem.OPEN_SOURCE))
    }

    @Test
    fun `gated items are absent when unavailable`() {
        val items = homeNavItems(showReminders = false, planTripAvailable = true, payFareAvailable = false)
        assertTrue(items.contains(HomeNavItem.PLAN_TRIP))
        assertTrue(!items.contains(HomeNavItem.MY_REMINDERS))
        assertTrue(!items.contains(HomeNavItem.PAY_FARE))
    }

    @Test
    fun `pay-fare reports no analytics label, others do`() {
        assertNull(HomeNavItem.PAY_FARE.analyticsLabelRes)
        HomeNavItem.entries.filter { it != HomeNavItem.PAY_FARE }.forEach {
            assertTrue("$it should have an analytics label", it.analyticsLabelRes != null)
        }
    }
}
