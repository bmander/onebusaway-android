/*
 * Copyright (C) 2013-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com),
 * Microsoft Corporation.
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
package org.onebusaway.android.io.elements

/**
 * Specifies a region in the OneBusAway multi-region system.
 */
interface ObaRegion {

    /** Specifies a single bound rectangle within this region. */
    interface Bounds {
        val lat: Double
        val lon: Double
        val latSpan: Double
        val lonSpan: Double
    }

    interface Open311Server {
        val juridisctionId: String?
        val apiKey: String?
        val baseUrl: String?
    }

    /** The ID of this region. */
    val id: Long

    /** The name of the region. */
    val name: String

    /** True if this server is active and should be presented in a list of working servers. */
    val active: Boolean

    /** The base OBA URL for this region, or null if it doesn't have one. */
    val obaBaseUrl: String?

    /** The Sidecar base URL for this region, or null if it doesn't have one. */
    val sidecarBaseUrl: String?

    /** The Plausible analytics server URL for this region, or null if not configured. */
    val plausibleAnalyticsServerUrl: String?

    /** The Umami analytics server URL for this region, or null if not configured. */
    val umamiAnalyticsUrl: String?

    /** The Umami analytics website ID for this region, or null if not configured. */
    val umamiAnalyticsId: String?

    /** The base SIRI URL for this region, or null if it doesn't use SIRI. */
    val siriBaseUrl: String?

    /** An array of bounding boxes for the region. */
    val bounds: Array<Bounds>

    val open311Servers: Array<Open311Server>

    /** The primary language for this region. */
    val language: String?

    /** The email of the party responsible for this region's OBA server. */
    val contactEmail: String?

    /** True if this server supports OBA discovery APIs. */
    val supportsObaDiscoveryApis: Boolean

    /** True if this server supports OBA real-time APIs. */
    val supportsObaRealtimeApis: Boolean

    /** True if this server supports SIRI real-time APIs. */
    val supportsSiriRealtimeApis: Boolean

    /** True if this server supports Embedded Social. */
    val supportsEmbeddedSocial: Boolean

    /** The Twitter URL for the region. */
    val twitterUrl: String?

    /** True if this server is experimental, false if it's production. */
    val experimental: Boolean

    /** The StopInfo URL for the region (see #103). */
    val stopInfoUrl: String?

    /** The OpenTripPlanner URL for the region. */
    val otpBaseUrl: String?

    /** The email of the party responsible for this region's OTP server. */
    val otpContactEmail: String?

    /** True if the region includes support for displaying bikeshare information from OTP. */
    val supportsOtpBikeshare: Boolean

    /** The Android App ID for the mobile app used for fare payment in the region. */
    val paymentAndroidAppId: String?

    /**
     * The title of a warning dialog shown the first time the user selects the fare payment option,
     * or null if no warning should be shown.
     */
    val paymentWarningTitle: String?

    /**
     * The body text of a warning dialog shown the first time the user selects the fare payment
     * option, or null if no warning should be shown.
     */
    val paymentWarningBody: String?

    /** True if the region allows the travel-behavior data-collection feature. */
    val isTravelBehaviorDataCollectionEnabled: Boolean

    /** True if the region allows enrolling more participants in the travel-behavior study. */
    val isEnrollParticipantsInStudy: Boolean
}
