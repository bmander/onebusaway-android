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
package org.onebusaway.android.ui.nav

/**
 * Central registry of Navigation-Compose route ids and nav-arg keys (Campaign C). The single
 * NavHost backbone lives in [org.onebusaway.android.ui.HomeActivity]; each screen converted from an
 * Activity to a destination adds its route here.
 *
 * Nav-arg keys deliberately reuse the existing launch-extra constant names (e.g. `MapParams.*`,
 * `ArrivalsIntents.*`) verbatim so the deep-link / shortcut / FCM contracts are preserved when a
 * thin exported entry-point activity translates its intent into one of these routes.
 *
 * C0 introduces only the start destination; C1+ append the converted screens.
 */
object NavRoutes {

    /** The map-centric home screen — the NavHost start destination. */
    const val HOME = "home"
}
