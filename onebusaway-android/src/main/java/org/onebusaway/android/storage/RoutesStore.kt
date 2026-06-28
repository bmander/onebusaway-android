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

import android.content.ContentValues
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.provider.ObaContract

/**
 * Persistence seam for the Routes table's usage/metadata writes (so a route shows in recents/search
 * and its name/URL can be displayed later). One of the storage-modernization store interfaces moving
 * `ObaContract` calls out of feature code; provider-backed for now (Slice 0).
 *
 * The methods are split by which columns they write because the legacy upsert is a *partial* update
 * (only the supplied columns change). Collapsing them into one all-columns method would clobber an
 * existing URL when only the name is known, or wipe the long name when only the short name is set.
 */
interface RoutesStore {

    /** Records a route's short/long name (marking it used). Leaves an existing URL untouched. */
    suspend fun markRouteUsed(
        routeId: String,
        shortName: String?,
        longName: String?,
        regionId: Long?
    )

    /** Stores a route's full short/long name + URL (marking it used). */
    suspend fun storeRouteDetails(
        routeId: String,
        shortName: String?,
        longName: String?,
        url: String?,
        regionId: Long?
    )

    /** Refreshes only a route's short name (does not mark it used; leaves other columns untouched). */
    suspend fun refreshRouteShortName(routeId: String, shortName: String?)
}

/** Provider-backed [RoutesStore] delegating to the legacy [ObaContract.Routes]. */
class ProviderRoutesStore @Inject constructor(
    @ApplicationContext private val context: Context
) : RoutesStore {

    override suspend fun markRouteUsed(
        routeId: String,
        shortName: String?,
        longName: String?,
        regionId: Long?
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(ObaContract.Routes.SHORTNAME, shortName)
            put(ObaContract.Routes.LONGNAME, longName)
            regionId?.let { put(ObaContract.Routes.REGION_ID, it) }
        }
        ObaContract.Routes.insertOrUpdate(context, routeId, values, true)
        Unit
    }

    override suspend fun storeRouteDetails(
        routeId: String,
        shortName: String?,
        longName: String?,
        url: String?,
        regionId: Long?
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(ObaContract.Routes.SHORTNAME, shortName)
            put(ObaContract.Routes.LONGNAME, longName)
            put(ObaContract.Routes.URL, url)
            regionId?.let { put(ObaContract.Routes.REGION_ID, it) }
        }
        ObaContract.Routes.insertOrUpdate(context, routeId, values, true)
        Unit
    }

    override suspend fun refreshRouteShortName(routeId: String, shortName: String?) =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply { put(ObaContract.Routes.SHORTNAME, shortName) }
            ObaContract.Routes.insertOrUpdate(context, routeId, values, false)
            Unit
        }
}
