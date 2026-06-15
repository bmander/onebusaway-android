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
package org.onebusaway.android.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.onebusaway.android.preferences.PreferencesRepository

/**
 * In-memory [PreferencesRepository] for JVM unit tests. The synchronous accessors read/write a
 * backing map keyed by the resource id or string so they round-trip; [observeBoolean] reports
 * [observeValue] regardless of key (the single reactive value the current consumers care about).
 *
 * Pre-seed synchronous values with the `setX` methods before constructing the subject under test.
 */
class FakePreferencesRepository(private val observeValue: Boolean = true) : PreferencesRepository {

    private val values = mutableMapOf<Any, Any?>()

    override fun observeBoolean(keyRes: Int, default: Boolean): Flow<Boolean> = flowOf(observeValue)

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: Any, default: T): T = (values[key] as T?) ?: default

    override fun getBoolean(keyRes: Int, default: Boolean) = read(keyRes, default)
    override fun getBoolean(key: String, default: Boolean) = read(key, default)
    override fun getString(keyRes: Int, default: String?) = read(keyRes, default)
    override fun getString(key: String, default: String?) = read(key, default)
    override fun getInt(keyRes: Int, default: Int) = read(keyRes, default)
    override fun getInt(key: String, default: Int) = read(key, default)
    override fun getLong(keyRes: Int, default: Long) = read(keyRes, default)
    override fun getLong(key: String, default: Long) = read(key, default)
    override fun getFloat(keyRes: Int, default: Float) = read(keyRes, default)
    override fun getFloat(key: String, default: Float) = read(key, default)

    override fun setBoolean(keyRes: Int, value: Boolean) { values[keyRes] = value }
    override fun setBoolean(key: String, value: Boolean) { values[key] = value }
    override fun setString(keyRes: Int, value: String?) { values[keyRes] = value }
    override fun setString(key: String, value: String?) { values[key] = value }
    override fun setInt(keyRes: Int, value: Int) { values[keyRes] = value }
    override fun setInt(key: String, value: Int) { values[key] = value }
    override fun setLong(keyRes: Int, value: Long) { values[keyRes] = value }
    override fun setLong(key: String, value: Long) { values[key] = value }
    override fun setFloat(keyRes: Int, value: Float) { values[keyRes] = value }
    override fun setFloat(key: String, value: Float) { values[key] = value }
}
