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
package org.onebusaway.android.region

import javax.inject.Inject
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.elements.ObaRegion

/**
 * Performs the side-effecting region "activation" transaction — the OBA API context region write, the
 * persisted region-id preference, the custom-URL / OTP clears, the Plausible rebuild, and the Open311
 * re-init — that `Application.setCurrentRegion` historically did inline.
 *
 * Extracted as an injectable seam (Campaign A, A0b) so region resolution can move into
 * [RegionRepository] (A1) and drive the transaction without depending on `Application.setCurrentRegion`,
 * while the repository owns publishing the resulting [RegionState]. The seam (not yet its caller) is the
 * deliverable here; the transaction body collapses into the repository at the end of the campaign (A7),
 * at which point the `Application` delegation below disappears.
 */
interface RegionActivator {

    /** Applies [region] (or null for a custom API URL) as the active region; mirrors `regionChanged`. */
    fun activate(region: ObaRegion?, regionChanged: Boolean)

    /** The currently-active region (the OBA context's), for seeding the repository's state at startup. */
    fun currentRegion(): ObaRegion?
}

/**
 * Default implementation: delegates to the (still `Application`-owned) transaction. The static reach is
 * the deliberate migration bridge — it is removed in A7 when the transaction moves into the repository.
 */
class DefaultRegionActivator @Inject constructor() : RegionActivator {

    override fun activate(region: ObaRegion?, regionChanged: Boolean) {
        Application.get().applyRegionTransaction(region, regionChanged)
    }

    override fun currentRegion(): ObaRegion? = Application.get().currentRegion
}
