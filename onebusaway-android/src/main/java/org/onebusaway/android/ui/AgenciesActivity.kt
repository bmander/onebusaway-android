/*
 * Copyright (C) 2012-2013 Paul Watts (paulcwatts@gmail.com) and individual contributors.
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
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.onebusaway.android.ui.agencies.AgenciesRoute
import org.onebusaway.android.ui.agencies.AgenciesViewModel
import org.onebusaway.android.ui.agencies.DefaultAgenciesRepository
import org.onebusaway.android.ui.compose.theme.ObaTheme

/**
 * Lists the transit agencies supported in the current region.
 *
 * This is the app's pilot Compose + MVVM screen: the Activity is a thin host for
 * [AgenciesRoute]; all state lives in [AgenciesViewModel].
 */
class AgenciesActivity : AppCompatActivity() {

    private val viewModel: AgenciesViewModel by viewModels {
        viewModelFactory {
            initializer { AgenciesViewModel(DefaultAgenciesRepository(applicationContext)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ObaTheme {
                AgenciesRoute(viewModel, onBack = { NavHelp.goHome(this, false) })
            }
        }
    }

    companion object {

        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, AgenciesActivity::class.java))
        }
    }
}
