/*
 * Copyright (C) 2010-2017 Brian Ferris (bdferris@onebusaway.org),
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
package org.onebusaway.android.ui;

import android.util.Patterns;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Shared, stateless helpers used by the settings preference fragments
 * ({@link SettingsFragment} / {@link AdvancedSettingsFragment}) and by the {@code add-region} deep
 * link translator in {@link HomeActivity}. These were package-private statics on the former
 * {@code SettingsActivity}; they moved here when the settings screens became a HomeActivity NavHost
 * destination (Campaign C) so they survive independently of any single host.
 */
final class SettingsSupport {

    private SettingsSupport() {
    }

    static boolean validateUrl(String apiUrl) {
        if (!apiUrl.startsWith("http")) {
            apiUrl = "https://" + apiUrl;
        }
        try {
            URL url = new URL(apiUrl);
            if (url.getHost().equals("localhost")) {
                return true;
            }
            return Patterns.WEB_URL.matcher(apiUrl).matches();
        } catch (MalformedURLException e) {
            return false;
        }
    }

    static void setIconSpaceReservedRecursive(PreferenceGroup group, boolean reserved) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            pref.setIconSpaceReserved(reserved);
            if (pref instanceof PreferenceGroup) {
                setIconSpaceReservedRecursive((PreferenceGroup) pref, reserved);
            }
        }
    }
}
