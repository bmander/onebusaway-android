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
package org.onebusaway.android.database.oba

import androidx.room.Dao
import androidx.room.Query

/** Room access for stop user-state (the legacy `stops` table). */
@Dao
interface StopDao {

    /** Sets the favorite flag. A no-op when the row doesn't exist (matches the legacy UPDATE). */
    @Query("UPDATE stops SET favorite = :favorite WHERE _id = :stopId")
    suspend fun setFavorite(stopId: String, favorite: Int)
}
