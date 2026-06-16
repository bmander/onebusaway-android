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

import static org.onebusaway.android.ui.SettingsSupport.setIconSpaceReservedRecursive;
import static org.onebusaway.android.ui.SettingsSupport.validateUrl;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

import com.google.firebase.analytics.FirebaseAnalytics;

import org.onebusaway.android.BuildConfig;
import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.ObaAnalytics;
import org.onebusaway.android.io.PlausibleAnalytics;
import org.onebusaway.android.region.RegionRefresher;
import org.onebusaway.android.region.RegionRepository;
import org.onebusaway.android.travelbehavior.io.coroutines.FirebaseDataPusher;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


/**
 * The advanced settings preference fragment (custom OBA/OTP API URLs, experimental regions, debug
 * data push). Opened as a nested screen from {@link SettingsFragment} via
 * {@code onPreferenceStartFragment}, implemented by the host activity
 * ([org.onebusaway.android.ui.HomeActivity]). Extracted verbatim from the former
 * {@code SettingsActivity$AdvancedSettingsFragment} when the settings screens became a NavHost
 * destination (Campaign C); it reaches the host's region-task callback and the OTP-changed signal
 * through {@link HomeActivity} (the host activity) instead of the deleted SettingsActivity.
 */
@AndroidEntryPoint
public class AdvancedSettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceClickListener,
        Preference.OnPreferenceChangeListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "AdvancedSettings";

    @Inject
    RegionRepository regionRepository;

    private Preference mCustomApiUrlPref;
    private Preference mCustomOtpApiUrlPref;
    private Preference mPushFirebaseData;
    private Preference mResetDonationTimestamps;
    private FirebaseAnalytics mFirebaseAnalytics;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences_advanced, rootKey);
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(requireContext());

        mCustomApiUrlPref = findPreference(getString(R.string.preference_key_oba_api_url));
        if (mCustomApiUrlPref != null) {
            mCustomApiUrlPref.setOnPreferenceChangeListener(this);
            String appName = getString(R.string.app_name);
            mCustomApiUrlPref.setTitle(
                    getString(R.string.preferences_oba_api_servername_title, appName));
        }

        mCustomOtpApiUrlPref = findPreference(getString(R.string.preference_key_otp_api_url));
        if (mCustomOtpApiUrlPref != null) {
            mCustomOtpApiUrlPref.setOnPreferenceChangeListener(this);
        }

        mPushFirebaseData = findPreference(
                getString(R.string.preference_key_push_firebase_data));
        if (mPushFirebaseData != null) {
            mPushFirebaseData.setOnPreferenceClickListener(this);
        }

        mResetDonationTimestamps = findPreference(
                getString(R.string.preference_key_reset_donation_timestamps));
        if (mResetDonationTimestamps != null) {
            mResetDonationTimestamps.setOnPreferenceClickListener(this);
        }

        if (BuildConfig.USE_FIXED_REGION) {
            Preference experimentalRegion = findPreference(
                    getString(R.string.preference_key_experimental_regions));
            PreferenceCategory advancedCategory = findPreference(
                    getString(R.string.preferences_category_advanced));
            if (advancedCategory != null && experimentalRegion != null) {
                advancedCategory.removePreference(experimentalRegion);
            }
        }

        setIconSpaceReservedRecursive(getPreferenceScreen(), false);
    }

    @Override
    public void onResume() {
        super.onResume();
        Application.getPrefs().registerOnSharedPreferenceChangeListener(this);
        updateApiUrlSummaries();
    }

    @Override
    public void onPause() {
        super.onPause();
        Application.getPrefs().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference pref) {
        if (pref.equals(mPushFirebaseData)) {
            FirebaseDataPusher pusher = new FirebaseDataPusher();
            pusher.push(requireContext());
        } else if (pref.equals(mResetDonationTimestamps)) {
            Application.getDonationsManager().setDonationRequestReminderDate(null);
            Application.getDonationsManager().setDonationRequestDismissedDate(null);
        }
        return true;
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        if (preference.equals(mCustomApiUrlPref) && newValue instanceof String) {
            String apiUrl = (String) newValue;
            if (!TextUtils.isEmpty(apiUrl)) {
                if (!validateUrl(apiUrl)) {
                    Toast.makeText(requireContext(),
                            getString(R.string.custom_api_url_error,
                                    getString(R.string.app_name)),
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                regionRepository.clear();
                Log.d(TAG, "User entered new API URL, set region to null.");
            } else {
                Log.d(TAG, "User entered blank API URL, re-initializing regions...");
                NavHelp.goHome(requireActivity(), false);
            }
        } else if (preference.equals(mCustomOtpApiUrlPref) && newValue instanceof String) {
            String apiUrl = (String) newValue;
            if (!TextUtils.isEmpty(apiUrl)) {
                if (!validateUrl(apiUrl)) {
                    Toast.makeText(requireContext(),
                            getString(R.string.custom_otp_api_url_error),
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
            HomeActivity activity = (HomeActivity) requireActivity();
            activity.setOtpCustomAPIUrlChanged(true);
        }
        return true;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) return;

        if (key.equals(getString(R.string.preference_key_experimental_regions))) {
            boolean experimentalServers = sharedPreferences.getBoolean(key, false);
            Log.d(TAG, "Experimental regions preference changed to " + experimentalServers);

            HomeActivity activity = (HomeActivity) requireActivity();
            RegionRefresher.refresh(requireContext(), null, activity::onRegionTaskFinished);

            if (experimentalServers) {
                ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                        Application.get().getPlausibleInstance(),
                        PlausibleAnalytics.REPORT_PREFERENCES_EVENT_URL,
                        getString(R.string.analytics_label_button_press_experimental_on),
                        null);
            } else {
                ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                        Application.get().getPlausibleInstance(),
                        PlausibleAnalytics.REPORT_PREFERENCES_EVENT_URL,
                        getString(R.string.analytics_label_button_press_experimental_off),
                        null);
            }
        } else if (key.equals(getString(R.string.preference_key_oba_api_url))
                || key.equals(getString(R.string.preference_key_otp_api_url))) {
            updateApiUrlSummaries();
        }
    }

    private void updateApiUrlSummaries() {
        if (Application.get().getCurrentRegion() != null) {
            if (mCustomApiUrlPref != null) {
                mCustomApiUrlPref.setSummary(
                        getString(R.string.preferences_oba_api_servername_summary,
                                getString(R.string.app_name)));
            }
            String customOtpApiUrl = Application.get().getCustomOtpApiUrl();
            if (mCustomOtpApiUrlPref != null) {
                if (!TextUtils.isEmpty(customOtpApiUrl)) {
                    mCustomOtpApiUrlPref.setSummary(customOtpApiUrl);
                } else {
                    mCustomOtpApiUrlPref.setSummary(
                            getString(R.string.preferences_otp_api_servername_summary));
                }
            }
        } else {
            if (mCustomApiUrlPref != null) {
                mCustomApiUrlPref.setSummary(Application.get().getCustomApiUrl());
            }
        }
        Application.get().setUseOldOtpApiUrlVersion(false);
    }
}
