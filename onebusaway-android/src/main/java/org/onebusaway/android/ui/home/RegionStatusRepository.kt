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
package org.onebusaway.android.ui.home

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onebusaway.android.BuildConfig
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.RegionUtils

/**
 * Outcome of a region-status refresh, replacing the int `currentRegionChanged` flag the legacy
 * [org.onebusaway.android.region.ObaRegionsTask] passed to its callback. The repository performs
 * only the model writes the task did (`Application.setCurrentRegion` + the auto-select
 * preference); the *effects* the task ran inline — the progress dialog, the manual-region picker,
 * the region-found toast, analytics — are left to the caller (the HomeViewModel, P8) to map from
 * this value.
 */
sealed interface RegionStatus {

    /** A custom API URL is set, so no region info is needed. */
    object Skipped : RegionStatus

    /** The build flavor hard-codes a region; it was set and auto-selection was disabled. */
    data class Fixed(val region: ObaRegion) : RegionStatus

    /** The current region was auto-selected or changed to [region]. */
    data class Changed(val region: ObaRegion) : RegionStatus

    /** A region was already set and remains the best match (contents refreshed silently). */
    object Unchanged : RegionStatus

    /** No region is set and none could be auto-selected, so the user must pick one. */
    object NeedsManualSelection : RegionStatus

    /** Region info could not be loaded from any source (catastrophic failure). */
    object Failed : RegionStatus
}

/** Refreshes region info from the Regions REST API and resolves the current region. */
interface RegionStatusRepository {

    suspend fun refreshRegions(): RegionStatus
}

/**
 * Default implementation porting `HomeActivity.checkRegionStatus()` plus
 * [org.onebusaway.android.region.ObaRegionsTask]'s `doInBackground` + `onPostExecute` selection
 * into a single suspend call. Location is read via [Application.getLastKnownLocation] with a null
 * client (it falls back to the platform `LocationManager`), so this does not depend on the
 * deprecated `GoogleApiClient`.
 */
class DefaultRegionStatusRepository(private val context: Context) : RegionStatusRepository {

    override suspend fun refreshRegions(): RegionStatus = withContext(Dispatchers.IO) {
        val app = Application.get()

        // A custom API URL means we don't use the Regions API at all.
        if (app.customApiUrl?.isNotEmpty() == true) {
            return@withContext RegionStatus.Skipped
        }

        // A build flavor may hard-code its region; set it and disable auto-selection.
        if (BuildConfig.USE_FIXED_REGION) {
            val region = RegionUtils.getRegionFromBuildFlavor()
            RegionUtils.saveToProvider(context, listOf(region))
            app.setCurrentRegion(region)
            PreferenceUtils.saveBoolean(
                context.getString(R.string.preference_key_auto_select_region), false
            )
            return@withContext RegionStatus.Fixed(region)
        }

        // Force a server reload when we have no region, the cache has expired, or the app updated.
        val newVer = appVersionCode()
        val oldVer = Application.getPrefs().getInt(CHECK_REGION_VER, 0)
        val force = shouldForceReload(
            hasRegion = app.currentRegion != null,
            lastUpdate = app.lastRegionUpdateDate,
            now = System.currentTimeMillis(),
            oldVer = oldVer,
            newVer = newVer
        )
        PreferenceUtils.saveInt(CHECK_REGION_VER, newVer)

        val results = RegionUtils.getRegions(context, force) ?: return@withContext RegionStatus.Failed

        val autoSelect = Application.getPrefs()
            .getBoolean(context.getString(R.string.preference_key_auto_select_region), true)
        // getClosestRegion uses Location.distanceTo, so only compute it when auto-selecting.
        val closest = if (autoSelect) {
            RegionUtils.getClosestRegion(results, Application.getLastKnownLocation(context, null), true)
        } else {
            null
        }

        val status = resolveRegionStatus(app.currentRegion, closest, autoSelect)
        if (autoSelect) {
            when {
                status is RegionStatus.Changed -> app.setCurrentRegion(status.region)
                // Same region as before: refresh its contents without signalling a change.
                status is RegionStatus.Unchanged && closest != null ->
                    app.setCurrentRegion(closest, false)
            }
        }
        status
    }

    @Suppress("DEPRECATION")
    private fun appVersionCode(): Int = try {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_META_DATA).versionCode
    } catch (e: PackageManager.NameNotFoundException) {
        0
    }
}

/** One week, in milliseconds — the staleness window after which region info is reloaded. */
internal const val REGION_UPDATE_THRESHOLD_MS = 1000L * 60 * 60 * 24 * 7

/** Mirrors `HomeActivity`'s `checkRegionVer` preference key so the same slot is read/written. */
private const val CHECK_REGION_VER = "checkRegionVer"

/**
 * The pure force-reload decision from `HomeActivity.checkRegionStatus()`: reload if there is no
 * region yet, the cache is older than [REGION_UPDATE_THRESHOLD_MS], or the app version increased.
 * [now] is passed in so this stays a stateless helper.
 */
internal fun shouldForceReload(
    hasRegion: Boolean,
    lastUpdate: Long,
    now: Long,
    oldVer: Int,
    newVer: Int
): Boolean = !hasRegion || (now - lastUpdate > REGION_UPDATE_THRESHOLD_MS) || (oldVer < newVer)

/**
 * The pure region-selection branches from `ObaRegionsTask.onPostExecute` (regions compared by id,
 * matching `ObaRegionElement.equals`). [closest] is precomputed by the caller — it is null when
 * auto-selection is off or no usable region is within range.
 */
internal fun resolveRegionStatus(
    current: ObaRegion?,
    closest: ObaRegion?,
    autoSelect: Boolean
): RegionStatus {
    if (!autoSelect) {
        return if (current == null) RegionStatus.NeedsManualSelection else RegionStatus.Unchanged
    }
    return when {
        current == null && closest != null -> RegionStatus.Changed(closest)
        current == null -> RegionStatus.NeedsManualSelection
        closest != null && current.id != closest.id -> RegionStatus.Changed(closest)
        else -> RegionStatus.Unchanged
    }
}
