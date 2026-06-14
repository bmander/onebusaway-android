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
package org.onebusaway.android.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.regions.RegionsRoute
import org.onebusaway.android.ui.regions.RegionsViewModel

/**
 * Lets the user manually pick the OBA region (server deployment) to use.
 *
 * Compose + MVVM screen: the Activity is a thin host for [RegionsRoute]; all state lives in
 * [RegionsViewModel]. Selecting a region sets it app-wide, disables automatic region
 * selection (with a toast) if it was on, and returns to the home screen — matching the
 * legacy ListFragment-based picker.
 */
@AndroidEntryPoint
class RegionsActivity : AppCompatActivity() {

    private val viewModel: RegionsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ObaTheme {
                RegionsRoute(
                    viewModel = viewModel,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRegionSelected = { autoSelectDisabled ->
                        if (autoSelectDisabled) {
                            Toast.makeText(
                                this,
                                R.string.region_disabled_auto_selection,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        NavHelp.goHome(this, false)
                    }
                )
            }
        }
    }

    companion object {

        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, RegionsActivity::class.java))
        }
    }
}
