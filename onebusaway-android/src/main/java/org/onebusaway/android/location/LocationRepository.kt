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

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.onebusaway.android.util.LocationUtils

/**
 * The observable last-known device location, and (Campaign A, B1) the owner of location *production* —
 * the reactive replacement for the static `Application.getLastKnownLocation()`, mirroring
 * [org.onebusaway.android.region.RegionRepository]. Features read the current value
 * ([lastKnownLocation]) or observe changes by collecting [location].
 *
 * This repository is the single source of truth: the value lives in its [StateFlow], not on
 * `Application`. The Android location listener funnels updates in through [update]; one-shot readers
 * call [lastKnownLocation], which lazily polls the providers on first access (the old
 * `getLastKnownLocation` side effect). One process-singleton instance is shared via Hilt; tests
 * substitute a fake.
 */
interface LocationRepository {

    /** The last-known location, or null until one is established. Observe for changes. */
    val location: StateFlow<Location?>

    /**
     * The last-known location. On first access (when none has been established yet) this lazily polls
     * the location providers and publishes the result, exactly like the former
     * `Application.getLastKnownLocation` — so one-shot callers see a value even before the listener fires.
     */
    fun lastKnownLocation(): Location?

    /**
     * Offers a raw location from the device listener. Publishes it (as a fresh copy) iff it is "better"
     * than the current value per [LocationUtils.compareLocations]; returns whether it was accepted (the
     * caller uses this to keep the location-derived magnetic declination in sync).
     */
    fun update(raw: Location?): Boolean
}

/**
 * Default implementation: owns the canonical [MutableStateFlow] plus the provider-polling producer
 * (the `getLocation2` / `getLocationApiV1` logic moved off `Application` in B1). A Hilt [Singleton]
 * constructed lazily on first injection (after `Application.onCreate`); it starts null and fills from
 * the listener ([update]) or the lazy poll ([lastKnownLocation]).
 */
@Singleton
class DefaultLocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationRepository {

    private val _location = MutableStateFlow<Location?>(null)

    override val location: StateFlow<Location?> = _location.asStateFlow()

    @Synchronized
    override fun lastKnownLocation(): Location? {
        if (_location.value == null) {
            try {
                _location.value = poll()
            } catch (e: SecurityException) {
                Log.e(TAG, "User may have denied location permission - $e")
            }
        }
        return _location.value
    }

    @Synchronized
    override fun update(raw: Location?): Boolean {
        // compareLocations already rejects a null candidate; the explicit check also lets `raw`
        // smart-cast to non-null for the copy below.
        if (raw == null || !LocationUtils.compareLocations(raw, _location.value)) {
            return false
        }
        // A fresh copy: the StateFlow dedupes by reference (Location has no value equality).
        _location.value = Location(raw)
        return true
    }

    /**
     * Considers both Google Play Services (if available) and the Android Location API, returning the
     * more recent. Moved verbatim from `Application.getLocation2` / `getLocationApiV1`.
     */
    @Throws(SecurityException::class)
    private fun poll(): Location? {
        var playServices: Location? = null
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            == ConnectionResult.SUCCESS
        ) {
            val task = LocationServices.getFusedLocationProviderClient(context).lastLocation
            // isSuccessful (not isComplete) — a task that completed with a failure would throw
            // RuntimeExecutionException from getResult().
            if (task.isSuccessful) {
                playServices = task.result
                Log.d(TAG, "Got location from Google Play Services, testing against API v1...")
            }
        }
        val apiV1 = pollApiV1()
        return if (LocationUtils.compareLocationsByTime(playServices, apiV1)) {
            Log.d(TAG, "Using location from Google Play Services")
            playServices
        } else {
            Log.d(TAG, "Using location from Location API v1")
            apiV1
        }
    }

    private fun pollApiV1(): Location? {
        val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var last: Location? = null
        for (provider in mgr.getProviders(true)) {
            val loc = try {
                mgr.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                Log.w(TAG, "User may have denied location permission - $e")
                null
            }
            // Keep this provider's location if we have none yet, or it is newer than what we have.
            if (LocationUtils.compareLocationsByTime(loc, last)) {
                last = loc
            }
        }
        return last
    }

    private companion object {
        const val TAG = "LocationRepository"
    }
}
