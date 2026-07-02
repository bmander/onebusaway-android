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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.onebusaway.android.database.oba.ServiceAlertDao
import org.onebusaway.android.database.oba.ServiceAlertRecord
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.provider.contentChanges

/**
 * Persistence seam for service-alert (situation) read/hidden state. One of the storage-modernization
 * store interfaces that move the `ObaContract`/ContentProvider calls out of feature code so the
 * backing store can be swapped to Room without touching consumers. The default impl is still
 * provider-backed (Slice 0).
 *
 * Hidden state follows the #1593 model: writes record an *explicit* per-id decision (hidden true/false),
 * and the "hide all alerts" preference is applied downstream in the arrivals derivation — never baked
 * into storage — so merely opening an alert (mark-as-read) can't harden the default into a permanent
 * hide. [hideDecisions] is the reactive single-source-of-truth the screen derives the shown/hidden
 * split from.
 */
interface ServiceAlertStore {

    /** Records/updates [id] and stamps it read (without touching its hide decision). */
    suspend fun markRead(id: String)

    /** Records an explicit hide decision for [id] (true = hidden, false = shown). */
    suspend fun setHidden(id: String, hidden: Boolean)

    /** Hides every recorded alert (the settings "hide all alerts" toggle / the dialog's Hide All). */
    suspend fun hideAll()

    /** The explicit per-id hide decisions (id → isHidden), re-emitting on every change. See #1593. */
    fun hideDecisions(): Flow<Map<String, Boolean>>
}

/** Provider-backed [ServiceAlertStore] delegating to the legacy [ObaContract.ServiceAlerts]. */
class ProviderServiceAlertStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ServiceAlertStore {

    override suspend fun markRead(id: String) = withContext(Dispatchers.IO) {
        ObaContract.ServiceAlerts.insertOrUpdate(id, ContentValues(), true, null)
        Unit
    }

    override suspend fun setHidden(id: String, hidden: Boolean) = withContext(Dispatchers.IO) {
        ObaContract.ServiceAlerts.insertOrUpdate(id, ContentValues(), false, hidden)
        Unit
    }

    override suspend fun hideAll() = withContext(Dispatchers.IO) {
        ObaContract.ServiceAlerts.hideAllAlerts()
        Unit
    }

    override fun hideDecisions(): Flow<Map<String, Boolean>> =
        context.contentChanges(ObaContract.ServiceAlerts.CONTENT_URI)
            .map { ObaContract.ServiceAlerts.getHideDecisions() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
}

/**
 * Room-backed [ServiceAlertStore]. Records only explicit hide decisions (the "hide all alerts"
 * preference is applied downstream, per #1593); recording read state never changes the hide decision.
 */
class RoomServiceAlertStore @Inject constructor(
    private val dao: ServiceAlertDao,
) : ServiceAlertStore {

    override suspend fun markRead(id: String) {
        dao.insertIfAbsent(ServiceAlertRecord(id = id, hidden = null))
        dao.updateMarkedReadTime(id, System.currentTimeMillis())
    }

    override suspend fun setHidden(id: String, hidden: Boolean) {
        dao.insertIfAbsent(ServiceAlertRecord(id = id, hidden = null))
        dao.updateHidden(id, if (hidden) 1 else 0)
    }

    override suspend fun hideAll() = dao.setAllHidden(1)

    override fun hideDecisions(): Flow<Map<String, Boolean>> =
        dao.hideDecisions().map { rows -> rows.associate { it.id to (it.hidden == 1) } }
}
