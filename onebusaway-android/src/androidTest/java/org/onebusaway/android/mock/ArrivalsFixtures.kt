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
package org.onebusaway.android.mock

import android.content.Context
import kotlinx.serialization.json.Json
import org.onebusaway.android.io.client.ArrivalsForStop
import org.onebusaway.android.io.client.EntryWithReferences
import org.onebusaway.android.io.client.ObaEnvelope
import org.onebusaway.android.io.client.SituationReference
import org.onebusaway.android.ui.arrivals.ArrivalInfo
import org.onebusaway.android.ui.arrivals.asArrivalData
import org.onebusaway.android.ui.arrivals.convertArrivals
import org.onebusaway.android.util.SituationUtils

/**
 * Test helper for instrumented tests that exercise the arrival/situation projections against a
 * captured arrivals-and-departures fixture: decodes the fixture as the io/client DTO (the production
 * wire type) and runs the same `convertArrivals` / `SituationUtils.getAllSituations` the app uses, so
 * the assertions ride the production path rather than the retired legacy Jackson types.
 */
object ArrivalsFixtures {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Decodes a `res/raw` arrivals-and-departures fixture into the io/client envelope. */
    @JvmStatic
    fun load(context: Context, fixture: String): ObaEnvelope<EntryWithReferences<ArrivalsForStop>> =
        Resources.read(context, Resources.getTestUri(fixture))
            .use { json.decodeFromString(it.readText()) }

    /** The fixture's arrivals projected to display [ArrivalInfo] via the production [convertArrivals]. */
    @JvmStatic
    fun convert(
        context: Context,
        env: ObaEnvelope<EntryWithReferences<ArrivalsForStop>>,
        includeArriveDepartLabels: Boolean,
    ): ArrayList<ArrivalInfo> {
        val arrivals = env.data!!.entry.arrivalsAndDepartures.map { it.asArrivalData() }
        return ArrayList(convertArrivals(context, arrivals, null, env.currentTime, includeArriveDepartLabels))
    }

    /** All situations (stop/agency + route alerts) for the fixture, via the production aggregation. */
    @JvmStatic
    fun allSituations(
        env: ObaEnvelope<EntryWithReferences<ArrivalsForStop>>,
        filter: List<String>?,
    ): List<SituationReference> = SituationUtils.getAllSituations(env.data!!, filter)

    /** Just the stop/agency-level situations the entry references directly (not route alerts). */
    @JvmStatic
    fun stopSituations(
        env: ObaEnvelope<EntryWithReferences<ArrivalsForStop>>,
    ): List<SituationReference> {
        val data = env.data!!
        return data.entry.situationIds.mapNotNull { data.references.situation(it) }
    }
}
