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

import javax.inject.Inject
import org.onebusaway.android.models.AgencyContact

/** Provides the customer-service contacts for the agencies covering the current region. */
interface CustomerServiceRepository {

    suspend fun getAgencies(): Result<List<AgencyContact>>
}

/**
 * Default implementation over the modernized [ObaWebService] (replacing the legacy AgenciesLoader),
 * adapting the agencies-with-coverage references to [AgencyContact]. A non-OK app-level code or a
 * transport failure maps to [Result.failure] via requireData + runCatching.
 */
class DefaultCustomerServiceRepository @Inject constructor(
    private val service: ObaWebService
) : CustomerServiceRepository {

    override suspend fun getAgencies(): Result<List<AgencyContact>> = runCatching {
        val data = service.agenciesWithCoverage().requireData()
        data.list.mapNotNull { coverage ->
            val agency = data.references.agency(coverage.agencyId) ?: return@mapNotNull null
            AgencyContact(
                id = agency.id,
                name = agency.name,
                email = agency.email?.takeIf { it.isNotEmpty() },
                url = agency.url?.takeIf { it.isNotEmpty() },
                phone = agency.phone?.takeIf { it.isNotEmpty() }
            )
        }
    }
}
