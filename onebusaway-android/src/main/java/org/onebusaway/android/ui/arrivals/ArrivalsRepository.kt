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
package org.onebusaway.android.ui.arrivals

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.io.client.ArrivalsForStop
import org.onebusaway.android.io.client.EntryWithReferences
import org.onebusaway.android.io.client.ObaEnvelope
import org.onebusaway.android.io.client.ObaWebService
import org.onebusaway.android.io.client.References
import org.onebusaway.android.io.client.RouteReference
import org.onebusaway.android.io.client.RouteRepository
import org.onebusaway.android.io.client.StopReference
import org.onebusaway.android.io.client.colorArgb
import org.onebusaway.android.io.client.textColorArgb
import org.onebusaway.android.models.ObaRoute
import org.onebusaway.android.models.ObaSituation
import org.onebusaway.android.models.ObaStop
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.provider.loadStopUserInfo
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.util.BuildFlavorUtils
import org.onebusaway.android.util.DBUtil
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.MyTextUtils
import org.onebusaway.android.util.ObaRequestErrors
import org.onebusaway.android.util.SituationUtils
import org.onebusaway.android.util.getRouteDisplayName

/** A loaded snapshot of a stop's arrivals plus the header, actions, alerts, and filter data. */
data class ArrivalsData(
    val arrivals: List<ArrivalInfo>,
    val header: StopHeader,
    /** The effective time window after the loader's empty-result expansion. */
    val minutesAfter: Int,
    val style: Int,
    val isStale: Boolean,
    /** The route filter actually applied (loaded from the provider when the caller passed null). */
    val effectiveRouteFilter: Set<String>,
    val actions: Map<String, ArrivalActions>,
    val alerts: List<AlertItem>,
    val hiddenAlertCount: Int,
    val routeFilterOptions: List<RouteFilterOption>,
    val filteredRouteCount: Int,
    val stopCode: String?,
    val stopLat: Double,
    val stopLon: Double,
    val stopUserName: String?
)

/** Loads real-time arrivals for a stop and persists the per-stop route filter / favorite. */
interface ArrivalsRepository {

    /**
     * @param routeFilter the routes to keep, or null to load the persisted filter for this stop
     */
    suspend fun getArrivals(
        stopId: String,
        minutesAfter: Int,
        routeFilter: Set<String>?
    ): Result<ArrivalsData>

    /** Marks (or unmarks) the stop as a favorite in the provider. */
    suspend fun setStopFavorite(stopId: String, favorite: Boolean)

    /**
     * Stars (or unstars) a route/headsign favorite, then backfills the route's full details
     * (short/long name, URL) from the API so the long name can be shown later. [stopId] null means
     * "all stops". Replaces the legacy QueryUtils write + AsyncTaskLoader route-info fetch.
     */
    suspend fun favoriteRoute(
        routeId: String,
        headsign: String?,
        stopId: String?,
        shortName: String?,
        longName: String?,
        favorite: Boolean
    )

    /** Persists the per-stop route filter (empty == show all). */
    suspend fun setRouteFilter(stopId: String, filter: Set<String>)

    /** Persists the arrival-info display style (the legacy "sort by" view-mode toggle). */
    suspend fun setArrivalStyle(style: Int)

    /** Marks the given service alerts as hidden. */
    suspend fun hideAlerts(ids: List<String>)

    /** Un-hides every service alert (the "show hidden alerts" action). */
    suspend fun showAllAlerts()

    /** The service-alert dialog's content for an alert id, from the last good response, or null. */
    fun alertDetails(id: String): AlertDetails?

    /** The map-relevant snapshot from the last good load, for the map panel's recentering/tutorials. */
    fun lastLoaded(): ArrivalsLoaded?
}

/**
 * What the map host needs after each arrivals load: the focused [stop] to recenter on, its [routes]
 * (rendered on the map), and whether there are [hasArrivals] (gates the onboarding tutorial).
 * Decouples the host from the raw arrivals envelope. ([stop]/[routes] are still the legacy element
 * types the map subsystem consumes — a separate axis from this envelope decoupling.)
 */
data class ArrivalsLoaded(
    val stop: ObaStop?,
    val routes: List<ObaRoute>?,
    val hasArrivals: Boolean,
)

/** The fields the service-alert dialog shows, decoupled from `ObaSituation`. */
data class AlertDetails(
    val id: String,
    val summary: String?,
    val description: String?,
    val url: String?,
)

