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
package org.onebusaway.android.map;

/**
 * The host-environment hooks an {@link ObaMapHost} needs from its owner (the hosting Activity, or
 * the thin fragment wrapper that keeps the non-Home map screens working). Chiefly the
 * location-permission request: since the host is not a {@code Fragment}/{@code Activity} it can't
 * call {@code requestPermissions(...)} itself, so it asks the owner — which drives the real
 * {@code ActivityResultLauncher} and delivers the outcome back via
 * {@link ObaMapHost#onLocationPermissionResult(int)}.
 */
public interface MapHostDeps {

    /** Ask the owner to request the location permission. */
    void requestLocationPermission();
}
