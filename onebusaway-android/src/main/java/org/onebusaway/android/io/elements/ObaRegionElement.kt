/*
 * Copyright (C) 2012-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com),
 * Microsoft Corporation
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

import java.util.Arrays

/**
 * Concrete [ObaRegion]. Equality is by [id] only (preserved from the original). The 27-arg
 * constructor is called positionally by `RegionMapper` and the test `MockRegion`.
 */
class ObaRegionElement(
    override val id: Long = 0,
    override val name: String = "",
    override val active: Boolean = false,
    override val obaBaseUrl: String? = null,
    override val siriBaseUrl: String? = null,
    bounds: Array<ObaRegion.Bounds>? = emptyArray(),
    open311Servers: Array<ObaRegion.Open311Server>? = emptyArray(),
    override val language: String? = "",
    override val contactEmail: String? = "",
    override val supportsObaDiscoveryApis: Boolean = false,
    override val supportsObaRealtimeApis: Boolean = false,
    override val supportsSiriRealtimeApis: Boolean = false,
    override val twitterUrl: String? = "",
    override val experimental: Boolean = true,
    override val stopInfoUrl: String? = "",
    override val otpBaseUrl: String? = "",
    override val otpContactEmail: String? = "",
    override val supportsOtpBikeshare: Boolean = false,
    override val supportsEmbeddedSocial: Boolean = false,
    override val paymentAndroidAppId: String? = null,
    override val paymentWarningTitle: String? = null,
    override val paymentWarningBody: String? = null,
    override val isTravelBehaviorDataCollectionEnabled: Boolean = false,
    override val isEnrollParticipantsInStudy: Boolean = false,
    override val sidecarBaseUrl: String? = "",
    override val plausibleAnalyticsServerUrl: String? = "",
    private val umamiAnalytics: UmamiAnalyticsConfig? = null,
) : ObaRegion {

    // Null-tolerant (the legacy Java ctor accepted null arrays from the DB/parse paths).
    override val bounds: Array<ObaRegion.Bounds> = bounds ?: emptyArray()

    override val open311Servers: Array<ObaRegion.Open311Server> = open311Servers ?: emptyArray()

    override val umamiAnalyticsUrl: String? get() = umamiAnalytics?.url

    override val umamiAnalyticsId: String? get() = umamiAnalytics?.id

    class Bounds(
        override val lat: Double = 0.0,
        override val lon: Double = 0.0,
        override val latSpan: Double = 0.0,
        override val lonSpan: Double = 0.0,
    ) : ObaRegion.Bounds {
        override fun toString(): String = "[lat=$lat,lon=$lon,latSpan=$latSpan,lonSpan=$lonSpan]"

        companion object {
            @JvmField
            val EMPTY_ARRAY = arrayOf<Bounds>()
        }
    }

    class UmamiAnalyticsConfig(val url: String? = null, val id: String? = null)

    class Open311Server(
        jurisdictionId: String? = "",
        override val apiKey: String? = "",
        override val baseUrl: String? = "",
    ) : ObaRegion.Open311Server {
        override val juridisctionId: String? = jurisdictionId

        override fun toString(): String =
            "[jurisdictionId=$juridisctionId,apiKey=$apiKey,baseUrl=$baseUrl]"

        companion object {
            @JvmField
            val EMPTY_ARRAY = arrayOf<Open311Server>()
        }
    }

    override fun hashCode(): Int = 31 + if (id == 0L) 0 else id.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ObaRegionElement) return false
        return id == other.id
    }

    override fun toString(): String =
        "ObaRegionElement{" +
            "id=$id" +
            ", regionName='$name'" +
            ", active=$active" +
            ", obaBaseUrl='$obaBaseUrl'" +
            ", siriBaseUrl='$siriBaseUrl'" +
            ", bounds=${Arrays.toString(bounds)}" +
            ", open311Servers=${Arrays.toString(open311Servers)}" +
            ", language='$language'" +
            ", contactEmail='$contactEmail'" +
            ", supportsObaDiscoveryApis=$supportsObaDiscoveryApis" +
            ", supportsObaRealtimeApis=$supportsObaRealtimeApis" +
            ", supportsSiriRealtimeApis=$supportsSiriRealtimeApis" +
            ", twitterUrl='$twitterUrl'" +
            ", experimental=$experimental" +
            ", stopInfoUrl='$stopInfoUrl'" +
            ", otpBaseUrl='$otpBaseUrl'" +
            ", otpContactEmail='$otpContactEmail'" +
            ", supportsOtpBikeshare='$supportsOtpBikeshare'" +
            ", supportsEmbeddedSocial=$supportsEmbeddedSocial" +
            ", paymentAndroidAppId=$paymentAndroidAppId" +
            ", paymentWarningTitle=$paymentWarningTitle" +
            ", paymentWarningBody=$paymentWarningBody" +
            ", sidecarBaseUrl=$sidecarBaseUrl" +
            ", plausibleAnalyticsServerUrl=$plausibleAnalyticsServerUrl" +
            "}"

    companion object {
        @JvmField
        val EMPTY_ARRAY = arrayOf<ObaRegionElement>()
    }
}
