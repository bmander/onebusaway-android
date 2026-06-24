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
package org.onebusaway.android.ui.agencies

import android.util.Log
import javax.inject.Inject
import org.onebusaway.android.io.client.AgencyCoverage
import org.onebusaway.android.io.client.ListWithReferences
import org.onebusaway.android.io.client.ObaWebService
import org.onebusaway.android.io.client.requireData

/**
 * A transit agency as displayed on the supported agencies screen, decoupled from the
 * io/client response types.
 *
 * @param url the agency's website, or null if it has none (never blank)
 */
data class AgencyItem(
    val id: String,
    val name: String,
    val url: String?
)

/** Provides the list of transit agencies covered by the current region. */
interface AgenciesRepository {

    suspend fun getAgencies(): Result<List<AgencyItem>>
}

/**
 * Default implementation backed by the modernized [ObaWebService]. Retrofit suspend functions are
 * main-safe, so no manual `withContext(Dispatchers.IO)` is needed; failures (IO/HTTP/serialization
 * or a non-OK OBA code, via [requireData]) are mapped to [Result.failure].
 */
class DefaultAgenciesRepository @Inject constructor(
    private val service: ObaWebService
) : AgenciesRepository {

    override suspend fun getAgencies(): Result<List<AgencyItem>> = runCatching {
        service.agenciesWithCoverage().requireData().toAgencyItems()
    }.onFailure { Log.e(TAG, "getAgencies failed", it) }

    private companion object {
        const val TAG = "AgenciesRepository"
    }
}

/**
 * Maps the agencies-with-coverage payload to display [AgencyItem]s, resolving each coverage entry's
 * agency from the references by id (skipping any unresolved entry) and normalizing blank URLs to
 * null. Pure, so it is exercised directly in JVM unit tests.
 */
fun ListWithReferences<AgencyCoverage>.toAgencyItems(): List<AgencyItem> =
    list.mapNotNull { coverage ->
        val agency = references.agency(coverage.agencyId) ?: return@mapNotNull null
        AgencyItem(
            id = agency.id,
            name = agency.name,
            url = agency.url?.takeIf { it.isNotEmpty() }
        )
    }
