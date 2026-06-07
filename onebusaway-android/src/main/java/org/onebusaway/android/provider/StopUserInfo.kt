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
package org.onebusaway.android.provider

import android.content.Context

/** A user's customization of a stop: whether it's a favorite, and any custom name. */
data class StopUserInfo(val isFavorite: Boolean, val userName: String?)

/**
 * Loads the user's favorite and custom-named stops from the ContentProvider, keyed by stop id.
 * The same query the legacy UIUtils.StopUserInfoMap ran; shared by the Compose stop repositories
 * so each row can show the star and the user's name.
 */
fun loadStopUserInfo(context: Context): Map<String, StopUserInfo> {
    val map = mutableMapOf<String, StopUserInfo>()
    context.contentResolver.query(
        ObaContract.Stops.CONTENT_URI,
        arrayOf(ObaContract.Stops._ID, ObaContract.Stops.FAVORITE, ObaContract.Stops.USER_NAME),
        "(" + ObaContract.Stops.USER_NAME + " IS NOT NULL) OR (" + ObaContract.Stops.FAVORITE + "=1)",
        null,
        null
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            map[cursor.getString(0)] = StopUserInfo(
                isFavorite = cursor.getInt(1) == 1,
                userName = cursor.getString(2)
            )
        }
    }
    return map
}
