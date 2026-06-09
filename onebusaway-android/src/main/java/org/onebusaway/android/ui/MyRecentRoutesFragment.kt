/*
 * Copyright (C) 2010-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South  Florida (sjbarbeau@gmail.com)
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
import org.onebusaway.android.ui.mylists.RecentRoutesRepository
import org.onebusaway.android.ui.mylists.RouteListItem
import org.onebusaway.android.ui.mylists.RouteRow
import org.onebusaway.android.ui.mylists.RowAction
import org.onebusaway.android.ui.mylists.confirmClear
import org.onebusaway.android.util.UIUtils

/**
 * The recent-routes tab inside [MyRoutesActivity] / [MyRecentStopsAndRoutesActivity]. A thin Compose
 * host over [MyListViewModel]; tap opens the route on the map (or returns a shortcut in shortcut
 * mode), and the overflow mirrors the legacy context menu.
 */
class MyRecentRoutesFragment : Fragment() {

    private val viewModel: MyListViewModel<RouteListItem> by viewModels {
        viewModelFactory {
            initializer { MyListViewModel(RecentRoutesRepository(requireContext().applicationContext)) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = composeFragmentView(inflater) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        MyListContent(state = state, emptyText = getString(R.string.my_no_recent_routes), itemKey = { it.id }) { route ->
            RouteRow(route, onClick = { onRouteClick(route) }, actions = routeActions(route))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
    }

    private fun isShortcutMode(): Boolean =
        (activity as? MyTabActivityBase)?.isShortcutMode == true

    private fun onRouteClick(route: RouteListItem) {
        if (isShortcutMode()) {
            val shortcut = UIUtils.createRouteShortcut(requireActivity(), route.id, route.shortName)
            requireActivity().setResult(Activity.RESULT_OK, shortcut.intent)
            requireActivity().finish()
        } else {
            HomeActivity.start(requireActivity(), route.id)
        }
    }

    /** Overflow actions match the legacy context menu; hidden in shortcut mode (tap creates the shortcut). */
    private fun routeActions(route: RouteListItem): List<RowAction> {
        if (isShortcutMode()) return emptyList()
        return buildList {
            add(RowAction(getString(R.string.my_context_showonmap)) {
                HomeActivity.start(requireActivity(), route.id)
            })
            route.url?.let { url ->
                add(RowAction(getString(R.string.my_context_show_schedule)) {
                    UIUtils.goToUrl(requireActivity(), url)
                })
            }
            add(RowAction(getString(R.string.my_context_create_shortcut)) {
                UIUtils.createRouteShortcut(requireActivity(), route.id, route.shortName)
            })
            add(RowAction(getString(R.string.my_context_remove_recent)) { viewModel.remove(route.id) })
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.my_recent_route_options, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.clear_recent) {
            confirmClear(
                R.string.my_option_clear_recent_routes_title,
                R.string.my_option_clear_recent_routes_confirm
            ) { viewModel.clearAll() }
            return true
        }
        return false
    }

    companion object {

        const val TAB_NAME = "recent_routes"
    }
}
