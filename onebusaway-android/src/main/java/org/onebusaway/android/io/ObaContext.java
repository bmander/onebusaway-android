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

import org.onebusaway.android.R;
import org.onebusaway.android.app.di.PreferencesEntryPoint;
import org.onebusaway.android.region.Region;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.net.MalformedURLException;
import java.net.URL;

public class ObaContext {

    private static final String TAG = "ObaContext";

    private Region mRegion;

    public ObaContext() {
    }

    public void setRegion(Region region) {
        mRegion = region;
    }

    public Region getRegion() {
        return mRegion;
    }

    public void setBaseOtpUrl(Context context, Uri.Builder builder) {
        // Use the custom OTP url if available (read from the prefs seam, not the Application god-object).
        String otpBaseUrl = PreferencesEntryPoint.get(context)
                .getString(R.string.preference_key_otp_api_url, null);
        if (TextUtils.isEmpty(otpBaseUrl)) {
            // Use this context's region OTP base URL (the region the RegionRepository wrote here).
            otpBaseUrl = mRegion.getOtpBaseUrl();
            Log.d(TAG, "Using default region OTP API URL '" + otpBaseUrl + "'.");
        } else {
            Log.d(TAG, "Using custom OTP API URL set by user '" + otpBaseUrl + "'.");
        }
        setUrl(context, builder, otpBaseUrl);
    }

    /**
     * Set a URL to the Uri.Builder. This method was created to avoid repeating the same logic for
     * 'setBasetOtpUrl' and 'setBaseUrl' methods.
     *
     * @param context used to get android resources
     * @param builder the Uri.Builder to set the url
     * @param serverName the url to be used.
     */
    private void setUrl(Context context, Uri.Builder builder, String serverName) {
        Uri baseUrl = null;
        if (!TextUtils.isEmpty(serverName)) {
            // TODO - Right now the below log statement is needed for OBA custom APIs, but not OTP
            // custom APIs (those are already logged in setBaseOtpUrl). This should be cleaned up.
            Log.d(TAG, "Using API URL '" + serverName + "'.");
            try {
                // URI.parse() doesn't tell us if the scheme is missing, so use URL() instead (#126)
                URL url = new URL(serverName);
            } catch (MalformedURLException e) {
                // Assume HTTPS scheme, since without a scheme the Uri won't parse the authority
                serverName = context.getString(R.string.https_prefix) + serverName;
            }

            baseUrl = Uri.parse(serverName);
        } else if (mRegion != null) {
            Log.d(TAG, "Using region base URL '" + mRegion.getObaBaseUrl() + "'.");

            baseUrl = Uri.parse(mRegion.getObaBaseUrl());
        }

        // Copy partial path (if one exists) from the base URL
        Uri.Builder path = new Uri.Builder();
        path.encodedPath(baseUrl.getEncodedPath());

        // Then, tack on the rest of the REST API method path from the Uri.Builder that was passed in
        path.appendEncodedPath(builder.build().getPath());

        // Finally, overwrite builder that was passed in with the full URL
        builder.scheme(baseUrl.getScheme());
        builder.encodedAuthority(baseUrl.getEncodedAuthority());
        builder.encodedPath(path.build().getEncodedPath());
    }
}
