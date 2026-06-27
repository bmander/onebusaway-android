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

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.onebusaway.android.api.ObaApi

/**
 * Rewrites each modernized request's placeholder URL to the live OBA endpoint and appends the
 * shared query parameters.
 *
 * The Retrofit client is built with a throwaway base URL; only the request's path and any
 * endpoint-specific query parameters are meaningful. This interceptor asks [ObaEndpointResolver]
 * (backed by `RegionRepository`) for the resolved host / key / app identity, grafts the live
 * scheme+host+port (and any base path prefix) onto the request via [okhttp3.HttpUrl], and layers the
 * version + app params on top — one source of truth for endpoint resolution.
 */
class ObaUrlInterceptor(private val resolver: ObaEndpointResolver) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // The live endpoint: custom API URL, else the current region's base URL. With neither set
        // there's nothing to contact — fail loudly rather than silently hitting a default server. The
        // data sources wrap their calls in runCatching, so this surfaces as a Result.failure.
        val base = resolver.baseUrl()
            ?: throw IOException("No OBA API endpoint: no current region and no custom API URL set")
        val baseUrl = base.toString().toHttpUrlOrNull()
            ?: throw IOException("Malformed OBA API base URL: $base")

        // Start from the live base (scheme/host/port + any path prefix) and append the REST path
        // Retrofit produced; HttpUrl collapses the join so a trailing slash on the base can't double up.
        val builder = baseUrl.newBuilder()
            .addEncodedPathSegments(request.url.encodedPath.removePrefix("/"))

        // App / version / key params, in the same order as the legacy BuilderBase.buildUri().
        if (resolver.appVersion != 0) {
            builder.addQueryParameter("app_ver", resolver.appVersion.toString())
        }
        resolver.appUid?.let { builder.addQueryParameter("app_uid", it) }
        builder.addQueryParameter("version", ObaApi.VERSION2)
        builder.addQueryParameter("key", resolver.apiKey)

        // Carry over any endpoint-specific query parameters (e.g. minutesAfter) Retrofit attached.
        val originalUrl = request.url
        originalUrl.queryParameterNames.forEach { name ->
            originalUrl.queryParameterValues(name).forEach { value ->
                value?.let { builder.addQueryParameter(name, it) }
            }
        }

        val resolved = request.newBuilder().url(builder.build()).build()
        return chain.proceed(resolved)
    }
}
