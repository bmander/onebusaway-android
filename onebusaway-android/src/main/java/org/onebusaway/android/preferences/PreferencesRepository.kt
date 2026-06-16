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
package org.onebusaway.android.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Injected access to user preferences — the replacement for scattered static
 * `Application.getPrefs()` / `PreferenceUtils` reads (roadmap §1.2, alongside RegionRepository /
 * LocationRepository).
 *
 * Two ways in:
 * - [observeBoolean] is the *reactive* seam — a consumer that today polls a pref on resume collects
 *   it instead.
 * - The synchronous `getX` / `setX` accessors are the one-shot replacement for `PreferenceUtils`.
 *
 * Every accessor comes in two key forms. The `@StringRes` overload lets a caller name a pref by its
 * resource id and stay Context-free / JVM-testable (the implementation resolves it); the `String`
 * overload handles keys that are const or runtime strings rather than resources (e.g. the
 * region-version slots, a map layer's preference key).
 */
interface PreferencesRepository {

    /** Emits the current value of the boolean pref [keyRes] and re-emits on every change. */
    fun observeBoolean(@StringRes keyRes: Int, default: Boolean): Flow<Boolean>

    /** Emits the current value of the String pref [keyRes] and re-emits on every change. */
    fun observeString(@StringRes keyRes: Int, default: String?): Flow<String?>

    /**
     * Emits once immediately, then a [Unit] on every preference change (any key). For a screen that
     * derives its whole state from many keys at once (e.g. Settings), collecting this and re-reading
     * the values synchronously is simpler than combining a flow per key.
     */
    fun observeChanges(): Flow<Unit>

    fun getBoolean(@StringRes keyRes: Int, default: Boolean): Boolean
    fun getBoolean(key: String, default: Boolean): Boolean

    fun getString(@StringRes keyRes: Int, default: String?): String?
    fun getString(key: String, default: String?): String?

    fun getInt(@StringRes keyRes: Int, default: Int): Int
    fun getInt(key: String, default: Int): Int

    fun getLong(@StringRes keyRes: Int, default: Long): Long
    fun getLong(key: String, default: Long): Long

    fun getFloat(@StringRes keyRes: Int, default: Float): Float
    fun getFloat(key: String, default: Float): Float

    fun setBoolean(@StringRes keyRes: Int, value: Boolean)
    fun setBoolean(key: String, value: Boolean)

    fun setString(@StringRes keyRes: Int, value: String?)
    fun setString(key: String, value: String?)

    fun setInt(@StringRes keyRes: Int, value: Int)
    fun setInt(key: String, value: Int)

    fun setLong(@StringRes keyRes: Int, value: Long)
    fun setLong(key: String, value: Long)

    fun setFloat(@StringRes keyRes: Int, value: Float)
    fun setFloat(key: String, value: Float)
}

/** Default implementation over the injected [SharedPreferences]. */
class DefaultPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences,
) : PreferencesRepository {

    override fun observeBoolean(keyRes: Int, default: Boolean): Flow<Boolean> = callbackFlow {
        val key = context.getString(keyRes)
        trySend(prefs.getBoolean(key, default))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) trySend(prefs.getBoolean(key, default))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    override fun observeString(keyRes: Int, default: String?): Flow<String?> = callbackFlow {
        val key = context.getString(keyRes)
        trySend(prefs.getString(key, default))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) trySend(prefs.getString(key, default))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    override fun observeChanges(): Flow<Unit> = callbackFlow {
        trySend(Unit)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    // The @StringRes overloads resolve the key and delegate to the String overloads, which are the
    // single point of contact with SharedPreferences.

    override fun getBoolean(keyRes: Int, default: Boolean) = getBoolean(context.getString(keyRes), default)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)

    override fun getString(keyRes: Int, default: String?) = getString(context.getString(keyRes), default)
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)

    override fun getInt(keyRes: Int, default: Int) = getInt(context.getString(keyRes), default)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)

    override fun getLong(keyRes: Int, default: Long) = getLong(context.getString(keyRes), default)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)

    override fun getFloat(keyRes: Int, default: Float) = getFloat(context.getString(keyRes), default)
    override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)

    override fun setBoolean(keyRes: Int, value: Boolean) = setBoolean(context.getString(keyRes), value)
    override fun setBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun setString(keyRes: Int, value: String?) = setString(context.getString(keyRes), value)
    override fun setString(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    override fun setInt(keyRes: Int, value: Int) = setInt(context.getString(keyRes), value)
    override fun setInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    override fun setLong(keyRes: Int, value: Long) = setLong(context.getString(keyRes), value)
    override fun setLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()

    override fun setFloat(keyRes: Int, value: Float) = setFloat(context.getString(keyRes), value)
    override fun setFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
}
