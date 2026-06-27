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
package org.onebusaway.android.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.onebusaway.android.BuildConfig
import org.onebusaway.android.io.client.sidecar.BikeWebService
import org.onebusaway.android.io.client.ObaEndpointResolver
import org.onebusaway.android.io.client.ObaUrlInterceptor
import org.onebusaway.android.io.client.ObaWebService
import org.onebusaway.android.io.client.RegionsWebService
import org.onebusaway.android.io.client.sidecar.ReminderWebService
import org.onebusaway.android.io.client.sidecar.SurveyWebService
import org.onebusaway.android.io.client.sidecar.WeatherWebService
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit

/**
 * Wires the modernized Retrofit-based OBA REST client (io/client). The base URL is a throwaway —
 * [ObaUrlInterceptor] rewrites every request to the live region endpoint — so a single shared
 * client serves all regions and reacts to region changes without being rebuilt.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(resolver: ObaEndpointResolver): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ObaUrlInterceptor(resolver))
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
                    )
                }
            }
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        // Placeholder; ObaUrlInterceptor swaps in the resolved region host/scheme per request.
        .baseUrl("https://localhost/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideObaWebService(retrofit: Retrofit): ObaWebService =
        retrofit.create(ObaWebService::class.java)

    /**
     * The regions-directory client. Built with a plain client (NO [ObaUrlInterceptor]) since regions
     * is fetched from a fixed directory host via `@Url`, not the selected region's OBA host.
     */
    @Provides
    @Singleton
    fun provideRegionsWebService(json: Json): RegionsWebService =
        plainRetrofit(json).create(RegionsWebService::class.java)

    /**
     * The surveys client. Like regions, it targets a non-OBA host (the region's sidecar) via `@Url`,
     * so it uses a plain client without [ObaUrlInterceptor].
     */
    @Provides
    @Singleton
    fun provideSurveyWebService(json: Json): SurveyWebService =
        plainRetrofit(json).create(SurveyWebService::class.java)

    /**
     * The bike-rental client. Targets an OpenTripPlanner host (the region's `otpBaseUrl`) via `@Url`,
     * so like regions/surveys it uses a plain client without [ObaUrlInterceptor].
     */
    @Provides
    @Singleton
    fun provideBikeWebService(json: Json): BikeWebService =
        plainRetrofit(json).create(BikeWebService::class.java)

    /**
     * The weather client. Targets the region's sidecar host via `@Url` (like surveys), so it uses a
     * plain client without [ObaUrlInterceptor].
     */
    @Provides
    @Singleton
    fun provideWeatherWebService(json: Json): WeatherWebService =
        plainRetrofit(json).create(WeatherWebService::class.java)

    /**
     * The arrivals-reminders client. Targets the region's sidecar host via `@Url`, so like surveys
     * it uses a plain client without [ObaUrlInterceptor].
     */
    @Provides
    @Singleton
    fun provideReminderWebService(json: Json): ReminderWebService =
        plainRetrofit(json).create(ReminderWebService::class.java)

    /**
     * A Retrofit built on a plain OkHttp client (debug logging only, no [ObaUrlInterceptor]) for
     * services that pass an absolute `@Url` per call rather than relying on the region host rewrite.
     */
    private fun plainRetrofit(json: Json): Retrofit {
        val client = OkHttpClient.Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
                    )
                }
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://localhost/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
