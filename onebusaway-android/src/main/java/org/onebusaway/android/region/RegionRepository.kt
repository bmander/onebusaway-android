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
package org.onebusaway.android.region

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.onebusaway.android.BuildConfig
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.util.RegionUtils

/**
 * The observable current region — the reactive replacement for reading `Application.currentRegion`
 * statically — and (Campaign A, A1) the owner of region *resolution*. Features that must react to a
 * region change (weather, wide alerts, the survey/what's-new gate, the nav-drawer items, the map's
 * re-centering) collect [region]/[state]; the resolution action ([refresh]/[choose]) lives here too,
 * folding in the former `RegionStatusRepository`.
 *
 * One process-singleton instance is held on `Application` (mirroring `getGtfsAlerts()`), so every view
 * model shares the same flow; tests substitute a fake. It lives in the neutral `region` package so both
 * the map and the home UI can depend on it without a backward dependency.
 */
interface RegionRepository {

    /** The current region, or null when none is set (e.g. a custom API URL is configured). */
    val region: StateFlow<ObaRegion?>

    /**
     * The richer resolution state (Campaign A) — what the UI can render directly: a refresh in flight,
     * an active region, a manual choice required, or a failure. [region] mirrors this flow's
     * [RegionState.Active] region (and keeps its last value while [RegionState.Resolving]).
     */
    val state: StateFlow<RegionState>

    /**
     * Resolves the current region (Regions REST API + closest-match auto-select), applies the result via
     * `RegionActivator`, updates [state]/[region], and returns the one-shot [RegionStatus] outcome for
     * the caller's effects (toast, picker, analytics). Replaces `RegionStatusRepository.refreshRegions`.
     */
    suspend fun refresh(): RegionStatus

    /** Sets the region the user picked from the manual-selection dialog (the old picker's onClick). */
    suspend fun choose(region: ObaRegion)

    /**
     * Clears the current region — a custom API URL was entered, or an experimental region was disabled.
     * The non-null counterpart to [choose]; unlike resolution it does no IO (just the activation
     * transaction + a state publish), so it is synchronous and callable from non-coroutine Java writers.
     */
    fun clear()

    /**
     * Syncs the observable state after a region write that bypassed [refresh]/[choose] — now only the
     * instrumented-test seam `Application.setCurrentRegion` (production writers all route through
     * [refresh]/[choose]/[clear] as of A4). It does not run the activation transaction (the caller
     * already did); it only updates the flows.
     */
    fun syncActivated(region: ObaRegion?)
}

/**
 * The reactive region resolution state, superseding the bare nullable [RegionRepository.region]:
 * what the UI can render directly. [RegionRepository.region] mirrors the [Active] region.
 */
sealed interface RegionState {

    /** A region resolution is in flight and no result is available yet. */
    object Resolving : RegionState

    /** A region is set. [region] is null only when a custom API URL is configured (no region needed). */
    data class Active(val region: ObaRegion?) : RegionState

    /** No region could be auto-selected; the user must pick one from [regions] (usable, name-sorted). */
    data class NeedsManualChoice(val regions: List<ObaRegion>) : RegionState

    /** Region info could not be loaded from any source (catastrophic failure). */
    object Failed : RegionState
}

/** Mirrors `HomeActivity`'s `checkRegionVer` preference key so the same slot is read/written. */
private const val CHECK_REGION_VER = "checkRegionVer"

/**
 * Default implementation. The observable state lives in a [RegionStateHolder] (so its transitions stay
 * JVM-testable); resolution ([refresh]) is the `Context`-coupled IO ported from
 * `DefaultRegionStatusRepository.refreshRegions`, calling [RegionActivator] (not
 * `Application.setCurrentRegion`) to apply the model writes. A Hilt `@Singleton` (A2): constructed
 * lazily on first injection — which is after `Application.onCreate` finishes, so the eager seed below
 * reads the region `initObaRegion` already loaded (no `@Singleton` injects this during onCreate). The
 * legacy writers reach it through `RegionEntryPoint` to [syncActivated] their state.
 */
@Singleton
class DefaultRegionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activator: RegionActivator,
    private val prefs: PreferencesRepository,
) : RegionRepository {

    private val holder = RegionStateHolder(activator.currentRegion())

    override val region: StateFlow<ObaRegion?> get() = holder.region

    override val state: StateFlow<RegionState> get() = holder.state

    override fun syncActivated(region: ObaRegion?) = holder.activated(region)

    override suspend fun refresh(): RegionStatus = withContext(Dispatchers.IO) {
        holder.resolving()

        // A custom API URL means we don't use the Regions API at all (region stays null).
        if (prefs.getString(R.string.preference_key_oba_api_url, null)?.isNotEmpty() == true) {
            holder.activated(null)
            return@withContext RegionStatus.Skipped
        }

        // A build flavor may hard-code its region; set it and disable auto-selection.
        if (BuildConfig.USE_FIXED_REGION) {
            val region = RegionUtils.getRegionFromBuildFlavor()
            RegionUtils.saveToProvider(context, listOf(region))
            activator.activate(region, true)
            holder.activated(region)
            prefs.setBoolean(R.string.preference_key_auto_select_region, false)
            return@withContext RegionStatus.Fixed(region)
        }

        // Force a server reload when we have no region, the cache has expired, or the app updated.
        val current = activator.currentRegion()
        val newVer = appVersionCode()
        val force = shouldForceReload(
            hasRegion = current != null,
            lastUpdate = prefs.getLong(R.string.preference_key_last_region_update, 0),
            now = System.currentTimeMillis(),
            oldVer = prefs.getInt(CHECK_REGION_VER, 0),
            newVer = newVer
        )
        prefs.setInt(CHECK_REGION_VER, newVer)

        val results = RegionUtils.getRegions(context, force)
        if (results == null) {
            holder.failed()
            return@withContext RegionStatus.Failed
        }

        val autoSelect = prefs.getBoolean(R.string.preference_key_auto_select_region, true)
        // getClosestRegion uses Location.distanceTo, so only compute it when auto-selecting.
        val closest = if (autoSelect) {
            RegionUtils.getClosestRegion(results, Application.getLastKnownLocation(context), true)
        } else {
            null
        }

        when (val status = resolveRegionStatus(current, closest, autoSelect)) {
            is RegionStatus.Changed -> {
                activator.activate(status.region, true)
                holder.activated(status.region)
                status
            }
            // Same region as before: refresh its contents silently (auto-select only).
            RegionStatus.Unchanged -> {
                if (autoSelect && closest != null) {
                    activator.activate(closest, false)
                    holder.activated(closest)
                } else {
                    holder.activated(current) // clear the transient Resolving; region is unchanged
                }
                status
            }
            is RegionStatus.NeedsManualSelection -> {
                // Attach the picker list (usable regions, name-sorted) to the decision sentinel.
                val regions = results.filter { RegionUtils.isRegionUsable(it) }.sortedBy { it.name }
                holder.needsChoice(regions)
                RegionStatus.NeedsManualSelection(regions)
            }
            else -> status // Skipped / Fixed are returned earlier; nothing else reaches here.
        }
    }

    override suspend fun choose(region: ObaRegion) = withContext(Dispatchers.IO) {
        activator.activate(region, true)
        holder.activated(region)
    }

    override fun clear() {
        activator.activate(null, true)
        holder.activated(null)
    }

    @Suppress("DEPRECATION")
    private fun appVersionCode(): Int = try {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_META_DATA).versionCode
    } catch (e: PackageManager.NameNotFoundException) {
        0
    }
}
