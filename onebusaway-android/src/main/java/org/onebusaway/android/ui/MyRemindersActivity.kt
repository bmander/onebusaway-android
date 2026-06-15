/*
 * Copyright (C) 2014 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com)
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

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.components.ObaTopAppBar
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.mylists.ReminderListDestination
import org.onebusaway.android.ui.mylists.RemindersRepository
import org.onebusaway.android.ui.mylists.chooseSortOrder
import org.onebusaway.android.ui.mylists.hostListVm
import org.onebusaway.android.util.PreferenceUtils

/**
 * Standalone saved-trip-reminders screen — a single Compose list ([ReminderListDestination]) with a
 * sort action. (Home embeds the same destination in its drawer; this activity is the launcher /
 * notification entry point.)
 */
class MyRemindersActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reminders = hostListVm("reminders") { RemindersRepository(applicationContext) }
        setContent {
            ObaTheme {
                Scaffold(
                    topBar = {
                        ObaTopAppBar(
                            title = stringResource(R.string.app_name),
                            onBack = { NavHelp.goHome(this, false) }
                        ) {
                            IconButton(onClick = {
                                chooseSortOrder(
                                    PreferenceUtils.getReminderSortOrderFromPreferences(),
                                    R.array.sort_reminders
                                ) { reminders.setSort(it) }
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_action_content_sort),
                                    contentDescription = stringResource(R.string.menu_option_sort_by)
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        ReminderListDestination(
                            reminders,
                            emptyText = R.string.trip_list_notrips,
                            onClick = { this@MyRemindersActivity.editReminder(it) },
                            actions = { this@MyRemindersActivity.reminderActions(it) }
                        )
                    }
                }
            }
        }
    }
}
