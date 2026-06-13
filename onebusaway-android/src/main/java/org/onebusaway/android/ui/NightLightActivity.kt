/*
 * Copyright 2013-2026 Colin McDonough, University of South Florida, Sean J. Barbeau,
 * Open Transit Software Foundation
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
package org.onebusaway.android.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.components.ObaTopAppBar
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.util.UIUtils

/** The dimmed "off" color between flashes (a dark scrim over the theme background). */
private val COLOR_DARK = Color(0xCC000000)

/** Amount of time the light is left on for a single flash, in milliseconds. */
private const val FLASH_TIME_ON = 75L

/** Amount of time between flashes, in milliseconds — two quick blinks then a beat. */
private val WAIT_TIMES = longArrayOf(100, 100, 400)

/**
 * A flashing light that riders can show at night to flag bus drivers. A thin Compose host: the
 * Activity owns the window-level concerns (keep-screen-on, full brightness, the pinned-shortcut
 * flow) and [NightLightScreen] drives the flash sequence.
 */
class NightLightActivity : AppCompatActivity() {

    private var oldScreenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Intent.ACTION_CREATE_SHORTCUT == intent.action) {
            val shortcut = createShortcut()
            setResult(RESULT_OK, shortcut.intent)
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            ObaTheme {
                NightLightScreen(
                    onBack = { finish() },
                    onCreateShortcut = { createShortcut() }
                )
            }
        }
        maybeShowIntroDialog()
    }

    override fun onPause() {
        super.onPause()
        restoreScreenBrightness()
    }

    /**
     * Shows the one-time intro dialog; once the user has seen it (now or previously), brightness
     * goes to full and the flashing starts (the screen flashes whenever the activity is RESUMED).
     */
    private fun maybeShowIntroDialog() {
        @Suppress("DEPRECATION")
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        if (!sp.getBoolean(PREFERENCE_SHOWED_DIALOG, false)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.night_light_dialog_title)
                .setMessage(R.string.night_light_dialog_message)
                .setCancelable(false)
                .setPositiveButton(R.string.night_light_start) { _, _ ->
                    sp.edit().putBoolean(PREFERENCE_SHOWED_DIALOG, true).apply()
                    setFullScreenBrightness()
                }
                .setNegativeButton(R.string.night_light_cancel) { _, _ -> finish() }
                .show()
        } else {
            setFullScreenBrightness()
        }
    }

    private fun setFullScreenBrightness() {
        val lp = window.attributes
        oldScreenBrightness = lp.screenBrightness
        lp.screenBrightness = 1.0f
        window.attributes = lp
    }

    private fun restoreScreenBrightness() {
        val lp = window.attributes
        lp.screenBrightness = oldScreenBrightness
        window.attributes = lp
    }

    /** Creates (and asks the launcher to pin) a home-screen shortcut to this screen. */
    private fun createShortcut(): ShortcutInfoCompat {
        val shortcut = UIUtils.makeShortcutInfo(
            this,
            getString(R.string.stop_info_option_night_light),
            Intent(this, NightLightActivity::class.java),
            R.drawable.ic_night_light
        )
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        return shortcut
    }

    companion object {

        private const val PREFERENCE_SHOWED_DIALOG = "showed_night_light_dialog"

        fun start(context: Context) {
            context.startActivity(Intent(context, NightLightActivity::class.java))
        }
    }
}

/**
 * The flashing screen: white / theme-color / white blinks with a pause between rounds, matching
 * the legacy flash thread. Tapping the screen pauses and resumes the flashing.
 */
@Composable
private fun NightLightScreen(onBack: () -> Unit, onCreateShortcut: () -> Unit) {
    val flashColors = listOf(Color.White, colorResource(R.color.theme_primary), Color.White)
    var flashing by remember { mutableStateOf(true) }
    // The single source of truth for the screen color: a flash color while on, the dark scrim while off.
    var displayColor by remember { mutableStateOf(COLOR_DARK) }

    // RESUMED-only flash loop, replacing the legacy background thread.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(flashing) {
        if (!flashing) {
            displayColor = COLOR_DARK
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var counter = 0
            while (isActive) {
                displayColor = flashColors[counter % flashColors.size]
                delay(FLASH_TIME_ON)
                displayColor = COLOR_DARK
                delay(WAIT_TIMES[counter % WAIT_TIMES.size])
                counter++
            }
        }
    }

    Scaffold(
        topBar = {
            ObaTopAppBar(stringResource(R.string.stop_info_option_night_light), onBack) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.night_light_create_shortcut)) },
                            onClick = {
                                expanded = false
                                onCreateShortcut()
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(displayColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { flashing = !flashing }
        )
    }
}
