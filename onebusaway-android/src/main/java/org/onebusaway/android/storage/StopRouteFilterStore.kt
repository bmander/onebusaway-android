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
import org.onebusaway.android.database.oba.StopRouteFilterDao
import org.onebusaway.android.provider.ObaContract

/**
 * Persistence seam for the per-stop route filter (the routes a user chose to keep visible in a stop's
 * arrival list; empty == show all). One of the storage-modernization store interfaces that move the
 * `ObaContract`/ContentProvider calls out of feature code so the backing store can be swapped to Room
 * without touching consumers. The default impl is still provider-backed (Slice 0).
 */
interface StopRouteFilterStore {

    /** Route IDs to keep for [stopId]; empty if there is no filter or on error. */
    suspend fun getFilter(stopId: String): List<String>

    /** Replaces the persisted filter for [stopId] (empty == show all). */
    suspend fun setFilter(stopId: String, routeIds: List<String>)
}

/** Provider-backed [StopRouteFilterStore] delegating to the legacy [ObaContract.StopRouteFilters]. */
class ProviderStopRouteFilterStore @Inject constructor(
    @ApplicationContext private val context: Context
) : StopRouteFilterStore {

    override suspend fun getFilter(stopId: String): List<String> = withContext(Dispatchers.IO) {
        ObaContract.StopRouteFilters.get(context, stopId)
    }

    override suspend fun setFilter(stopId: String, routeIds: List<String>) =
        withContext(Dispatchers.IO) {
            ObaContract.StopRouteFilters.set(context, stopId, ArrayList(routeIds))
        }
}

/** Room-backed [StopRouteFilterStore]. */
class RoomStopRouteFilterStore @Inject constructor(
    private val dao: StopRouteFilterDao
) : StopRouteFilterStore {

    override suspend fun getFilter(stopId: String): List<String> = dao.routeIdsForStop(stopId)

    override suspend fun setFilter(stopId: String, routeIds: List<String>) =
        dao.replaceForStop(stopId, routeIds)
}
