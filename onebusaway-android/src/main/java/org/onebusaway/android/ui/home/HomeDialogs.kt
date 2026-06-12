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

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onebusaway.android.R
import org.onebusaway.android.ui.arrivals.EtaContent

/** Which Home dialog is showing (replacing the deprecated showDialog/onCreateDialog ids). */
enum class HomeDialog { NONE, HELP, WHATS_NEW, LEGEND, DISMISS_DONATION }

/**
 * The help-menu options, in the order of the `main_help_options` string-array. The activity carries
 * each out (reset tutorials, show legend, show what's-new, agencies, Twitter, contact us).
 */
enum class HelpAction { TUTORIALS, LEGEND, WHATS_NEW, AGENCIES, TWITTER, CONTACT_US }

/**
 * Renders all of Home's Compose dialogs (HELP / WHAT'S-NEW / LEGEND / DISMISS-DONATION), keyed by the
 * single [HomeUiState.dialog] state. State + actions are driven by the activity through HomeScreen.
 */
@Composable
fun HomeDialogs(
    dialog: HomeDialog,
    showContactUs: Boolean,
    onHelpAction: (HelpAction) -> Unit,
    onWhatsNewDismissed: () -> Unit,
    onDonationDismissForever: () -> Unit,
    onDonationRemindLater: () -> Unit,
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
        HomeDialog.LEGEND -> LegendDialog(onDismiss)
        HomeDialog.DISMISS_DONATION -> DonationDismissDialog(
            onDismissForever = onDonationDismissForever,
            onRemindLater = onDonationRemindLater,
            onDismiss = onDismiss
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

/**
 * The arrival-color legend. Each row reuses the real arrivals' [EtaContent] so the sample matches
 * what a stop's ETA actually looks like (a color-coded number + the pulsing real-time dot), beside
 * the explanation. Order mirrors the legacy legend: on-time / early / late / scheduled / canceled.
 */
@Composable
private fun LegendDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.main_help_legend_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LegendRow(R.color.stop_info_ontime, predicted = true, label = R.string.main_help_legend_ontime)
                LegendRow(R.color.stop_info_early, predicted = true, label = R.string.main_help_legend_early)
                LegendRow(R.color.stop_info_delayed, predicted = true, label = R.string.main_help_legend_late)
                LegendRow(R.color.stop_info_scheduled_time, predicted = false, label = R.string.main_help_legend_scheduled)
                LegendRow(
                    R.color.stop_info_scheduled_time,
                    predicted = false,
                    canceled = true,
                    label = R.string.main_help_legend_canceled
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.main_help_close)) }
        }
    )
}

@Composable
private fun LegendRow(
    @ColorRes color: Int,
    predicted: Boolean,
    @StringRes label: Int,
    canceled: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EtaContent(eta = 5L, color = colorResource(color), predicted = predicted, canceled = canceled)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Confirmation when the user closes the donation card. Three stacked actions (the Compose idiom for
 * >2 dialog buttons): keep asking later, stop asking, or cancel.
 */
@Composable
private fun DonationDismissDialog(
    onDismissForever: () -> Unit,
    onRemindLater: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.donation_dismiss_dialog_title)) },
        text = {
            Text(stringResource(R.string.donation_dismiss_dialog_body, stringResource(R.string.app_name)))
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onRemindLater(); onDismiss() }) {
                    Text(stringResource(R.string.donation_dismiss_dialog_remind_me_later_button))
                }
                TextButton(onClick = { onDismissForever(); onDismiss() }) {
                    Text(stringResource(R.string.donation_dismiss_dialog_dont_want_to_help_button))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.donation_dismiss_dialog_cancel_button))
                }
            }
        }
    )
}
