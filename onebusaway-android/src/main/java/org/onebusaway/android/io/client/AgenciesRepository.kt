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
package org.onebusaway.android.io.client

import android.util.Log
import javax.inject.Inject
import org.onebusaway.android.models.AgencyContact

/**
 * The agencies-with-coverage fetch for the current region — the single source for both the supported-
 * agencies list and the customer-service contacts screen (which read different subsets of
 * [AgencyContact]). A non-OK app-level code or a transport failure maps to [Result.failure].
 */
interface AgenciesRepository {

    suspend fun getAgencies(): Result<List<AgencyContact>>
}

/** Default implementation over [ObaWebService], adapting the references to [AgencyContact]. */
class DefaultAgenciesRepository @Inject constructor(
    private val service: ObaWebService
) : AgenciesRepository {

    override suspend fun getAgencies(): Result<List<AgencyContact>> = runCatching {
        service.agenciesWithCoverage().requireData().toAgencyContacts()
    }.onFailure { Log.e(TAG, "getAgencies failed", it) }

    private companion object {
        const val TAG = "AgenciesRepository"
    }
}

/**
 * Maps the agencies-with-coverage payload to display [AgencyContact]s, resolving each coverage entry's
 * agency from the references by id (skipping any unresolved entry) and normalizing blank fields to
 * null. Pure, so it is exercised directly in JVM unit tests.
 */
internal fun ListWithReferences<AgencyCoverage>.toAgencyContacts(): List<AgencyContact> =
    list.mapNotNull { coverage ->
        val agency = references.agency(coverage.agencyId) ?: return@mapNotNull null
        AgencyContact(
            id = agency.id,
            name = agency.name,
            email = agency.email?.takeIf { it.isNotEmpty() },
            url = agency.url?.takeIf { it.isNotEmpty() },
            phone = agency.phone?.takeIf { it.isNotEmpty() },
        )
    }
