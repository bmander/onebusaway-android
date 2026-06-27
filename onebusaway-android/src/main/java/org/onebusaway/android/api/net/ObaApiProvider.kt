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
package org.onebusaway.android.api.net

import android.net.Uri
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.onebusaway.android.api.contract.ObaWebService
import retrofit2.Retrofit

/**
 * Builds — and rebuilds — the region-bound [ObaWebService]. The OBA host isn't known until a region
 * is selected and changes when the user switches regions, so rather than a fixed Retrofit on a
 * throwaway base URL (with a per-request host rewrite), this holds a Retrofit built against the
 * *real* base URL from [ObaEndpointResolver] and rebuilds it the first time it's used after that URL
 * changes. The shared [OkHttpClient] (carrying [ApiParamsInterceptor]) is reused across rebuilds —
 * only the lightweight Retrofit proxy is replaced. Region switches are rare, so the rebuild-on-change
 * cost is negligible.
 */
@Singleton
class ObaApiProvider @Inject constructor(
    private val resolver: ObaEndpointResolver,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private var cachedBase: Uri? = null
    private var cachedService: ObaWebService? = null

    /**
     * The [ObaWebService] bound to the current region's base URL, or null when there's no endpoint to
     * contact yet (no current region and no custom API URL). Rebuilds when the base URL has changed.
     */
    @Synchronized
    fun service(): ObaWebService? {
        val base = resolver.baseUrl()
        if (base == null) {
            cachedBase = null
            cachedService = null
            return null
        }
        if (base != cachedBase || cachedService == null) {
            cachedService = build(base)
            cachedBase = base
        }
        return cachedService
    }

    /**
     * Like [service] but throws when no endpoint is configured — the caller's `runCatching` maps it to
     * a [Result.failure], matching the per-endpoint failure policy.
     */
    fun requireService(): ObaWebService =
        service() ?: throw IOException("No OBA API endpoint: no current region and no custom API URL set")

    private fun build(base: Uri): ObaWebService {
        // Retrofit requires the base URL to end in '/' so the endpoints' relative paths resolve onto it.
        val baseUrl = base.toString().let { if (it.endsWith("/")) it else "$it/" }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ObaWebService::class.java)
    }
}
