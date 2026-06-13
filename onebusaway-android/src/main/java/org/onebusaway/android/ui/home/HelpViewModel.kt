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

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.onebusaway.android.app.Application
import org.onebusaway.android.util.PreferenceUtils

/** Which help dialog is showing. Split out of the shared HomeDialog when help became a feature module. */
sealed interface HelpDialog {
    object None : HelpDialog
    object Menu : HelpDialog
    object WhatsNew : HelpDialog
    object Legend : HelpDialog
}

/** The help feature's state: which dialog is up + whether the menu offers "contact us". */
data class HelpUiState(val dialog: HelpDialog = HelpDialog.None, val showContactUs: Boolean = true)

/**
 * Owns the help / what's-new / legend dialogs as a feature module (mirrors the other home feature
 * modules). Holds only the dialog state + the what's-new version check; the menu *actions* that do
 * things (reset tutorials, agencies, Twitter, contact us) are genuine Activity operations and stay in
 * HomeActivity, reached via the `onHelpAction` callback [HelpFeature] forwards.
 */
class HelpViewModel : ViewModel() {

    private val _state = MutableStateFlow(HelpUiState())
    val state: StateFlow<HelpUiState> = _state.asStateFlow()

    /** Open the help menu (from the nav drawer's Help item). */
    fun showMenu(showContactUs: Boolean) =
        _state.update { it.copy(dialog = HelpDialog.Menu, showContactUs = showContactUs) }

    fun showWhatsNew() = _state.update { it.copy(dialog = HelpDialog.WhatsNew) }

    fun showLegend() = _state.update { it.copy(dialog = HelpDialog.Legend) }

    fun dismiss() = _state.update { it.copy(dialog = HelpDialog.None) }

    /**
     * Show "What's New" if a newer version was just installed; returns whether it was (the activity uses
     * that to refresh the region-gated drawer items). Application-backed, so it isn't unit-tested.
     */
    @Suppress("DEPRECATION")
    fun maybeAutoShowWhatsNew(): Boolean {
        val settings = Application.getPrefs()
        val appInfo = try {
            Application.get().packageManager
                .getPackageInfo(Application.get().packageName, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val newVer = appInfo.versionCode
        if (settings.getInt(WHATS_NEW_VER, 0) < newVer) {
            showWhatsNew()
            PreferenceUtils.saveInt(WHATS_NEW_VER, newVer)
            return true
        }
        return false
    }

    private companion object {
        const val WHATS_NEW_VER = "whatsNewVer"
    }
}
