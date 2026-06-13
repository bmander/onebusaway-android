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
package org.onebusaway.android.map.googlemapsv2.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.StrokeStyle
import com.google.android.gms.maps.model.StyleSpan
import com.google.android.gms.maps.model.TextureStyle
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.Polyline
import org.onebusaway.android.R
import org.onebusaway.android.map.render.MapRenderState

/**
 * Declarative render of [MapRenderState] inside a `GoogleMap {}` content lambda. This is the
 * counterpart of the imperative overlay classes: the flavor host pushes overlay content into the
 * shared [MapRenderState] via its `ObaMapView` methods, and this composable draws whatever the
 * current snapshot holds. It grows one overlay per phase — MM1 renders route / itinerary polylines.
 */
@Composable
@GoogleMapComposable
fun ObaMapContent(renderState: MapRenderState) {
    val snapshot by renderState.snapshot.collectAsState()

    // The chevron texture is color-independent, so decode it once; the per-polyline color is applied
    // by the StrokeStyle below. Safe to build here: the map SDK is initialized before this content
    // composes. (This is the same texture the legacy GoogleMapHost.setRouteOverlay built.)
    val arrow = remember {
        TextureStyle.newBuilder(
            BitmapDescriptorFactory.fromResource(R.drawable.ic_navigation_expand_more)
        ).build()
    }

    snapshot.routePolylines.forEach { polyline ->
        Polyline(
            points = polyline.points.map { LatLng(it.latitude, it.longitude) },
            spans = listOf(
                StyleSpan(StrokeStyle.colorBuilder(polyline.color).stamp(arrow).build())
            ),
        )
    }
}
