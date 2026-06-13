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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onebusaway.android.R

/**
 * The donation prompt overlaid near the top of the map, replacing the XML donation_view include.
 * The title carries the app name for white-label brands; the close / learn-more / donate actions are
 * dispatched to HomeActivity (which keeps the DonationsManager logic + the dismiss dialog).
 */
@Composable
fun DonationCard(
    onClose: () -> Unit,
    onLearnMore: () -> Unit,
    onDonate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = colorResource(R.color.theme_primary)
    Card(shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = stringResource(R.string.donation_view_title, stringResource(R.string.app_name)),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(top = 4.dp, start = 4.dp, end = 4.dp)
                )
                IconButton(onClick = onClose) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.close_donation_card),
                        tint = colorResource(R.color.body_text_1)
                    )
                }
            }
            Text(
                text = stringResource(R.string.donation_view_body),
                modifier = Modifier.padding(4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onLearnMore, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.donation_view_learn_more_button), color = primary)
                }
                Button(
                    onClick = onDonate,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = primary, contentColor = Color.White)
                ) {
                    Text(stringResource(R.string.donation_view_donate_now_button))
                }
            }
        }
    }
}
