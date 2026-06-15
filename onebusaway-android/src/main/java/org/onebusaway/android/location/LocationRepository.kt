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
package org.onebusaway.android.location

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The observable last-known device location — the reactive companion to the static
 * `Application.getLastKnownLocation()`, mirroring [org.onebusaway.android.region.RegionRepository].
 * Features read the current value ([location].value) or observe changes by collecting the flow.
 *
 * `Application` is the single owner/writer: it [publish]es here from both `setLastKnownLocation`
 * (location-listener updates) and `getLastKnownLocation`'s lazy provider poll, so the flow always
 * reflects the same canonical location the static accessor returns — including the first-call poll.
 * A process-singleton instance is held on `Application`; tests substitute a fake `MutableStateFlow`.
 */
interface LocationRepository {

    /** The last-known location, or null until one is established. */
    val location: StateFlow<Location?>
}

/** Default implementation: a thin holder around a [MutableStateFlow], written only by `Application`. */
class DefaultLocationRepository(initial: Location?) : LocationRepository {

    private val _location = MutableStateFlow(initial)

    override val location: StateFlow<Location?> = _location.asStateFlow()

    /**
     * Publishes a new location. **Only `Application` should call this** — it is the one place the
     * last-known location is written. Callers pass a fresh [Location] copy so the [MutableStateFlow]
     * (which dedupes by reference, as `Location` has no value equality) emits on each real update.
     */
    fun publish(location: Location?) {
        _location.value = location
    }
}
