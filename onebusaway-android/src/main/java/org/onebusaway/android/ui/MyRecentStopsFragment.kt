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
import org.onebusaway.android.ui.mylists.StopListItem
import org.onebusaway.android.ui.mylists.StopRow
import org.onebusaway.android.ui.mylists.confirmClear

/**
 * The recent-stops tab inside [MyStopsActivity] / [MyRecentStopsAndRoutesActivity]. A thin Compose
 * host over [MyListViewModel]; tap/long-press wiring is shared with the other My-tab list fragments.
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
            StopRow(
                stop,
                onClick = { openStop(stop) },
                actions = stopActions(stop, R.string.my_context_remove_recent) { viewModel.remove(stop.id) }
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
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
