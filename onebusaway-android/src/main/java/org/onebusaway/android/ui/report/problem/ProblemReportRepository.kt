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
package org.onebusaway.android.ui.report.problem

import android.location.Location
import org.onebusaway.android.io.client.ObaWebService
import org.onebusaway.android.io.client.requireOk

/** Submits stop/trip problem reports to the OBA REST API. */
interface ProblemReportRepository {

    suspend fun submitStop(
        stopId: String,
        code: String,
        comment: String,
        location: Location?
    ): Result<Unit>

    suspend fun submitTrip(
        params: ProblemParams.Trip,
        code: String,
        comment: String,
        onVehicle: Boolean,
        vehicleNumber: String,
        location: Location?
    ): Result<Unit>
}

/**
 * Default implementation over the modernized [ObaWebService] (replacing the legacy
 * ReportLoader AsyncTaskLoader). A non-OK app-level code or a transport failure maps to
 * [Result.failure] via [requireOk] + `runCatching`.
 */
class DefaultProblemReportRepository(
    private val service: ObaWebService
) : ProblemReportRepository {

    override suspend fun submitStop(
        stopId: String,
        code: String,
        comment: String,
        location: Location?
    ): Result<Unit> = runCatching {
        service.reportProblemWithStop(
            stopId = stopId,
            code = code,
            data = dataJson(code),
            userComment = comment.ifEmpty { null },
            userLat = location?.latitude,
            userLon = location?.longitude,
            userLocationAccuracy = location?.accuracyMeters(),
        ).requireOk()
    }

    override suspend fun submitTrip(
        params: ProblemParams.Trip,
        code: String,
        comment: String,
        onVehicle: Boolean,
        vehicleNumber: String,
        location: Location?
    ): Result<Unit> = runCatching {
        service.reportProblemWithTrip(
            tripId = params.tripId,
            code = code,
            data = dataJson(code),
            stopId = params.stopId,
            serviceDate = params.serviceDate,
            vehicleId = params.vehicleId,
            userComment = comment.ifEmpty { null },
            userLat = location?.latitude,
            userLon = location?.longitude,
            userLocationAccuracy = location?.accuracyMeters(),
            userOnVehicle = onVehicle,
            userVehicleNumber = vehicleNumber.ifEmpty { null },
        ).requireOk()
    }

    /** The legacy JSON-encoded `data` param the API still expects alongside `code`. */
    private fun dataJson(code: String) = """{"code":"$code"}"""

    /** The location's accuracy in whole meters, or null when the fix carries none. */
    private fun Location.accuracyMeters(): Int? = if (hasAccuracy()) accuracy.toInt() else null
}
