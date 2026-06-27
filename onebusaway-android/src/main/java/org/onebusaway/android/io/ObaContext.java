/*
 * Copyright (C) 2012 Paul Watts (paulcwatts@gmail.com)
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
package org.onebusaway.android.io;

import org.onebusaway.android.region.Region;

/**
 * Holds the process-wide current {@link Region}. {@code RegionRepository} is the canonical
 * owner/writer of this value (and exposes it reactively via its {@code region} flow); this static
 * slot remains only because a few non-injectable Java readers (e.g. {@code
 * Application.getCurrentRegion}) and {@code RegionRepository}'s own seed read through it.
 */
public class ObaContext {

    private Region mRegion;

    public ObaContext() {
    }

    public void setRegion(Region region) {
        mRegion = region;
    }

    public Region getRegion() {
        return mRegion;
    }
}