/**
 * Default implementation wrapping the blocking arrivals-and-departures request. Ports
 * ArrivalsListLoader's behavior: widen the time window until arrivals are found, and fall back
 * to the last good response when a refresh fails. Builds the existing [ArrivalInfo] display
 * model plus the per-arrival actions, service alerts, and route-filter options on the IO thread
 * (their constructors read ContentProviders). All Android statics are quarantined here so
 * [ArrivalsViewModel] stays JVM-testable.
 *
 * Note: this repo is **stateful** ([lastGood]) and 1:1 with its [ArrivalsViewModel], so its
 * `@Binds` is intentionally **unscoped** (a fresh instance per VM) — do NOT make it `@Singleton`,
 * which would share `lastGood` across stops and corrupt per-stop state.
 */
class DefaultArrivalsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val regionRepository: RegionRepository,
    private val routeRepository: RouteRepository,
    private val service: ObaWebService
) : ArrivalsRepository {

    private var lastGood: ObaEnvelope<EntryWithReferences<ArrivalsForStop>>? = null

    private var lastGoodMinutesAfter: Int = MINUTES_AFTER_DEFAULT

    // Whether the viewed stop has been recorded in the Stops table this session. Recording (a) creates
    // the row so the favorite toggle's UPDATE actually persists, and (b) marks it used so it appears in
    // Recent stops. markAsUsed bumps USE_COUNT, so this is done once — not on every 60s poll/refresh.
    private var stopRecorded = false

    override suspend fun getArrivals(
        stopId: String,
        minutesAfter: Int,
        routeFilter: Set<String>?
    ): Result<ArrivalsData> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val filter = routeFilter ?: ObaContract.StopRouteFilters.get(context, stopId).toSet()
        var minutes = minutesAfter
        // A transport/parse failure surfaces as null (getOrNull); a server error is a non-OK
        // envelope. Widen the window while OK-but-empty, matching the legacy loader.
        var envelope: ObaEnvelope<EntryWithReferences<ArrivalsForStop>>? = null
        var empty: Boolean
        do {
            envelope = runCatching { service.arrivalsAndDeparturesForStop(stopId, minutes) }.getOrNull()
            val ads = envelope?.takeIf { it.code == ObaApi.OBA_OK }?.data?.entry?.arrivalsAndDepartures
            empty = ads.isNullOrEmpty()
            if (empty) {
                minutes += MINUTES_AFTER_INCREMENT
            }
        } while (empty && minutes <= MINUTES_AFTER_MAX)

        val fresh = envelope?.takeIf { it.code == ObaApi.OBA_OK && it.data != null }
        when {
            fresh != null -> {
                lastGood = fresh
                lastGoodMinutesAfter = minutes
                val data = fresh.data!!
                // Record the stop once per session so favoriting persists (markAsFavorite is an
                // UPDATE — it needs the row to exist) and the stop shows in Recent stops.
                if (!stopRecorded) {
                    data.references.stop(data.entry.stopId)?.let {
                        DBUtil.addToDB(it.id, it.code, it.name, it.direction, it.lat, it.lon)
                        stopRecorded = true
                    }
                }
                Result.success(toData(stopId, data, minutes, filter, isStale = false, now))
            }
            // Refresh failed but we have prior data — keep showing it (legacy stale fallback)
            lastGood != null ->
                Result.success(
                    toData(stopId, lastGood!!.data!!, lastGoodMinutesAfter, filter, isStale = true, now)
                )

            else -> Result.failure(
                IOException(ObaRequestErrors.getStopErrorString(context, envelope?.code ?: ObaApi.OBA_IO_EXCEPTION))
            )
        }
    }

    private fun toData(
        stopId: String,
        data: EntryWithReferences<ArrivalsForStop>,
        minutesAfter: Int,
        routeFilter: Set<String>,
        isStale: Boolean,
        now: Long
    ): ArrivalsData {
        val refs = data.references
        val style = BuildFlavorUtils.getArrivalInfoStyleFromPreferences(context)
        // Style B includes the arrival/departure word in the status label; Style A does not
        val includeArrivalDepartureLabel = style == BuildFlavorUtils.ARRIVAL_INFO_STYLE_B
        val arrivals = convertArrivals(
            context,
            data.entry.arrivalsAndDepartures.map { it.asArrivalData() },
            routeFilter,
            now,
            includeArrivalDepartureLabel
        )
        val stop = refs.stop(data.entry.stopId)
        val userInfo = loadStopUserInfo(context, stopId)
        val header = StopHeader(
            stopId = stopId,
            name = MyTextUtils.formatDisplayText(stop?.name).orEmpty(),
            direction = stop?.direction,
            isFavorite = userInfo?.isFavorite ?: false,
            routeCount = stop?.routeIds?.size ?: 0
        )
        val routeOptions = buildRouteFilterOptions(refs, stop, routeFilter)
        val (alerts, hiddenAlertCount) = buildAlerts(data, routeFilter, now)
        return ArrivalsData(
            arrivals = arrivals,
            header = header,
            minutesAfter = minutesAfter,
            style = style,
            isStale = isStale,
            effectiveRouteFilter = routeFilter,
            actions = buildActions(refs, arrivals),
            alerts = alerts,
            hiddenAlertCount = hiddenAlertCount,
            routeFilterOptions = routeOptions,
            filteredRouteCount = routeFilter.size,
            stopCode = stop?.code,
            stopLat = stop?.lat ?: 0.0,
            stopLon = stop?.lon ?: 0.0,
            stopUserName = userInfo?.userName
        )
    }

    /** Precomputes the navigation/dialog data for each arrival (legacy reads these on menu tap). */
    private fun buildActions(
        refs: References,
        arrivals: List<ArrivalInfo>
    ): Map<String, ArrivalActions> = arrivals.associate { arrival ->
        val route = refs.route(arrival.routeId)
        arrival.tripId to ArrivalActions(
            tripId = arrival.tripId,
            routeId = arrival.routeId,
            headsign = arrival.headsign.orEmpty(),
            stopId = arrival.stopId,
            routeShortName = route?.shortName,
            routeLongName = arrival.routeLongName,
            scheduleUrl = route?.url,
            agencyName = route?.agencyId?.let { refs.agency(it)?.name },
            blockId = refs.trip(arrival.tripId)?.blockId,
            isRouteFavorite = arrival.isRouteAndHeadsignFavorite
        )
    }

    /** Ports ArrivalsListFragment.refreshSituations: persist, then keep active + non-hidden. */
    private fun buildAlerts(
        data: EntryWithReferences<ArrivalsForStop>,
        routeFilter: Set<String>,
        now: Long
    ): Pair<List<AlertItem>, Int> {
        val situations = SituationUtils.getAllSituations(data, ArrayList(routeFilter))
        if (situations.isEmpty()) return emptyList<AlertItem>() to 0
        val active = mutableListOf<AlertItem>()
        var hiddenCount = 0
        for (situation in situations) {
            // Make sure this situation is recorded so read/hidden state can be tracked
            ObaContract.ServiceAlerts.insertOrUpdate(situation.id, ContentValues(), false, null)
            val isHidden = ObaContract.ServiceAlerts.isHidden(situation.id)
            if (SituationUtils.isActiveWindowForSituation(situation, now) && !isHidden) {
                active.add(
                    AlertItem(situation.id, situation.summary.value.orEmpty(), severityOf(situation.severity))
                )
            }
            if (isHidden) hiddenCount++
        }
        return active to hiddenCount
    }

    private fun buildRouteFilterOptions(
        refs: References,
        stop: StopReference?,
        routeFilter: Set<String>
    ): List<RouteFilterOption> {
        val routeIds = stop?.routeIds ?: return emptyList()
        return routeIds.mapNotNull { refs.route(it) }.map { route ->
            RouteFilterOption(
                routeId = route.id,
                displayName = getRouteDisplayName(route.shortName, route.longName),
                checked = routeFilter.contains(route.id)
            )
        }
    }

    override suspend fun setStopFavorite(stopId: String, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId)
            ObaContract.Stops.markAsFavorite(context, uri, favorite)
        }
    }

    override suspend fun favoriteRoute(
        routeId: String,
        headsign: String?,
        stopId: String?,
        shortName: String?,
        longName: String?,
        favorite: Boolean
    ) = withContext(Dispatchers.IO) {
        val regionId = regionRepository.region.value?.id
        // Ensure the route row exists (stamped with the current region) before marking the favorite.
        val values = ContentValues().apply {
            put(ObaContract.Routes.SHORTNAME, shortName)
            put(ObaContract.Routes.LONGNAME, longName)
            regionId?.let { put(ObaContract.Routes.REGION_ID, it) }
        }
        ObaContract.Routes.insertOrUpdate(context, routeId, values, true)
        ObaContract.RouteHeadsignFavorites.markAsFavorite(context, routeId, headsign, stopId, favorite)

        // Backfill the full route details so the long name can be shown later (was an AsyncTaskLoader).
        fetchAndStoreRouteDetails(routeId, regionId)
    }

    /** Route-details fetch via the modernized client; writes name/url back. */
    private suspend fun fetchAndStoreRouteDetails(routeId: String, regionId: Long?) {
        val route = routeRepository.getRoute(routeId).getOrNull() ?: return

        var shortName = route.shortName
        var longName = route.longName
        if (shortName.isNullOrEmpty()) {
            shortName = longName
        }
        if (longName.isNullOrEmpty() || shortName == longName) {
            longName = route.description
        }

        val values = ContentValues().apply {
            put(ObaContract.Routes.SHORTNAME, shortName)
            put(ObaContract.Routes.LONGNAME, longName)
            put(ObaContract.Routes.URL, route.url)
            regionId?.let { put(ObaContract.Routes.REGION_ID, it) }
        }
        ObaContract.Routes.insertOrUpdate(context, route.id, values, true)
    }

    override suspend fun setRouteFilter(stopId: String, filter: Set<String>) {
        withContext(Dispatchers.IO) {
            ObaContract.StopRouteFilters.set(context, stopId, ArrayList(filter))
        }
    }

    override suspend fun setArrivalStyle(style: Int) {
        withContext(Dispatchers.IO) {
            BuildFlavorUtils.setArrivalInfoStyle(context, style)
        }
    }

    override suspend fun hideAlerts(ids: List<String>) {
        withContext(Dispatchers.IO) {
            for (id in ids) {
                ObaContract.ServiceAlerts.insertOrUpdate(id, ContentValues(), false, true)
            }
        }
    }

    override suspend fun showAllAlerts() {
        withContext(Dispatchers.IO) {
            ObaContract.ServiceAlerts.showAllAlerts()
        }
    }

    override fun alertDetails(id: String): AlertDetails? =
        lastGood?.data?.references?.situation(id)?.let {
            AlertDetails(it.id, it.summary.value, it.description.value, it.url.value)
        }

    override fun lastLoaded(): ArrivalsLoaded? {
        val data = lastGood?.data ?: return null
        // Adapt the focused stop + all referenced routes into the legacy element types the map
        // subsystem still consumes (its own migration is a separate, deferred campaign).
        val stop = data.references.stop(data.entry.stopId)?.let(::DtoStop)
        val routes = data.references.routes.map(::DtoRoute)
        return ArrivalsLoaded(stop, routes, data.entry.arrivalsAndDepartures.isNotEmpty())
    }

    companion object {

        const val MINUTES_AFTER_DEFAULT = 65

        const val MINUTES_AFTER_INCREMENT = 60

        const val MINUTES_AFTER_MAX = 1440

        private fun severityOf(severity: String?): AlertSeverity = when (severity) {
            ObaSituation.SEVERITY_NO_IMPACT -> AlertSeverity.INFO
            ObaSituation.SEVERITY_SEVERE, ObaSituation.SEVERITY_VERY_SEVERE -> AlertSeverity.ERROR
            else -> AlertSeverity.WARNING
        }
    }
}

