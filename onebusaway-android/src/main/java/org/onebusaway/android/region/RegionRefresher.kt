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

import android.app.ProgressDialog
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.app.di.RegionEntryPoint
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.util.ViewUtils

/**
 * Java→coroutine bridge for triggering a region refresh from non-suspend, non-injectable callers — the
 * Settings "experimental regions" toggle and backup-restore — replacing the deleted `ObaRegionsTask`
 * AsyncTask (Campaign A, A3). The resolution itself now lives in [RegionRepository.refresh]; this just
 * re-hosts the task's UI glue: an optional progress dialog, the manual-region picker (the task's
 * `haveUserChooseRegion`), and a completion [Callback]. The task's 100 ms callback delay is gone —
 * `refresh()`/`choose()` set and publish the region before returning.
 */
object RegionRefresher {

    /** Replaces `ObaRegionsTask.Callback`. Java callers pass a lambda / method reference. */
    fun interface Callback {
        fun onRegionTaskFinished(currentRegionChanged: Boolean)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Refreshes/resolves the current region. Shows a progress dialog when [progressMessage] is non-null,
     * raises the manual-region picker when no region can be auto-selected, and reports the outcome to
     * [onFinished] (`true` only when the current region changed).
     */
    @JvmStatic
    @JvmOverloads
    @Suppress("DEPRECATION") // ProgressDialog — preserved from ObaRegionsTask's restore UX.
    fun refresh(context: Context, progressMessage: String? = null, onFinished: Callback? = null) {
        val repo = RegionEntryPoint.get(context)
        val dialog = if (progressMessage != null && ViewUtils.canManageDialog(context)) {
            ProgressDialog.show(context, "", progressMessage, true).apply {
                setIndeterminate(true)
                setCancelable(false)
            }
        } else {
            null
        }
        scope.launch {
            val status = repo.refresh()
            dialog?.dismiss()
            when (status) {
                is RegionStatus.Changed -> onFinished?.onRegionTaskFinished(true)
                is RegionStatus.NeedsManualSelection -> chooseRegion(context, status.regions, repo, onFinished)
                RegionStatus.Failed -> Unit // catastrophic load failure (the task returned early too)
                else -> onFinished?.onRegionTaskFinished(false) // Unchanged / Fixed / Skipped
            }
        }
    }

    /** The forced-choice picker (the task's `haveUserChooseRegion`); [regions] is already usable-filtered, name-sorted. */
    private fun chooseRegion(
        context: Context,
        regions: List<ObaRegion>,
        repo: RegionRepository,
        onFinished: Callback?,
    ) {
        if (!ViewUtils.canManageDialog(context) || regions.isEmpty()) return
        val names = regions.map { it.name }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.region_choose_region))
            .setCancelable(false)
            .setItems(names) { _, which ->
                scope.launch {
                    repo.choose(regions[which])
                    onFinished?.onRegionTaskFinished(true)
                }
            }
            .show()
    }
}
