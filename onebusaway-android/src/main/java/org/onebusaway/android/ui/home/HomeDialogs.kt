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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onebusaway.android.R

/** Which Home dialog is showing (replacing the deprecated showDialog/onCreateDialog ids). */
enum class HomeDialog { NONE, HELP, WHATS_NEW }

/**
 * The help-menu options, in the order of the `main_help_options` string-array. The activity carries
 * each out (reset tutorials, show legend, show what's-new, agencies, Twitter, contact us).
 */
enum class HelpAction { TUTORIALS, LEGEND, WHATS_NEW, AGENCIES, TWITTER, CONTACT_US }

/**
 * Renders the Compose HELP and WHAT'S-NEW dialogs (the LEGEND dialog stays a hosted
 * MaterialAlertDialogBuilder owned by HomeActivity). State + actions are driven by the activity
 * through HomeShellHost.
 */
@Composable
fun HomeDialogs(
    dialog: HomeDialog,
    showContactUs: Boolean,
    onHelpAction: (HelpAction) -> Unit,
    onWhatsNewDismissed: () -> Unit,
    onDismiss: () -> Unit
) {
    when (dialog) {
        HomeDialog.HELP -> HelpDialog(showContactUs, onHelpAction, onDismiss)
        HomeDialog.WHATS_NEW -> WhatsNewDialog(
            onDismiss = {
                onDismiss()
                onWhatsNewDismissed()
            }
        )
        HomeDialog.NONE -> Unit
    }
}

@Composable
private fun HelpDialog(
    showContactUs: Boolean,
    onHelpAction: (HelpAction) -> Unit,
    onDismiss: () -> Unit
) {
    val options = stringArrayResource(
        if (showContactUs) R.array.main_help_options else R.array.main_help_options_no_contact_us
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.main_help_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onHelpAction(HelpAction.entries[index])
                            }
                            .padding(vertical = 16.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.main_help_close)) }
        }
    )
}

@Composable
private fun WhatsNewDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.main_help_whatsnew_title)) },
        text = {
            Text(
                text = stringResource(R.string.main_help_whatsnew),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.main_help_close)) }
        }
    )
}
