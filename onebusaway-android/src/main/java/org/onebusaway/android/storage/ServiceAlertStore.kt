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
import org.onebusaway.android.R
import org.onebusaway.android.database.oba.ServiceAlertDao
import org.onebusaway.android.database.oba.ServiceAlertRecord
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.provider.ObaContract

/**
 * Persistence seam for service-alert (situation) read/hidden state. One of the storage-modernization
 * store interfaces that move the `ObaContract`/ContentProvider calls out of feature code so the
 * backing store can be swapped to Room without touching consumers. The default impl is still
 * provider-backed (Slice 0).
 */
interface ServiceAlertStore {

    /** Records [id] if absent, without changing read/hidden state (hidden defaults from the pref). */
    suspend fun ensureRecorded(id: String)

    /** Records/updates [id] and stamps it read. */
    suspend fun markRead(id: String)

    /** Records [id] if absent and sets its hidden flag. */
    suspend fun setHidden(id: String, hidden: Boolean)

    /** Whether [id] is currently hidden. */
    suspend fun isHidden(id: String): Boolean

    /** Un-hides every recorded alert. */
    suspend fun showAll()

    /** Hides every recorded alert. */
    suspend fun hideAll()
}

/** Provider-backed [ServiceAlertStore] delegating to the legacy [ObaContract.ServiceAlerts]. */
class ProviderServiceAlertStore @Inject constructor() : ServiceAlertStore {

    override suspend fun ensureRecorded(id: String) = withContext(Dispatchers.IO) {
        ObaContract.ServiceAlerts.insertOrUpdate(id, ContentValues(), false, null)
        Unit
    }

    override suspend fun markRead(id: String) = withContext(Dispatchers.IO) {
        ObaContract.ServiceAlerts.insertOrUpdate(id, ContentValues(), true, null)
        Unit
    }

    override suspend fun setHidden(id: String, hidden: Boolean) = withContext(Dispatchers.IO) {
        ObaContract.ServiceAlerts.insertOrUpdate(id, ContentValues(), false, hidden)
        Unit
    }

    override suspend fun isHidden(id: String): Boolean = withContext(Dispatchers.IO) {
        ObaContract.ServiceAlerts.isHidden(id)
    }

    override suspend fun showAll() = withContext(Dispatchers.IO) {
        ObaContract.ServiceAlerts.showAllAlerts()
        Unit
    }

    override suspend fun hideAll() = withContext(Dispatchers.IO) {
        ObaContract.ServiceAlerts.hideAllAlerts()
        Unit
    }
}

/**
 * Room-backed [ServiceAlertStore]. Mirrors the legacy upsert semantics: a brand-new alert is recorded
 * hidden when the user's "hide all alerts" preference is on; recording an existing alert never changes
 * its read/hidden state.
 */
class RoomServiceAlertStore @Inject constructor(
    private val dao: ServiceAlertDao,
    private val prefs: PreferencesRepository,
    @ApplicationContext private val context: Context,
) : ServiceAlertStore {

    /** The hidden value for a newly-recorded alert: 1 when "hide all alerts" is on, else null (unset). */
    private fun defaultHidden(): Int? =
        if (prefs.getBoolean(context.getString(R.string.preference_key_hide_alerts), false)) 1 else null

    override suspend fun ensureRecorded(id: String) {
        dao.insertIfAbsent(ServiceAlertRecord(id = id, hidden = defaultHidden()))
    }

    override suspend fun markRead(id: String) {
        dao.insertIfAbsent(ServiceAlertRecord(id = id, hidden = defaultHidden()))
        dao.updateMarkedReadTime(id, System.currentTimeMillis())
    }

    override suspend fun setHidden(id: String, hidden: Boolean) {
        dao.insertIfAbsent(ServiceAlertRecord(id = id, hidden = defaultHidden()))
        dao.updateHidden(id, if (hidden) 1 else 0)
    }

    override suspend fun isHidden(id: String): Boolean = dao.isHidden(id)

    override suspend fun showAll() = dao.setAllHidden(0)

    override suspend fun hideAll() = dao.setAllHidden(1)
}
