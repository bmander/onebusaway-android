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
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.R
import org.onebusaway.android.analytics.ObaAnalytics
import org.onebusaway.android.analytics.PlausibleAnalytics
import org.onebusaway.android.app.Application
import org.onebusaway.android.database.oba.RouteDao
import org.onebusaway.android.database.oba.RouteHeadsignFavoriteDao
import org.onebusaway.android.database.oba.RouteHeadsignFavoriteRecord
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

/**
 * Room-backed [RouteHeadsignFavoritesStore]. Faithfully mirrors the legacy markAsFavorite: favoriting
 * inserts a (route, headsign, stop|all, exclude=0) record and stars the route; unfavoriting deletes
 * the matching records, clears the route star when no non-excluded favorites remain, and — when a
 * single stop is unstarred while the whole route is starred — inserts an exclude=1 record. Also fires
 * the same bookmark analytics event.
 */
class RoomRouteHeadsignFavoritesStore @Inject constructor(
    private val dao: RouteHeadsignFavoriteDao,
    private val routeDao: RouteDao,
    @ApplicationContext private val context: Context,
) : RouteHeadsignFavoritesStore {

    override suspend fun setFavorite(
        routeId: String,
        headsign: String?,
        stopId: String?,
        favorite: Boolean
    ) {
        val stopIdInternal = stopId ?: ALL_STOPS
        if (favorite) {
            if (stopIdInternal != ALL_STOPS) {
                dao.deleteMatch(routeId, headsign, stopIdInternal)
            }
            dao.insert(
                RouteHeadsignFavoriteRecord(
                    routeId = routeId, headsign = headsign.orEmpty(), stopId = stopIdInternal, exclude = 0
                )
            )
            routeDao.setFavorite(routeId, 1)
        } else {
            dao.deleteMatch(routeId, headsign, stopIdInternal)
            if (stopIdInternal == ALL_STOPS) {
                dao.deleteForRoute(routeId, headsign)
            }
            if (!dao.routeHasFavorite(routeId)) {
                routeDao.setFavorite(routeId, 0)
            }
            // A single stop unstarred while the whole route is starred -> record an exclusion.
            if (stopId != null && isFavorite(routeId, headsign, stopId)) {
                dao.insert(
                    RouteHeadsignFavoriteRecord(
                        routeId = routeId, headsign = headsign.orEmpty(), stopId = stopIdInternal, exclude = 1
                    )
                )
            }
        }
        reportAnalytics(routeId, headsign, stopId, favorite)
    }

    /** True if (route, headsign) is favorited for [stopId] or for all stops without [stopId] excluded. */
    private suspend fun isFavorite(routeId: String, headsign: String?, stopId: String): Boolean {
        val hs = headsign ?: ""
        if (dao.isStopFavorite(routeId, hs, stopId)) return true
        if (dao.isAllStopsFavorite(routeId, hs)) return !dao.isStopExcluded(routeId, hs, stopId)
        return false
    }

    private fun reportAnalytics(routeId: String, headsign: String?, stopId: String?, favorite: Boolean) {
        val event = context.getString(
            if (favorite) R.string.analytics_label_star_route else R.string.analytics_label_unstar_route
        )
        val param = "${routeId}_$headsign for ${stopId ?: "all stops"}"
        ObaAnalytics.reportUiEvent(
            FirebaseAnalytics.getInstance(context),
            Application.get().plausibleInstance,
            PlausibleAnalytics.REPORT_BOOKMARK_EVENT_URL,
            event,
            param,
        )
    }

    private companion object {
        const val ALL_STOPS = "all"
    }
}
