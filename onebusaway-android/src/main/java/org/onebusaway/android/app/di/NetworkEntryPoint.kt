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

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.onebusaway.android.io.client.ObaWebService

/**
 * A Hilt [EntryPoint] that lets code which can't be constructor- or field-injected reach the
 * shared [ObaWebService] (e.g. repositories still built by hand from a [Context] at a Compose call
 * site). It needs a [Context] only to resolve the singleton graph; the returned service is the
 * same app-singleton every injected consumer shares.
 *
 * Use it only where injection genuinely isn't available — Hilt-reachable classes should inject
 * [ObaWebService] (or a repository) directly.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NetworkEntryPoint {

    fun obaWebService(): ObaWebService

    companion object {
        /** Resolves the shared [ObaWebService] from any [context] (its application is used). */
        @JvmStatic
        fun get(context: Context): ObaWebService =
            EntryPointAccessors.fromApplication(context, NetworkEntryPoint::class.java)
                .obaWebService()
    }
}
