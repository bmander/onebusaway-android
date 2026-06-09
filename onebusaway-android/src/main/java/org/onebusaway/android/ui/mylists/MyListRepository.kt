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
package org.onebusaway.android.ui.mylists

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.onebusaway.android.app.Application
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.ui.QueryUtils
import org.onebusaway.android.util.UIUtils

/**
 * A My-tab list backed by the content provider: [observe] re-queries whenever the underlying table
 * changes (the legacy `ContentObserver` behavior), plus per-item [remove] and [clearAll]. All
 * ContentResolver/cursor work is quarantined on [Dispatchers.IO] so [MyListViewModel] stays
 * Context-free and JVM-testable.
 */
interface MyListRepository<T> {
    fun observe(): Flow<List<T>>
    suspend fun remove(id: String)
    suspend fun clearAll()
}

/** Emits once immediately, then again whenever [uri] (and its descendants) change. */
private fun Context.contentChanges(uri: Uri): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    contentResolver.registerContentObserver(uri, true, observer)
    trySend(Unit)
    awaitClose { contentResolver.unregisterContentObserver(observer) }
}

/** "Recently used" = accessed within the last 7 days, or used at least once; newest first, capped at 20. */
private const val RECENT_LIMIT = "20"
private val RECENT_WINDOW_MS = 7 * DateUtils.DAY_IN_MILLIS

/** The recent-list WHERE clause (port of [QueryUtils.newRecentQuery]); [cutoffMs] is the window start. */
private fun recentSelection(accessTime: String, useCount: String, cutoffMs: Long, regionWhere: String) =
    "(($accessTime IS NOT NULL AND $accessTime > $cutoffMs) OR ($useCount > 0))$regionWhere"

private fun regionWhere(regionField: String): String {
    val region = Application.get().currentRegion ?: return ""
    return " AND " + QueryUtils.getRegionWhere(regionField, region.id)
}

private val STOP_PROJECTION = arrayOf(
    ObaContract.Stops._ID,
    ObaContract.Stops.UI_NAME,
    ObaContract.Stops.DIRECTION,
    ObaContract.Stops.LATITUDE,
    ObaContract.Stops.LONGITUDE,
    ObaContract.Stops.FAVORITE
)

private fun Cursor.toStopItem(context: Context): StopListItem {
    val rawDirection = getString(2)
    val directionText = rawDirection?.takeIf { it.isNotEmpty() }
        ?.let { context.getString(UIUtils.getStopDirectionText(it)) }
        ?.takeIf { it.isNotEmpty() }
    return StopListItem(
        id = getString(0),
        name = getString(1).orEmpty(),
        rawDirection = rawDirection,
        directionText = directionText,
        lat = getDouble(3),
        lon = getDouble(4),
        isFavorite = getInt(5) == 1
    )
}

private val ROUTE_PROJECTION = arrayOf(
    ObaContract.Routes._ID,
    ObaContract.Routes.SHORTNAME,
    ObaContract.Routes.LONGNAME,
    ObaContract.Routes.URL
)

private fun Cursor.toRouteItem() = RouteListItem(
    id = getString(0),
    shortName = getString(1).orEmpty(),
    longName = getString(2)?.takeIf { it.isNotEmpty() },
    url = getString(3)?.takeIf { it.isNotEmpty() }
)

/** Recently viewed stops, marked unused on removal/clear. */
class RecentStopsRepository(private val context: Context) : MyListRepository<StopListItem> {

    override fun observe(): Flow<List<StopListItem>> =
        context.contentChanges(ObaContract.Stops.CONTENT_URI)
            .conflate()
            .map { queryRecentStops() }
            .flowOn(Dispatchers.IO)

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        ObaContract.Stops.markAsUnused(
            context, Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, id)
        )
        Unit
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        ObaContract.Stops.markAsUnused(context, ObaContract.Stops.CONTENT_URI)
        Unit
    }

    private fun queryRecentStops(): List<StopListItem> {
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        val selection = recentSelection(
            ObaContract.Stops.ACCESS_TIME, ObaContract.Stops.USE_COUNT, cutoff,
            regionWhere(ObaContract.Stops.REGION_ID)
        )
        val uri = ObaContract.Stops.CONTENT_URI.buildUpon()
            .appendQueryParameter("limit", RECENT_LIMIT).build()
        val sort = "${ObaContract.Stops.ACCESS_TIME} desc, ${ObaContract.Stops.USE_COUNT} desc"
        return context.contentResolver.query(uri, STOP_PROJECTION, selection, null, sort)
            ?.use { c -> buildList { while (c.moveToNext()) add(c.toStopItem(context)) } }
            ?: emptyList()
    }
}

/** Recently viewed routes, marked unused on removal/clear. */
class RecentRoutesRepository(private val context: Context) : MyListRepository<RouteListItem> {

    override fun observe(): Flow<List<RouteListItem>> =
        context.contentChanges(ObaContract.Routes.CONTENT_URI)
            .conflate()
            .map { queryRecentRoutes() }
            .flowOn(Dispatchers.IO)

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        ObaContract.Routes.markAsUnused(
            context, Uri.withAppendedPath(ObaContract.Routes.CONTENT_URI, id)
        )
        Unit
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        ObaContract.Routes.markAsUnused(context, ObaContract.Routes.CONTENT_URI)
        Unit
    }

    private fun queryRecentRoutes(): List<RouteListItem> {
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        val selection = recentSelection(
            ObaContract.Routes.ACCESS_TIME, ObaContract.Routes.USE_COUNT, cutoff,
            regionWhere(ObaContract.Routes.REGION_ID)
        )
        val uri = ObaContract.Routes.CONTENT_URI.buildUpon()
            .appendQueryParameter("limit", RECENT_LIMIT).build()
        val sort = "${ObaContract.Routes.ACCESS_TIME} desc, ${ObaContract.Routes.USE_COUNT} desc"
        return context.contentResolver.query(uri, ROUTE_PROJECTION, selection, null, sort)
            ?.use { c -> buildList { while (c.moveToNext()) add(c.toRouteItem()) } }
            ?: emptyList()
    }
}
