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
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room access for routes (the legacy `routes` table). */
@Dao
interface RouteDao {

    @Query("SELECT * FROM routes WHERE _id = :routeId LIMIT 1")
    suspend fun getRoute(routeId: String): RouteRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: RouteRecord)

    @Query("UPDATE routes SET favorite = :favorite WHERE _id = :routeId")
    suspend fun setFavorite(routeId: String, favorite: Int)
}
