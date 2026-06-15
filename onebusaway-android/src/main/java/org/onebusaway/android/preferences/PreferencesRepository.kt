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
 * Injected access to user preferences — the reactive replacement for scattered static
 * `Application.getPrefs()` / `PreferenceUtils` reads (roadmap §1.2, alongside RegionRepository /
 * LocationRepository). Keys are passed as string resource ids so callers (view models) stay
 * Context-free and JVM-testable; the implementation resolves them.
 *
 * This is the reactive seam: a consumer that today polls a pref on resume collects [observeBoolean]
 * instead. Synchronous accessors are added here as one-shot consumers migrate off `PreferenceUtils`.
 */
interface PreferencesRepository {

    /** Emits the current value of the boolean pref [keyRes] and re-emits on every change. */
    fun observeBoolean(@StringRes keyRes: Int, default: Boolean): Flow<Boolean>
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
}
