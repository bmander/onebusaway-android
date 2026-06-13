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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import org.onebusaway.android.R

/**
 * The three bike marker icons (the small dot, the big station, the big floating bike), built once and
 * reused. Lifted verbatim from the legacy BikeStationOverlay; `small` is drawn from a vector via a
 * Canvas, the big icons are raster resources.
 */
class BikeIcons(context: Context) {

    val small: BitmapDescriptor = BitmapDescriptorFactory.fromBitmap(createSmallBitmap(context))

    val bigStation: BitmapDescriptor =
        BitmapDescriptorFactory.fromResource(R.drawable.bike_station_marker_big)

    val bigFloating: BitmapDescriptor =
        BitmapDescriptorFactory.fromResource(R.drawable.bike_floating_marker_big)

    private fun createSmallBitmap(context: Context): Bitmap {
        val px = context.resources.getDimensionPixelSize(R.dimen.bikeshare_small_marker_size)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shape = ContextCompat.getDrawable(context, R.drawable.bike_marker_small)!!
        shape.setBounds(0, 0, bitmap.width, bitmap.height)
        shape.draw(canvas)
        return bitmap
    }
}
