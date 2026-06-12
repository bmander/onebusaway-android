/*
 * Copyright (C) 2012-2015 Paul Watts (paulcwatts@gmail.com), University of South Florida
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
import org.onebusaway.android.ui.mylists.StarredStopsRepository
import org.onebusaway.android.ui.mylists.StopListItem
import org.onebusaway.android.ui.mylists.StopRow
import org.onebusaway.android.ui.mylists.chooseSortOrder
import org.onebusaway.android.ui.mylists.confirmClear
import org.onebusaway.android.util.PreferenceUtils

/**
 * The starred-stops tab (inside [MyStopsActivity]) and the home-screen starred view (embedded by
 * [HomeActivity] via [TAG]). A thin Compose host over [MyListViewModel] with a [StarredStopsRepository]
 * that supplies live next-arrivals; the options menu adds sort over the shared tap/long-press wiring.
 */
class MyStarredStopsFragment : Fragment() {

    private val viewModel: MyListViewModel<StopListItem> by viewModels {
        viewModelFactory {
            initializer { MyListViewModel(StarredStopsRepository(requireContext().applicationContext)) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = composeFragmentView(inflater) {
        val host = requireListHost()
        val shortcutMode = isInShortcutMode()
        val state by viewModel.state.collectAsStateWithLifecycle()
        MyListContent(state = state, emptyText = getString(R.string.my_no_starred_stops), itemKey = { it.id }) { stop ->
            StopRow(
                stop,
                onClick = { host.openStop(stop, shortcutMode) },
                actions = host.stopActions(stop, R.string.my_context_remove_star, shortcutMode) { viewModel.remove(stop.id) }
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.my_starred_stop_options, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.clear_starred -> {
            requireListHost().confirmClear(
                R.string.my_option_clear_starred_stops_title,
                R.string.my_option_clear_starred_stops_confirm
            ) { viewModel.clearAll() }
            true
        }
        R.id.sort_stops -> {
            requireListHost().chooseSortOrder(PreferenceUtils.getStopSortOrderFromPreferences(), R.array.sort_stops) {
                viewModel.setSort(it)
            }
            true
        }
        else -> false
    }

    companion object {

        const val TAG = "MyStarredStopsFragment"
        const val TAB_NAME = "starred"
    }
}
