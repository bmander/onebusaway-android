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
import static org.onebusaway.android.util.UIUtils.setAppTheme;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.analytics.FirebaseAnalytics;

import org.onebusaway.android.BuildConfig;
import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.ObaAnalytics;
import org.onebusaway.android.io.PlausibleAnalytics;
import org.onebusaway.android.io.elements.ObaRegion;
import org.onebusaway.android.provider.ObaContract;
import org.onebusaway.android.util.BackupUtils;
import org.onebusaway.android.util.BuildFlavorUtils;
import org.onebusaway.android.util.ShowcaseViewUtils;

/**
 * The main settings preference fragment. Hosted by the Settings NavHost destination
 * ([org.onebusaway.android.ui.HomeActivity]); nested screens (Advanced) open via
 * {@code onPreferenceStartFragment}, which the host activity implements. Extracted verbatim from the
 * former {@code SettingsActivity$SettingsFragment} when the settings screens became a destination
 * (Campaign C); the only behavioral differences are that the ringtone and backup pickers now use
 * {@code ActivityResultLauncher}s instead of {@code startActivityForResult}.
 */
public class SettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceClickListener,
        Preference.OnPreferenceChangeListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "SettingsFragment";

    private Preference mRegionPref;
    private Preference mLeftHandMode;
    private Preference mAnalyticsPref;
    private Preference mHideAlertsPref;
    private Preference mTutorialPref;
    private Preference mDonatePref;
    private Preference mPoweredByObaPref;
    private Preference mAboutPref;

    private Preference mAdvancedPref;
    private Preference mSaveBackup;
    private Preference mRestoreBackup;
    private Preference mRingtonePref;
    private ListPreference mPreferredUnits;
    private ListPreference mPreferredTempUnits;
    private ListPreference mThemePref;
    private ListPreference mMapMode;
    private FirebaseAnalytics mFirebaseAnalytics;

    // The ringtone / save-backup / restore-backup pickers are launched via the Activity Result API
    // (the screen no longer has its own Activity to receive onActivityResult). Registered in
    // onCreate so the system can re-deliver a result across recreation.
    private ActivityResultLauncher<Intent> mRingtonePickerLauncher;
    private ActivityResultLauncher<Intent> mSaveBackupLauncher;
    private ActivityResultLauncher<Intent> mRestoreBackupLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRingtonePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == android.app.Activity.RESULT_OK
                            && result.getData() != null) {
                        Uri ringtoneUri = result.getData().getParcelableExtra(
                                RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                        String value = ringtoneUri != null ? ringtoneUri.toString() : "";
                        Application.getPrefs().edit()
                                .putString(getString(R.string.preference_key_notification_sound),
                                        value)
                                .apply();
                    }
                });
        mSaveBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == android.app.Activity.RESULT_OK
                            && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            BackupUtils.save(requireActivity(), uri);
                        }
                    }
                });
        mRestoreBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == android.app.Activity.RESULT_OK
                            && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            BackupUtils.restore(requireActivity(), uri);
                        }
                    }
                });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(requireContext());

        mRegionPref = findPreference(getString(R.string.preference_key_region));
        mRegionPref.setOnPreferenceClickListener(this);

        mLeftHandMode = findPreference(getString(R.string.preference_key_left_hand_mode));
        mLeftHandMode.setOnPreferenceChangeListener(this);

        mSaveBackup = findPreference(getString(R.string.preference_key_save_backup));
        mSaveBackup.setOnPreferenceClickListener(this);

        mRestoreBackup = findPreference(getString(R.string.preference_key_restore_backup));
        mRestoreBackup.setOnPreferenceClickListener(this);

        mAnalyticsPref = findPreference(getString(R.string.preferences_key_analytics));
        mAnalyticsPref.setOnPreferenceChangeListener(this);

        mHideAlertsPref = findPreference(getString(R.string.preference_key_hide_alerts));
        mHideAlertsPref.setOnPreferenceChangeListener(this);

        mTutorialPref = findPreference(getString(R.string.preference_key_tutorial));
        mTutorialPref.setOnPreferenceClickListener(this);

        mDonatePref = findPreference(getString(R.string.preferences_key_donate));
        mDonatePref.setOnPreferenceClickListener(this);

        mPoweredByObaPref = findPreference(getString(R.string.preferences_key_powered_by_oba));
        mPoweredByObaPref.setOnPreferenceClickListener(this);

        mAboutPref = findPreference(getString(R.string.preferences_key_about));
        mAboutPref.setOnPreferenceClickListener(this);

        // Campaign C: open the Advanced sub-screen via the settings NavHost destination (see
        // HomeActivity.showSettingsSubScreen) instead of the preference framework's app:fragment.
        mAdvancedPref = findPreference("pref_advanced");
        if (mAdvancedPref != null) {
            mAdvancedPref.setOnPreferenceClickListener(this);
        }

        mMapMode = findPreference(getString(R.string.preference_key_map_mode));
        mMapMode.setOnPreferenceChangeListener(this);

        mPreferredUnits = findPreference(getString(R.string.preference_key_preferred_units));

        mPreferredTempUnits = findPreference(
                getString(R.string.preference_key_preferred_temperature_units));

        mThemePref = findPreference(getString(R.string.preference_key_app_theme));
        mThemePref.setOnPreferenceChangeListener(this);

        mRingtonePref = findPreference(getString(R.string.preference_key_notification_sound));
        if (mRingtonePref != null) {
            mRingtonePref.setOnPreferenceClickListener(this);
        }

        PreferenceScreen preferenceScreen = getPreferenceScreen();

        if (BuildConfig.USE_FIXED_REGION) {
            PreferenceCategory regionCategory = findPreference(
                    getString(R.string.preferences_category_location));
            if (regionCategory != null) {
                regionCategory.removeAll();
                preferenceScreen.removePreference(regionCategory);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Preference notifications = findPreference(
                    getString(R.string.preference_key_notifications));
            if (notifications != null) {
                preferenceScreen.removePreference(notifications);
            }
        }

        if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)) {
            Preference logsCategory = findPreference(
                    getString(R.string.preferences_key_user_debugging_logs_category));
            if (logsCategory != null) {
                preferenceScreen.removePreference(logsCategory);
            }
        }

        PreferenceCategory aboutCategory = findPreference(
                getString(R.string.preferences_category_about));
        if (aboutCategory != null) {
            if (BuildFlavorUtils.isOBABuildFlavor()) {
                aboutCategory.removePreference(mPoweredByObaPref);
            } else {
                aboutCategory.removePreference(mDonatePref);
            }
        }

        updateBrandedPreferenceSummaries();
        setIconSpaceReservedRecursive(getPreferenceScreen(), false);
    }

    @Override
    public void onResume() {
        super.onResume();
        Application.getPrefs().registerOnSharedPreferenceChangeListener(this);

        changePreferenceSummary(getString(R.string.preference_key_region));
        changePreferenceSummary(getString(R.string.preference_key_preferred_units));
        changePreferenceSummary(getString(R.string.preference_key_preferred_temperature_units));
        changePreferenceSummary(getString(R.string.preference_key_app_theme));
        changePreferenceSummary(getString(R.string.preference_key_map_mode));

        ObaRegion obaRegion = Application.get().getCurrentRegion();
        if (obaRegion != null && TextUtils.isEmpty(obaRegion.getOtpBaseUrl())) {
            PreferenceCategory notifications = findPreference(
                    getString(R.string.preference_key_notifications));
            Preference tripPlan = findPreference(
                    getString(R.string.preference_key_trip_plan_notifications));
            if (notifications != null && tripPlan != null) {
                notifications.removePreference(tripPlan);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Application.getPrefs().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference pref) {
        String key = pref.getKey();
        Log.d(TAG, "preference - " + key);

        if (pref.equals(mRegionPref)) {
            RegionsActivity.start(requireActivity());
        } else if (pref.equals(mTutorialPref)) {
            ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                    Application.get().getPlausibleInstance(),
                    PlausibleAnalytics.REPORT_PREFERENCES_EVENT_URL,
                    getString(R.string.analytics_label_button_press_tutorial),
                    null);
            ShowcaseViewUtils.resetAllTutorials(requireContext());
            NavHelp.goHome(requireActivity(), true);
        } else if (pref.equals(mDonatePref)) {
            startActivity(Application.getDonationsManager().buildOpenDonationsPageIntent());
        } else if (pref.equals(mPoweredByObaPref)) {
            ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                    Application.get().getPlausibleInstance(),
                    PlausibleAnalytics.REPORT_PREFERENCES_EVENT_URL,
                    getString(R.string.analytics_label_button_press_powered_by_oba),
                    null);
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.powered_by_oba_url)));
            startActivity(intent);
        } else if (pref.equals(mAboutPref)) {
            ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                    Application.get().getPlausibleInstance(),
                    PlausibleAnalytics.REPORT_PREFERENCES_EVENT_URL,
                    getString(R.string.analytics_label_button_press_about),
                    null);
            AboutActivity.start(requireActivity());
        } else if (pref.equals(mSaveBackup)) {
            mSaveBackupLauncher.launch(BackupUtils.buildCreateBackupFileIntent());
        } else if (pref.equals(mRestoreBackup)) {
            mRestoreBackupLauncher.launch(BackupUtils.buildSelectBackupFileIntent());
        } else if (pref.equals(mRingtonePref)) {
            launchRingtonePicker();
        } else if (pref.equals(mAdvancedPref)) {
            if (requireActivity() instanceof HomeActivity) {
                ((HomeActivity) requireActivity())
                        .showSettingsSubScreen(AdvancedSettingsFragment.class.getName());
            }
        }
        return true;
    }

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        if (preference instanceof ListPreference) {
            // This ensures the popup uses the M3 Alert Dialog Builder
            // Will be dismissed if device is rotated while open
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(preference.getTitle())
                    .setSingleChoiceItems(
                            ((ListPreference) preference).getEntries(),
                            ((ListPreference) preference).findIndexOfValue(((ListPreference) preference).getValue()),
                            (dialog, which) -> {
                                String value = ((ListPreference) preference).getEntryValues()[which].toString();
                                if (preference.callChangeListener(value)) {
                                    ((ListPreference) preference).setValue(value);
                                }
                                dialog.dismiss();
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            super.onDisplayPreferenceDialog(preference);
        }
    }

    private void launchRingtonePicker() {
        String existingValue = Application.getPrefs().getString(
                getString(R.string.preference_key_notification_sound), null);
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_NOTIFICATION);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
        if (existingValue != null) {
            if (existingValue.isEmpty()) {
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, (Uri) null);
            } else {
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        Uri.parse(existingValue));
            }
        }
        mRingtonePickerLauncher.launch(intent);
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        if (preference.equals(mLeftHandMode) && newValue instanceof Boolean) {
            ObaAnalytics.setLeftHanded(mFirebaseAnalytics, (Boolean) newValue);
        } else if (preference.equals(mHideAlertsPref) && newValue instanceof Boolean) {
            if ((Boolean) newValue) {
                ObaContract.ServiceAlerts.hideAllAlerts();
            }
        } else if (preference.equals(mThemePref) && newValue instanceof String) {
            setAppTheme((String) newValue);
            requireActivity().recreate();
        } else if (preference.equals(mAnalyticsPref) && newValue instanceof Boolean) {
            ObaAnalytics.setSendAnonymousData(mFirebaseAnalytics, (Boolean) newValue);
        }
        return true;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) return;

        if (key.equals(getString(R.string.preference_key_oba_api_url))) {
            changePreferenceSummary(key);
        } else if (key.equals(getString(R.string.preference_key_otp_api_url))) {
            changePreferenceSummary(key);
        } else if (key.equalsIgnoreCase(
                getString(R.string.preference_key_preferred_units))) {
            changePreferenceSummary(key);
        } else if (key.equalsIgnoreCase(getString(R.string.preference_key_app_theme))) {
            changePreferenceSummary(key);
            setAppTheme(sharedPreferences.getString(key,
                    getString(R.string.preferences_app_theme_option_system_default)));
        } else if (key.equalsIgnoreCase(
                getString(R.string.preference_key_auto_select_region))) {
            boolean autoSelect = sharedPreferences.getBoolean(key, true);
            if (autoSelect) {
                ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                        Application.get().getPlausibleInstance(),
                        PlausibleAnalytics.REPORT_PREFERENCES_EVENT_URL,
                        getString(R.string.analytics_label_button_press_auto),
                        null);
            } else {
                ObaAnalytics.reportUiEvent(mFirebaseAnalytics,
                        Application.get().getPlausibleInstance(),
                        PlausibleAnalytics.REPORT_PREFERENCES_EVENT_URL,
                        getString(R.string.analytics_label_button_press_manual),
                        null);
            }
        } else if (key.equalsIgnoreCase(getString(R.string.preferences_key_analytics))) {
            Boolean isAnalyticsActive = sharedPreferences.getBoolean(key, true);
            ObaAnalytics.setSendAnonymousData(mFirebaseAnalytics, isAnalyticsActive);
        } else if (key.equalsIgnoreCase(
                getString(R.string.preference_key_show_negative_arrivals))) {
            boolean showDepartedBuses = sharedPreferences.getBoolean(key, false);
            ObaAnalytics.setShowDepartedVehicles(mFirebaseAnalytics, showDepartedBuses);
        } else if (key.equalsIgnoreCase(
                getString(R.string.preference_key_preferred_temperature_units))) {
            changePreferenceSummary(key);
        } else if (key.equalsIgnoreCase(getString(R.string.preference_key_map_mode))) {
            changePreferenceSummary(key);
        }
    }

    private void changePreferenceSummary(String preferenceKey) {
        if (preferenceKey.equalsIgnoreCase(getString(R.string.preference_key_region))
                || preferenceKey.equalsIgnoreCase(
                getString(R.string.preference_key_oba_api_url))) {
            if (Application.get().getCurrentRegion() != null) {
                mRegionPref.setSummary(Application.get().getCurrentRegion().getName());
            } else {
                mRegionPref.setSummary(
                        getString(R.string.preferences_region_summary_custom_api));
            }
        } else if (preferenceKey.equalsIgnoreCase(
                getString(R.string.preference_key_preferred_units))) {
            mPreferredUnits.setSummary(mPreferredUnits.getValue());
        } else if (preferenceKey.equalsIgnoreCase(
                getString(R.string.preference_key_app_theme))) {
            mThemePref.setSummary(mThemePref.getValue());
        } else if (preferenceKey.equalsIgnoreCase(
                getString(R.string.preference_key_preferred_temperature_units))) {
            mPreferredTempUnits.setSummary(mPreferredTempUnits.getValue());
        } else if (preferenceKey.equalsIgnoreCase(
                getString(R.string.preference_key_map_mode))) {
            mMapMode.setSummary(mMapMode.getValue());
        }
    }

    private void updateBrandedPreferenceSummaries() {
        String appName = getString(R.string.app_name);

        Preference soundPref = findPreference(
                getString(R.string.preference_key_notification_sound));
        if (soundPref != null) {
            soundPref.setSummary(
                    getString(R.string.preferences_preferred_sound_summary, appName));
        }

        Preference vibratePref = findPreference(
                getString(R.string.preference_key_preference_vibrate_allowed));
        if (vibratePref != null) {
            vibratePref.setSummary(
                    getString(R.string.preferences_preferred_vibration_summary, appName));
        }

        if (mRestoreBackup != null) {
            mRestoreBackup.setSummary(
                    getString(R.string.preferences_restore_summary, appName));
        }

        if (mDonatePref != null) {
            mDonatePref.setSummary(
                    getString(R.string.preferences_donate_summary, appName));
        }

        if (mPoweredByObaPref != null) {
            mPoweredByObaPref.setTitle(
                    getString(R.string.preferences_powered_by_oba_title, appName));
        }

        Preference destLogsPref = findPreference(
                getString(R.string.preferences_key_user_share_destination_logs));
        if (destLogsPref != null) {
            destLogsPref.setSummary(
                    getString(R.string.preferences_user_share_destination_logs_summary,
                            appName));
        }
    }
}
