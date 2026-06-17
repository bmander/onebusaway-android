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
package org.onebusaway.android.ui.home.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onebusaway.android.R
import org.onebusaway.android.map.RouteHeader

/**
 * The route-mode header overlay (the Compose replacement for the legacy `route_info_head.xml` /
 * `RouteMapController.RoutePopup`). Renders the route short/long name + agency (or a spinner while the
 * route loads) and a cancel button that exits route mode. It reports its measured height via [onHeight]
 * so the host can set the map's top padding (keeping vehicle markers visible under it).
 */
@Composable
fun RouteHeaderOverlay(
    header: RouteHeader,
    onCancel: () -> Unit,
    onHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.onSizeChanged { onHeight(it.height) },
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) {
        if (header.loading) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                CircularProgressIndicator(Modifier.size(48.dp))
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = header.shortName,
                    fontSize = 45.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                Column(Modifier.weight(1f)) {
                    if (header.longName.isNotEmpty()) {
                        Text(
                            text = header.longName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (header.agency.isNotEmpty()) {
                        Text(text = header.agency)
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        painter = painterResource(R.drawable.ic_navigation_close),
                        contentDescription = stringResource(android.R.string.cancel),
                    )
                }
            }
        }
    }
}
