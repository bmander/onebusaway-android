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
 * endpoint-specific query parameters survive. This interceptor reuses [org.onebusaway.android.io.ObaContext]
 * (the same instance `RegionRepository` keeps updated) to resolve the region / custom API URL /
 * api key and append the version + app identifiers — one source of truth for endpoint resolution.
 */
class ObaUrlInterceptor(private val context: Context) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val obaContext = ObaApi.getDefaultContext()

        // Seed a Uri.Builder with the REST path Retrofit produced, dropping the leading slash to
        // match BuilderBase (which sets the path without one). Then layer on the resolved base URL
        // and the app / version / key params in the same order as BuilderBase.buildUri().
        val builder = Uri.Builder().encodedPath(request.url.encodedPath.removePrefix("/"))
        obaContext.setBaseUrl(context, builder)
        obaContext.setAppInfo(builder)
        builder.appendQueryParameter("version", ObaApi.VERSION2)
        builder.appendQueryParameter("key", obaContext.apiKey)

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
