/*
 * Copyright (C) 2010-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South  Florida (sjbarbeau@gmail.com), Microsoft Corporation
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
@file:JvmName("StopDetailsDialog")

package org.onebusaway.android.ui.arrivals

import android.content.Context
import android.text.TextUtils
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.util.Pair
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onebusaway.android.R
import org.onebusaway.android.util.DisplayFormat
import org.onebusaway.android.util.formatRouteDisplayNames

/**
 * Generates the dialog text used to show detailed information about a particular stop.
 *
 * @return a pair of Strings consisting of the <dialog title, dialog message>
 */
fun createStopDetailsDialogText(
    context: Context,
    stopName: String?,
    stopUserName: String?,
    stopCode: String?,
    stopDirection: String?,
    routeDisplayNames: List<String>?
): Pair<String, String> {
    val newLine = "\n"
    var title = ""
    val message = StringBuilder()

    if (!TextUtils.isEmpty(stopUserName)) {
        title = stopUserName!!
        if (stopName != null) {
            // Show official stop name in addition to user name
            message.append(context.getString(R.string.stop_info_official_stop_name_label, stopName))
                .append(newLine)
        }
    } else if (stopName != null) {
        title = stopName
    }

    if (stopCode != null) {
        message.append(context.getString(R.string.stop_details_code, stopCode) + newLine)
    }

    // Routes that serve this stop
    if (routeDisplayNames != null) {
        val routes = context.getString(R.string.stop_info_route_ids_label) + " " +
            formatRouteDisplayNames(routeDisplayNames, emptyList())
        message.append(routes)
    }

    if (!TextUtils.isEmpty(stopDirection)) {
        message.append(newLine)
            .append(context.getString(DisplayFormat.getStopDirectionText(stopDirection!!)))
    }
    return Pair(title, message.toString())
}

/**
 * Renders the stop-details dialog when the [viewModel] has it visible and content is loaded. Hosted
 * by both the standalone arrivals screen and the map panel so the overflow "show stop details"
 * action works in either. The text generation lives in [createStopDetailsDialogText]; this is pure UI.
 */
@Composable
internal fun StopDetailsHost(viewModel: ArrivalsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visible by viewModel.stopDetailsVisible.collectAsStateWithLifecycle()
    val content = state as? ArrivalsUiState.Content
    if (visible && content != null) {
        val text = createStopDetailsDialogText(
            LocalContext.current,
            content.header.name,
            content.stopUserName,
            content.stopCode,
            content.header.direction,
            content.routeFilterOptions.map { it.displayName }
        )
        AlertDialog(
            onDismissRequest = viewModel::dismissStopDetails,
            title = { Text(text.first) },
            text = { Text(text.second) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissStopDetails) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}
