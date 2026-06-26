/*
 * Copyright (C) 2010 Paul Watts (paulcwatts@gmail.com)
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
package org.onebusaway.android.io.elements

import android.location.Location
import org.onebusaway.android.util.LocationUtils

class ObaShapeElement(
    private val pointsStr: String = "",
    override val length: Int = 0,
    private val levelsStr: String = "",
) : ObaShape {

    override val rawLevels: String get() = levelsStr

    override val levels: List<Int> get() = decodeLevels(levelsStr, length)

    override val points: List<Location> get() = decodeLine(pointsStr, length)

    override val rawPoints: String get() = pointsStr

    companion object {
        @JvmField
        val EMPTY_OBJECT = ObaShapeElement()

        @JvmField
        val EMPTY_ARRAY = arrayOf<ObaShapeElement>()

        /**
         * Decodes an encoded polyline into a list of points.
         * For the exact algorithm: http://code.google.com/apis/maps/documentation/polylinealgorithm.html
         *
         * @param encoded   the encoded string
         * @param numPoints a hint used to allocate memory; the result always reflects the points
         *                  actually contained in [encoded]
         */
        @JvmStatic
        fun decodeLine(encoded: String, numPoints: Int): List<Location> {
            require(numPoints >= 0) { "numPoints must be >= 0" }
            val array = ArrayList<Location>(numPoints)

            val len = encoded.length
            var i = 0
            var lat = 0
            var lon = 0

            while (i < len) {
                var shift = 0
                var result = 0
                var b: Int
                do {
                    b = encoded[i].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                    i++
                } while (b >= 0x20)
                val dlat = if (result and 1 == 1) (result shr 1).inv() else result shr 1
                lat += dlat

                shift = 0
                result = 0
                do {
                    b = encoded[i].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                    i++
                } while (b >= 0x20)
                val dlon = if (result and 1 == 1) (result shr 1).inv() else result shr 1
                lon += dlon

                // The polyline encodes in degrees * 1E5, we need decimal degrees
                array.add(LocationUtils.makeLocation(lat / 1E5, lon / 1E5))
            }

            return array
        }

        /**
         * Decodes encoded levels according to:
         * http://code.google.com/apis/maps/documentation/polylinealgorithm.html
         */
        @JvmStatic
        fun decodeLevels(encoded: String, numPoints: Int): List<Int> {
            require(numPoints >= 0) { "numPoints must be >= 0" }
            val array = ArrayList<Int>(numPoints)

            val len = encoded.length
            var i = 0
            while (i < len) {
                var shift = 0
                var result = 0
                var b: Int
                do {
                    b = encoded[i].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                    i++
                } while (b >= 0x20)
                array.add(result)
            }

            return array
        }
    }
}
