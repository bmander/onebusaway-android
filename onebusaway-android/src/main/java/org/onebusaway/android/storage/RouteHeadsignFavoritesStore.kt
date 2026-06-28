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
package org.onebusaway.android.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.provider.ObaContract

/**
 * Persistence seam for route/headsign favorites (a route is favorited for a specific headsign at a
 * specific stop, or for all stops). One of the storage-modernization store interfaces moving
 * `ObaContract` calls out of feature code; provider-backed for now (Slice 0). The synchronous
 * per-row `isFavorite` read on the arrivals hot path is migrated at the Slice 3 cutover.
 */
interface RouteHeadsignFavoritesStore {

    /**
     * Marks (or unmarks) a route/headsign/stop combination as a favorite, also reconciling the
     * route's overall favorite flag. [stopId] null means "all stops".
     */
    suspend fun setFavorite(
        routeId: String,
        headsign: String?,
        stopId: String?,
        favorite: Boolean
    )
}

/** Provider-backed [RouteHeadsignFavoritesStore] delegating to [ObaContract.RouteHeadsignFavorites]. */
class ProviderRouteHeadsignFavoritesStore @Inject constructor(
    @ApplicationContext private val context: Context
) : RouteHeadsignFavoritesStore {

    override suspend fun setFavorite(
        routeId: String,
        headsign: String?,
        stopId: String?,
        favorite: Boolean
    ) = withContext(Dispatchers.IO) {
        ObaContract.RouteHeadsignFavorites.markAsFavorite(context, routeId, headsign, stopId, favorite)
        Unit
    }
}
