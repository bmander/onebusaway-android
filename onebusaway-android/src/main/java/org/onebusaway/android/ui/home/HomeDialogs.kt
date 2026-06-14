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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.onebusaway.android.R
import org.onebusaway.android.io.elements.ObaRegion

/**
 * Which shared Home dialog is showing. The help / what's-new / legend dialogs are their own feature
 * module now (see [HelpViewModel] / [HelpFeature]); only the forced-choice region picker remains here.
 */
sealed interface HomeDialog {
    object None : HomeDialog

    /** The forced-choice region picker (old ObaRegionsTask.haveUserChooseRegion), keyed by [regions]. */
    data class ChooseRegion(val regions: List<ObaRegion>) : HomeDialog
}

/** Renders the shared Home dialogs, keyed by [HomeUiState.dialog] — just the region picker. */
@Composable
fun HomeDialogs(dialog: HomeDialog, onRegionChosen: (ObaRegion) -> Unit) {
    when (dialog) {
        is HomeDialog.ChooseRegion -> RegionChooserDialog(dialog.regions, onRegionChosen)
        HomeDialog.None -> Unit
    }
}

/**
 * A region-wide GTFS alert (old `GtfsAlertsHelper.showWideAlertDialog`): a non-dismissible warning
 * dialog driven by [HomeUiState.wideAlert]. "More info" opens [WideAlert.url] in a browser (shown
 * only when a url is present) and dismisses; "Dismiss" just clears it. Back/scrim do nothing, mirroring
 * the legacy `setCancelable(false)`.
 */
@Composable
fun WideAlertDialog(alert: WideAlert, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = {
            Icon(painterResource(R.drawable.baseline_warning_24), contentDescription = null)
        },
        title = { Text(alert.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(alert.message)
            }
        },
        confirmButton = {
            if (alert.url != null) {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(alert.url)))
                    onDismiss()
                }) { Text(stringResource(R.string.more_info)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        },
    )
}

/**
 * The forced-choice region picker (old ObaRegionsTask.haveUserChooseRegion): a non-dismissible
 * dialog of usable regions (pre-filtered + sorted by the repository). The user must pick one — there
 * is no cancel, and back/scrim do nothing, since the app can't function without a region.
 */
@Composable
private fun RegionChooserDialog(regions: List<ObaRegion>, onRegionChosen: (ObaRegion) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.region_choose_region)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                regions.forEach { region ->
                    Text(
                        text = region.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRegionChosen(region) }
                            .padding(vertical = 16.dp)
                    )
                }
            }
        },
        confirmButton = { }
    )
}
