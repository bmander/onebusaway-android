/*
 * Copyright (C) 2011 Paul Watts (paulcwatts@gmail.com)
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

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.composeFragmentView
import org.onebusaway.android.ui.mylists.MyListContent
import org.onebusaway.android.ui.mylists.MyListViewModel
import org.onebusaway.android.ui.mylists.RecentStopsRepository
import org.onebusaway.android.ui.mylists.RowAction
import org.onebusaway.android.ui.mylists.StopListItem
import org.onebusaway.android.ui.mylists.StopRow
import org.onebusaway.android.ui.mylists.confirmClear
import org.onebusaway.android.util.UIUtils

/**
 * The recent-stops tab inside [MyStopsActivity] / [MyRecentStopsAndRoutesActivity]. A thin Compose
 * host over [MyListViewModel]; the list re-queries on content changes, and tap / overflow handle
 * navigation, shortcut-mode results, and recent-list removal.
 */
class MyRecentStopsFragment : Fragment() {

    private val viewModel: MyListViewModel<StopListItem> by viewModels {
        viewModelFactory {
            initializer { MyListViewModel(RecentStopsRepository(requireContext().applicationContext)) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = composeFragmentView(inflater) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        MyListContent(state = state, emptyText = getString(R.string.my_no_recent_stops), itemKey = { it.id }) { stop ->
            StopRow(stop, onClick = { onStopClick(stop) }, actions = stopActions(stop))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
    }

    private fun isShortcutMode(): Boolean =
        (activity as? MyTabActivityBase)?.isShortcutMode == true

    private fun arrivalsBuilder(stop: StopListItem) =
        ArrivalsListActivity.Builder(requireActivity(), stop.id)
            .setStopName(stop.name)
            .setStopDirection(stop.rawDirection)

    private fun onStopClick(stop: StopListItem) {
        val builder = arrivalsBuilder(stop)
        if (isShortcutMode()) {
            val shortcut = UIUtils.createStopShortcut(requireActivity(), stop.name, builder)
            requireActivity().setResult(Activity.RESULT_OK, shortcut.intent)
            requireActivity().finish()
        } else {
            builder.setUpMode(NavHelp.UP_MODE_BACK)
            builder.start()
        }
    }

    /** Overflow actions match the legacy context menu; hidden in shortcut mode (tap creates the shortcut). */
    private fun stopActions(stop: StopListItem): List<RowAction> {
        if (isShortcutMode()) return emptyList()
        return listOf(
            RowAction(getString(R.string.my_context_showonmap)) {
                HomeActivity.start(requireActivity(), stop.id, stop.lat, stop.lon)
            },
            RowAction(getString(R.string.my_context_create_shortcut)) {
                UIUtils.createStopShortcut(requireActivity(), stop.name, arrivalsBuilder(stop))
            },
            RowAction(getString(R.string.my_context_remove_recent)) { viewModel.remove(stop.id) }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.my_recent_stop_options, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.clear_recent) {
            confirmClear(
                R.string.my_option_clear_recent_stops_title,
                R.string.my_option_clear_recent_stops_confirm
            ) { viewModel.clearAll() }
            return true
        }
        return false
    }

    companion object {

        const val TAB_NAME = "recent_stops"
    }
}
