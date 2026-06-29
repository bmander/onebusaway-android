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
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.database.oba.StopDao
import org.onebusaway.android.provider.ObaContract

/**
 * Persistence seam for the Stops table's user-state writes (favorite toggle, etc.). One of the
 * storage-modernization store interfaces moving `ObaContract` calls out of feature code;
 * provider-backed for now (Slice 0). Read helpers (loadStopUserInfo, getLocation) and the
 * record/usage writes from the Java utils are migrated at the Slice 3 cutover.
 */
interface StopsStore {

    /** Marks (or unmarks) the stop a favorite. The row must already exist (an UPDATE). */
    suspend fun setFavorite(stopId: String, favorite: Boolean)
}

/** Provider-backed [StopsStore] delegating to the legacy [ObaContract.Stops]. */
class ProviderStopsStore @Inject constructor(
    @ApplicationContext private val context: Context
) : StopsStore {

    override suspend fun setFavorite(stopId: String, favorite: Boolean) =
        withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId)
            ObaContract.Stops.markAsFavorite(context, uri, favorite)
            Unit
        }
}

/** Room-backed [StopsStore]. setFavorite is an UPDATE — a no-op when the row doesn't exist. */
class RoomStopsStore @Inject constructor(
    private val dao: StopDao
) : StopsStore {

    override suspend fun setFavorite(stopId: String, favorite: Boolean) =
        dao.setFavorite(stopId, if (favorite) 1 else 0)
}
