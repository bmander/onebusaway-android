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

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.onebusaway.android.io.ObaApi

/**
 * Rewrites each modernized request's placeholder URL to the live OBA endpoint and appends the
 * shared query parameters.
 *
 * The Retrofit client is built with a throwaway base URL; only the request's path and any
 * endpoint-specific query parameters survive. This interceptor asks [ObaEndpointResolver] (backed by
 * `RegionRepository`) for the resolved host / key / app identity, then layers the REST path and the
 * version + app params on top — one source of truth for endpoint resolution.
 */
class ObaUrlInterceptor(private val resolver: ObaEndpointResolver) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // The REST path Retrofit produced, minus the leading slash, to merge onto the base URL below.
        val restPath = request.url.encodedPath.removePrefix("/")
        val builder = Uri.Builder()

        val base = resolver.baseUrl()
        if (base != null) {
            builder.scheme(base.scheme).encodedAuthority(base.encodedAuthority)
            // Keep any partial path on the base URL, then append the REST API method path.
            val path = Uri.Builder().encodedPath(base.encodedPath).appendEncodedPath(restPath)
            builder.encodedPath(path.build().encodedPath)
        } else {
            // Defensive fallback for the no-region/no-custom-URL case (preserves legacy behavior).
            builder.scheme("http").authority("api.pugetsound.onebusaway.org").encodedPath(restPath)
        }

        // App / version / key params, in the same order as the legacy BuilderBase.buildUri().
        if (resolver.appVersion != 0) {
            builder.appendQueryParameter("app_ver", resolver.appVersion.toString())
        }
        resolver.appUid?.let { builder.appendQueryParameter("app_uid", it) }
        builder.appendQueryParameter("version", ObaApi.VERSION2)
        builder.appendQueryParameter("key", resolver.apiKey)

        // Carry over any endpoint-specific query parameters (e.g. minutesAfter) Retrofit attached.
        val originalUrl = request.url
        originalUrl.queryParameterNames.forEach { name ->
            originalUrl.queryParameterValues(name).forEach { value ->
                value?.let { builder.appendQueryParameter(name, it) }
            }
        }

        val resolved = request.newBuilder()
            .url(builder.build().toString().toHttpUrl())
            .build()
        return chain.proceed(resolved)
    }
}
