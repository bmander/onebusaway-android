/*
 * Copyright (C) 2010-2017 Brian Ferris (bdferris@onebusaway.org),
 * University of South Florida (sjbarbeau@gmail.com),
 * Microsoft Corporation,
 * Open Transit Software Foundation
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
package org.onebusaway.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.StateFlow
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.ui.compose.components.ObaTopAppBar
import org.onebusaway.android.ui.compose.findActivity

private const val SETTINGS_FRAGMENT_TAG = "settingsRoot"

/**
 * The settings NavHost destination (Campaign C; former `SettingsActivity`). It hosts the existing
 * preference fragments ([SettingsFragment] + the nested [AdvancedSettingsFragment]) inside a Compose
 * [FragmentContainerView] using the host activity's `supportFragmentManager`.
 *
 * Which fragment is shown is driven by [nestedFragmentClass] (set via [HomeActivity.showSettingsSubScreen]
 * when the Advanced preference is tapped — an explicit `onPreferenceClick`, not the framework's
 * `app:fragment` path — and cleared by back) rather than the FragmentManager's own back stack — this
 * keeps back handling unambiguous in a single-Activity
 * NavHost (a [BackHandler] enabled only on a sub-screen returns to the root; on the root, the system
 * back falls through to the NavHost, which pops the whole destination). The destination stays composed
 * across that sub-navigation, so the former Activity's `onDestroy` side effect (re-home when
 * auto-select-region was re-enabled / the OTP custom URL changed) fires only on truly leaving settings.
 */
@Composable
fun SettingsDestination(
    navController: NavController,
    nestedFragmentClass: StateFlow<String?>,
    onClearNested: () -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    val fragmentManager = activity.supportFragmentManager
    val nested by nestedFragmentClass.collectAsStateWithLifecycle()

    // Swap the hosted fragment to match [nested]: the root SettingsFragment when null, otherwise the
    // sub-screen fragment named by the tapped preference. No addToBackStack — back is driven below.
    LaunchedEffect(nested) {
        if (fragmentManager.isStateSaved) return@LaunchedEffect
        val fragment = if (nested == null) {
            SettingsFragment()
        } else {
            fragmentManager.fragmentFactory.instantiate(activity.classLoader, nested!!)
        }
        fragmentManager.commit {
            replace(R.id.settings_container, fragment, SETTINGS_FRAGMENT_TAG)
        }
    }

    // On a sub-screen, back returns to the root settings; on the root, this is disabled so the system
    // back falls through to the NavHost (pops the settings destination → home).
    BackHandler(enabled = nested != null) { onClearNested() }

    // Port of the Activity's onCreate "show check region dialog" + onDestroy "re-home" side effects.
    // Capture the auto-select-region pref on enter; on leave (truly leaving settings, since this stays
    // composed across sub-navigation), re-home if it was re-enabled or the OTP custom URL was changed.
    DisposableEffect(Unit) {
        val autoSelectKey = activity.getString(R.string.preference_key_auto_select_region)
        val autoSelectInitial = Application.getPrefs().getBoolean(autoSelectKey, true)

        if (activity.intent.getBooleanExtra(SettingsActivity.SHOW_CHECK_REGION_DIALOG, false)) {
            showCheckRegionDialog(activity)
            // Clear so a configuration change / re-entry doesn't re-show it.
            activity.intent.removeExtra(SettingsActivity.SHOW_CHECK_REGION_DIALOG)
        }

        onDispose {
            onClearNested()
            if (!fragmentManager.isStateSaved) {
                fragmentManager.findFragmentByTag(SETTINGS_FRAGMENT_TAG)?.let { existing ->
                    fragmentManager.commit { remove(existing) }
                }
            }

            val autoSelectCurrent = Application.getPrefs().getBoolean(autoSelectKey, true)
            if (autoSelectCurrent && !autoSelectInitial) {
                NavHelp.goHome(activity, false)
            } else if (activity is HomeActivity && activity.otpCustomAPIUrlChanged) {
                activity.setOtpCustomAPIUrlChanged(false)
                NavHelp.goHome(activity, false)
            }
        }
    }

    Scaffold(
        topBar = {
            ObaTopAppBar(
                title = stringResource(
                    if (nested == null) R.string.navdrawer_item_settings
                    else R.string.preferences_category_advanced
                ),
                onBack = { if (nested != null) onClearNested() else navController.popBackStack() }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    FragmentContainerView(context).apply { id = R.id.settings_container }
                }
            )
        }
    }
}

/** Port of the former SettingsActivity.showCheckRegionDialog. */
private fun showCheckRegionDialog(activity: android.app.Activity) {
    val obaRegion = Application.get().currentRegion ?: return
    MaterialAlertDialogBuilder(activity)
        .setTitle(activity.getString(R.string.preference_region_dialog_title))
        .setMessage(
            activity.getString(R.string.preference_region_dialog_message, obaRegion.name)
        )
        .setPositiveButton(android.R.string.ok) { _, _ -> }
        .show()
}
