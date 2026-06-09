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

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.core.view.MenuItemCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.color.MaterialColors
import org.onebusaway.android.R
import org.onebusaway.android.provider.ObaContract
import org.onebusaway.android.ui.compose.composeFragmentView
import org.onebusaway.android.ui.mylists.MyListContent
import org.onebusaway.android.ui.mylists.MyListViewModel
import org.onebusaway.android.ui.mylists.ReminderItem
import org.onebusaway.android.ui.mylists.ReminderRow
import org.onebusaway.android.ui.mylists.RemindersRepository
import org.onebusaway.android.ui.mylists.RowAction
import org.onebusaway.android.ui.mylists.chooseSortOrder
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.ReminderUtils

/**
 * Saved trip reminders, embedded by [HomeActivity] (via [TAG]) and hosted by [MyRemindersActivity]. A
 * thin Compose host over [MyListViewModel]; tap edits the reminder, the long-press menu mirrors the
 * legacy context menu, and the options menu sorts by name or departure time.
 */
class MyRemindersFragment : Fragment() {

    private val viewModel: MyListViewModel<ReminderItem> by viewModels {
        viewModelFactory {
            initializer { MyListViewModel(RemindersRepository(requireContext().applicationContext)) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = composeFragmentView(inflater) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        MyListContent(
            state = state,
            emptyText = getString(R.string.trip_list_notrips),
            itemKey = { "${it.tripId}:${it.stopId}" }
        ) { reminder ->
            ReminderRow(reminder, onClick = { editReminder(reminder) }, actions = reminderActions(reminder))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
    }

    private fun editReminder(reminder: ReminderItem) {
        TripInfoActivity.start(requireActivity(), reminder.tripId, reminder.stopId)
    }

    private fun reminderActions(reminder: ReminderItem): List<RowAction> = listOf(
        RowAction(getString(R.string.trip_list_context_edit)) { editReminder(reminder) },
        RowAction(getString(R.string.trip_list_context_delete)) { confirmDelete(reminder) },
        RowAction(getString(R.string.trip_list_context_showstop)) {
            ArrivalsListActivity.start(requireActivity(), reminder.stopId)
        },
        RowAction(getString(R.string.trip_list_context_showroute)) {
            RouteInfoActivity.start(requireActivity(), reminder.routeId)
        }
    )

    private fun confirmDelete(reminder: ReminderItem) {
        confirmDeleteReminder(requireActivity()) {
            ReminderUtils.requestDeleteAlarm(
                requireActivity(), ObaContract.Trips.buildUri(reminder.tripId, reminder.stopId)
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        val item = menu.add(R.string.menu_option_sort_by)
        item.setIcon(R.drawable.ic_action_content_sort)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        view?.let {
            val color = MaterialColors.getColor(it, R.attr.colorControlNormal)
            MenuItemCompat.setIconTintList(item, ColorStateList.valueOf(color))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.title == getString(R.string.menu_option_sort_by)) {
            chooseSortOrder(PreferenceUtils.getReminderSortOrderFromPreferences(), R.array.sort_reminders) {
                viewModel.setSort(it)
            }
            return true
        }
        return false
    }

    companion object {

        const val TAG = "MyRemindersFragment"
    }
}
