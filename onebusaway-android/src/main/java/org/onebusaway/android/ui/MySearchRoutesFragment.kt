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
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.search.DefaultRouteSearchRepository
import org.onebusaway.android.ui.search.RouteSearchContent
import org.onebusaway.android.ui.search.RouteSearchResult
import org.onebusaway.android.ui.search.SearchViewModel
import org.onebusaway.android.util.UIUtils

/**
 * The route search tab inside [MyRoutesActivity]. A thin Compose host: search state lives in
 * [SearchViewModel] (debounced incremental search); this fragment only handles navigation and
 * shortcut-mode results.
 */
class MySearchRoutesFragment : Fragment() {

    private val viewModel: SearchViewModel<RouteSearchResult> by viewModels {
        viewModelFactory {
            initializer {
                SearchViewModel(
                    DefaultRouteSearchRepository(requireContext().applicationContext)::search
                )
            }
        }
    }

    // ComposeView is built from the inflater's context (not requireContext()) so onCreateView
    // works for an unattached fragment — see MySearchFragmentOnCreateViewTest (#1564)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            ObaTheme {
                RouteSearchContent(
                    viewModel = viewModel,
                    shortcutMode = isShortcutMode(),
                    onRouteClick = ::onRouteClicked,
                    onShowOnMap = { HomeActivity.start(requireActivity(), it.id) },
                    onShowSchedule = { route ->
                        route.url?.let { UIUtils.goToUrl(requireActivity(), it) }
                    },
                    onCreateShortcut = {
                        UIUtils.createRouteShortcut(requireContext(), it.id, it.shortName)
                    }
                )
            }
        }
    }

    private fun isShortcutMode(): Boolean =
        (activity as? MyTabActivityBase)?.isShortcutMode == true

    private fun onRouteClicked(route: RouteSearchResult) {
        val activity = requireActivity()
        if (isShortcutMode()) {
            val shortcut = UIUtils.createRouteShortcut(activity, route.id, route.shortName)
            activity.setResult(Activity.RESULT_OK, shortcut.intent)
            activity.finish()
        } else {
            RouteInfoActivity.start(activity, route.id)
        }
    }

    companion object {

        const val TAB_NAME = "search"
    }
}
