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
 * A stored trip-reminder row, with [departTimeMs] already converted from the legacy minutes-to-midnight
 * DB representation to epoch millis so consumers never touch the wire format.
 */
data class StoredReminderTrip(
    val name: String?,
    val reminderMinutes: Int,
    val routeId: String?,
    val headsign: String?,
    val departTimeMs: Long,
    val stopSequence: Int,
    val serviceDate: Long,
    val vehicleId: String?,
)

/**
 * Persistence seam for trip reminders (the Trips table). One of the storage-modernization store
 * interfaces moving `ObaContract` calls out of feature code; provider-backed for now (Slice 0). The
 * reminder LIST (MyListRepository.RemindersRepository, a ContentObserver Flow) and the alarm
 * delete/exists helpers in ReminderUtils.java are migrated at the Slice 3 cutover.
 */
interface RemindersStore {

    /** The stored reminder for the (tripId, stopId) pair, or null if none is saved. */
    suspend fun getTrip(tripId: String, stopId: String): StoredReminderTrip?

    /** Inserts the reminder trip row ([departTimeMs] is converted to the DB representation). */
    suspend fun saveTrip(
        tripId: String,
        stopId: String,
        routeId: String?,
        departTimeMs: Long,
        headsign: String?,
        name: String,
        reminderMinutes: Int,
        alarmDeletePath: String,
        serviceDate: Long,
        stopSequence: Int,
        vehicleId: String?,
    )
}

/** Provider-backed [RemindersStore] delegating to the legacy [ObaContract.Trips]. */
class ProviderRemindersStore @Inject constructor(
    @ApplicationContext private val context: Context
) : RemindersStore {

    override suspend fun getTrip(tripId: String, stopId: String): StoredReminderTrip? =
        withContext(Dispatchers.IO) {
            context.contentResolver
                .query(ObaContract.Trips.buildUri(tripId, stopId), PROJECTION, null, null, null)
                ?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    StoredReminderTrip(
                        name = c.getString(COL_NAME),
                        reminderMinutes = c.getInt(COL_REMINDER),
                        routeId = c.getString(COL_ROUTE_ID),
                        headsign = c.getString(COL_HEADSIGN),
                        departTimeMs = ObaContract.Trips.convertDBToTime(c.getInt(COL_DEPARTURE)),
                        stopSequence = c.getInt(COL_STOP_SEQUENCE),
                        serviceDate = c.getLong(COL_SERVICE_DATE),
                        vehicleId = c.getString(COL_VEHICLE_ID),
                    )
                }
        }

    override suspend fun saveTrip(
        tripId: String,
        stopId: String,
        routeId: String?,
        departTimeMs: Long,
        headsign: String?,
        name: String,
        reminderMinutes: Int,
        alarmDeletePath: String,
        serviceDate: Long,
        stopSequence: Int,
        vehicleId: String?,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(ObaContract.Trips._ID, tripId)
            put(ObaContract.Trips.TRIP_ID, tripId)
            put(ObaContract.Trips.STOP_ID, stopId)
            put(ObaContract.Trips.ROUTE_ID, routeId)
            put(ObaContract.Trips.DEPARTURE, ObaContract.Trips.convertTimeToDB(departTimeMs))
            put(ObaContract.Trips.HEADSIGN, headsign)
            put(ObaContract.Trips.NAME, name)
            put(ObaContract.Trips.REMINDER, reminderMinutes)
            put(ObaContract.Trips.ALARM_DELETE_PATH, alarmDeletePath)
            put(ObaContract.Trips.SERVICE_DATE, serviceDate)
            put(ObaContract.Trips.STOP_SEQUENCE, stopSequence)
            put(ObaContract.Trips.VEHICLE_ID, vehicleId)
        }
        context.contentResolver.insert(ObaContract.Trips.CONTENT_URI, values)
        Unit
    }

    private companion object {

        val PROJECTION = arrayOf(
            ObaContract.Trips.NAME,
            ObaContract.Trips.REMINDER,
            ObaContract.Trips.ROUTE_ID,
            ObaContract.Trips.HEADSIGN,
            ObaContract.Trips.DEPARTURE,
            ObaContract.Trips.STOP_SEQUENCE,
            ObaContract.Trips.SERVICE_DATE,
            ObaContract.Trips.VEHICLE_ID
        )

        const val COL_NAME = 0
        const val COL_REMINDER = 1
        const val COL_ROUTE_ID = 2
        const val COL_HEADSIGN = 3
        const val COL_DEPARTURE = 4
        const val COL_STOP_SEQUENCE = 5
        const val COL_SERVICE_DATE = 6
        const val COL_VEHICLE_ID = 7
    }
}