/**
 * Adapts a [StopReference] DTO to the legacy [ObaStop] the map subsystem consumes (recentering +
 * marker). A localized bridge at the arrivals→map boundary; the map's own migration off the element
 * types is a separate, deferred campaign.
 */
private class DtoStop(private val s: StopReference) : ObaStop {
    override val id: String get() = s.id
    override val stopCode: String? get() = s.code
    override val name: String? get() = s.name
    override val location: Location get() = LocationUtils.makeLocation(s.lat, s.lon)
    override val latitude: Double get() = s.lat
    override val longitude: Double get() = s.lon
    override val direction: String? get() = s.direction
    override val locationType: Int get() = s.locationType
    override val routeIds: Array<String> get() = s.routeIds.toTypedArray()
}

/** Adapts a [RouteReference] DTO to the legacy [ObaRoute] the map subsystem consumes. */
private class DtoRoute(private val r: RouteReference) : ObaRoute {
    override val id: String get() = r.id
    override val shortName: String? get() = r.shortName
    override val longName: String? get() = r.longName
    override val description: String? get() = r.description
    override val type: Int get() = r.type
    override val url: String? get() = r.url
    override val color: Int? get() = r.colorArgb()
    override val textColor: Int? get() = r.textColorArgb()
    override val agencyId: String get() = r.agencyId
}
