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

import java.io.IOException
import org.onebusaway.android.io.ObaApi

/**
 * Unwraps an OBA envelope to its payload, throwing when the app-level [ObaEnvelope.code] is not OK
 * or the body is absent. Centralizes the success policy so every repository's `runCatching` maps
 * the same failures to `Result.failure` instead of re-checking the code/null per endpoint.
 */
fun <T> ObaEnvelope<T>.requireData(): T {
    if (code != ObaApi.OBA_OK || data == null) {
        throw IOException("OBA request failed (code $code)")
    }
    return data
}
