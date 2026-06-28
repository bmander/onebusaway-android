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
package org.onebusaway.android.ui.tripinfo

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.api.contract.ReminderWebService
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.provider.ProviderQueries
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.storage.RemindersStore
import org.onebusaway.android.storage.RoutesStore
import org.onebusaway.android.storage.StoredReminderTrip
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.MyTextUtils
import org.onebusaway.android.util.ReminderUtils

/** The reminder lead times (minutes) backing each spinner position, as stored in the Trips table. */
internal val REMINDER_MINUTES = listOf(1, 3, 5, 10, 15, 20, 25, 30)

/**
 * The launch parameters of the Trip Info screen. The arrivals "set reminder" action passes the full
 * set; editing an existing reminder passes only [tripId]/[stopId] and the rest is read from the
 * Trips table by [TripInfoRepository.load].
 */
data class TripInfoArgs(
    val tripId: String,
    val stopId: String,
    val routeId: String? = null,
    val routeName: String? = null,
    val stopName: String? = null,
    val headsign: String? = null,
    val departTime: Long = 0,
    val stopSequence: Int = 0,
    val serviceDate: Long = 0,
    val vehicleId: String? = null
) {

    val tripUri: Uri = ObaContract.Trips.buildUri(tripId, stopId)
}

/**
 * The resolved trip-reminder data: [TripInfoArgs] merged with any existing Trips row (args win),
 * plus the display strings for the form header and the valid reminder options for this departure.
 */
data class TripInfoData(
    // Raw values, persisted back to the Trips table on save.
    val routeId: String?,
    val headsign: String?,
    val departTime: Long,
    val stopSequence: Int,
    val serviceDate: Long,
    val vehicleId: String?,
    val tripName: String,
    val reminderMinutes: Int,
    val isNewTrip: Boolean,
    // Display-ready strings for the form.
    val stopNameText: String,
    val routeText: String,
    val headsignText: String,
    val departureText: String,
    val reminderOptions: List<String>
)

interface TripInfoRepository {

    /** Loads and merges the reminder data for [args] (see [TripInfoData]). */
    suspend fun load(args: TripInfoArgs): TripInfoData

    /**
     * Registers the reminder alarm with the region's server and, on success, saves the trip row.
     * Replaces any existing alarm for this trip first. Returns false if the alarm could not be set.
     */
    suspend fun save(args: TripInfoArgs, data: TripInfoData, reminderMinutes: Int, tripName: String): Boolean
}

class DefaultTripInfoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val regionRepository: RegionRepository,
    private val reminderService: ReminderWebService,
    private val routesStore: RoutesStore,
    private val remindersStore: RemindersStore,
) : TripInfoRepository {

    override suspend fun load(args: TripInfoArgs): TripInfoData = withContext(Dispatchers.IO) {
        // If the launcher passed a route name, refresh it in the Routes table (legacy behavior).
        if (args.routeId != null && args.routeName != null) {
            routesStore.refreshRouteShortName(args.routeId, args.routeName)
        }
        val fromDb = remindersStore.getTrip(args.tripId, args.stopId)?.let { toExistingTrip(it, args) }
        fromDb ?: newTrip(args)
    }

    /** Merges an existing stored reminder with [args] (args win), looking up any still-missing names. */
    private fun toExistingTrip(stored: StoredReminderTrip, args: TripInfoArgs): TripInfoData {
        val routeId = args.routeId ?: stored.routeId
        val departTime = if (args.departTime != 0L) args.departTime else stored.departTimeMs
        val routeName = args.routeName ?: ReminderUtils.getRouteShortName(context, routeId)
        val stopName = args.stopName ?: ProviderQueries.stringForQuery(
            context,
            Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, args.stopId),
            ObaContract.Stops.NAME
        )
        return toTripInfoData(
            routeId = routeId,
            routeName = routeName,
            stopName = stopName,
            headsign = args.headsign ?: stored.headsign,
            departTime = departTime,
            stopSequence = if (args.stopSequence != 0) args.stopSequence else stored.stopSequence,
            serviceDate = if (args.serviceDate != 0L) args.serviceDate else stored.serviceDate,
            vehicleId = args.vehicleId ?: stored.vehicleId,
            tripName = stored.name.orEmpty(),
            reminderMinutes = stored.reminderMinutes,
            isNewTrip = false
        )
    }

    private fun newTrip(args: TripInfoArgs): TripInfoData = toTripInfoData(
        routeId = args.routeId,
        routeName = args.routeName,
        stopName = args.stopName,
        headsign = args.headsign,
        departTime = args.departTime,
        stopSequence = args.stopSequence,
        serviceDate = args.serviceDate,
        vehicleId = args.vehicleId,
        tripName = "",
        reminderMinutes = PreferenceUtils.getInt(
            context.getString(R.string.preference_key_default_reminder_time), DEFAULT_REMINDER_MINUTES
        ),
        isNewTrip = true
    )

    private fun toTripInfoData(
        routeId: String?,
        routeName: String?,
        stopName: String?,
        headsign: String?,
        departTime: Long,
        stopSequence: Int,
        serviceDate: Long,
        vehicleId: String?,
        tripName: String,
        reminderMinutes: Int,
        isNewTrip: Boolean
    ) = TripInfoData(
        routeId = routeId,
        headsign = headsign,
        departTime = departTime,
        stopSequence = stopSequence,
        serviceDate = serviceDate,
        vehicleId = vehicleId,
        tripName = tripName,
        reminderMinutes = reminderMinutes,
        isNewTrip = isNewTrip,
        stopNameText = MyTextUtils.formatDisplayText(stopName.orEmpty()).orEmpty(),
        routeText = MyTextUtils.formatDisplayText(context.getString(R.string.trip_info_route, routeName.orEmpty())).orEmpty(),
        headsignText = MyTextUtils.formatDisplayText(headsign.orEmpty()).orEmpty(),
        departureText = context.getString(
            R.string.trip_info_depart,
            DateUtils.formatDateTime(
                context, departTime,
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NO_NOON or DateUtils.FORMAT_NO_MIDNIGHT
            )
        ),
        reminderOptions = ReminderUtils.getReminderTimes(context, departTime).toList()
    )

    override suspend fun save(
        args: TripInfoArgs,
        data: TripInfoData,
        reminderMinutes: Int,
        tripName: String
    ): Boolean = withContext(Dispatchers.IO) {
        // Replace any existing alarm for this trip before registering the new one.
        if (ReminderUtils.isAlarmExist(context, args.tripUri)) {
            ReminderUtils.requestDeleteAlarm(context, args.tripUri)
        }
        PreferenceUtils.saveInt(
            context.getString(R.string.preference_key_default_reminder_time), reminderMinutes
        )
        val alarmDeletePath = requestAlarm(args, data, reminderMinutes * 60) ?: return@withContext false
        remindersStore.saveTrip(
            tripId = args.tripId,
            stopId = args.stopId,
            routeId = data.routeId,
            departTimeMs = data.departTime,
            headsign = data.headsign,
            name = tripName,
            reminderMinutes = reminderMinutes,
            alarmDeletePath = alarmDeletePath,
            serviceDate = data.serviceDate,
            stopSequence = data.stopSequence,
            vehicleId = data.vehicleId,
        )
        true
    }

    /** Registers the alarm with the region's server; returns its delete URL, or null on failure. */
    private suspend fun requestAlarm(
        args: TripInfoArgs,
        data: TripInfoData,
        reminderSeconds: Int
    ): String? {
        val region = regionRepository.region.value ?: return null
        val base = region.sidecarBaseUrl ?: return null
        // The push id is the one required field not guaranteed by the typed args (it's absent until
        // push registration completes); the legacy builder returned null in that case.
        val userPushId = Application.getUserPushID()
        if (userPushId.isNullOrEmpty()) return null
        val url = base + context.getString(R.string.arrivals_reminders_api_endpoint) +
            region.id + "/alarms"
        return runCatching {
            reminderService.createAlarm(
                url = url,
                stopId = args.stopId,
                serviceDate = data.serviceDate,
                stopSequence = data.stopSequence,
                tripId = args.tripId,
                userPushId = userPushId,
                secondsBefore = reminderSeconds,
                vehicleId = data.vehicleId,
            ).url
        }.getOrNull()
    }

    companion object {

        private const val DEFAULT_REMINDER_MINUTES = 10
    }
}
