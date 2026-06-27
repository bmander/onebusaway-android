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
package org.onebusaway.android.io.client

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.MalformedURLException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.region.RegionRepository

/**
 * Resolves the OBA REST endpoint and per-request identity for [ObaUrlInterceptor], reading the
 * active region from [RegionRepository] (and a user-entered custom API URL from
 * [PreferencesRepository]). This is the single source of truth for "which host + key + app
 * identifiers does a request get".
 */
@Singleton
class ObaEndpointResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val regionRepository: RegionRepository,
    private val preferences: PreferencesRepository,
) {

    /**
     * The base endpoint (scheme + authority + any partial path) for OBA REST requests: a
     * user-entered custom API URL if present, otherwise the active region's base URL, or null if
     * neither is set. A scheme-less custom URL is assumed to be https (#126).
     */
    fun baseUrl(): Uri? {
        val custom = preferences.getString(R.string.preference_key_oba_api_url, null)
        val raw = custom?.takeIf { it.isNotEmpty() } ?: regionRepository.region.value?.obaBaseUrl
        ?: return null
        val withScheme = try {
            URL(raw)
            raw
        } catch (e: MalformedURLException) {
            context.getString(R.string.https_prefix) + raw
        }
        return Uri.parse(withScheme)
    }

    /** The OBA API key appended to every request. */
    val apiKey: String get() = ObaApi.API_KEY

    /** The app version code (`app_ver`), or 0 if unavailable. */
    @Suppress("DEPRECATION")
    val appVersion: Int
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }

    /** The persisted per-install app UID (`app_uid`), generated once by [Application], or null. */
    val appUid: String? get() = preferences.getString(Application.APP_UID, null)
}
