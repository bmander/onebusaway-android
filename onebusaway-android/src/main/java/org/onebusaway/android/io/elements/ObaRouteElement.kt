/*
 * Copyright (C) 2010-2014 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com)
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

import org.onebusaway.android.util.parseObaHexColor

/**
 * Object defining a Route element. Equality is by [id] only (preserved from the original).
 */
class ObaRouteElement(
    override val id: String = "",
    override val shortName: String = "",
    override val longName: String = "",
    override val description: String = "",
    override val type: Int = 0,
    override val url: String = "",
    private val colorHex: String = "",
    private val textColorHex: String = "",
    override val agencyId: String = "",
) : ObaRoute {

    /** The Android color int for the route line, or null if not included in the API response. */
    override val color: Int? get() = parseObaHexColor(colorHex)

    /** The Android color int for the route text, or null if not included in the API response. */
    override val textColor: Int? get() = parseObaHexColor(textColorHex)

    override fun hashCode(): Int = 31 + id.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ObaRouteElement) return false
        return id == other.id
    }

    override fun toString(): String = "ObaRouteElement [id=$id]"

    companion object {
        @JvmField
        val EMPTY_OBJECT = ObaRouteElement()

        @JvmField
        val EMPTY_ARRAY = arrayOf<ObaRouteElement>()
    }
}
