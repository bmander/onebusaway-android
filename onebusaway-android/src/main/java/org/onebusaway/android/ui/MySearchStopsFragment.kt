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
import org.onebusaway.android.ui.search.DefaultStopSearchRepository
import org.onebusaway.android.ui.search.SearchViewModel
import org.onebusaway.android.ui.search.StopSearchContent
import org.onebusaway.android.ui.search.StopSearchResult
import org.onebusaway.android.util.UIUtils

/**
 * The stop search tab inside [MyStopsActivity]. A thin Compose host: search state lives in
 * [SearchViewModel] (debounced incremental search); this fragment only handles navigation and
 * shortcut-mode results.
 */
class MySearchStopsFragment : Fragment() {

    private val viewModel: SearchViewModel<StopSearchResult> by viewModels {
        viewModelFactory {
            initializer {
                SearchViewModel(
                    DefaultStopSearchRepository(requireContext().applicationContext)::search
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
                StopSearchContent(
                    viewModel = viewModel,
                    shortcutMode = isShortcutMode(),
                    onStopClick = ::onStopClicked,
                    onShowOnMap = {
                        HomeActivity.start(requireActivity(), it.id, it.latitude, it.longitude)
                    }
                )
            }
        }
    }

    private fun isShortcutMode(): Boolean =
        (activity as? MyTabActivityBase)?.isShortcutMode == true

    private fun onStopClicked(stop: StopSearchResult) {
        val activity = requireActivity()
        val builder = ArrivalsListActivity.Builder(activity, stop.id)
            .setStopName(stop.serverName)
            .setStopDirection(stop.direction)
        if (isShortcutMode()) {
            val shortcut = UIUtils.createStopShortcut(activity, stop.serverName, builder)
            activity.setResult(Activity.RESULT_OK, shortcut.intent)
            activity.finish()
        } else {
            builder.setUpMode(NavHelp.UP_MODE_BACK)
            builder.start()
        }
    }

    companion object {

        const val TAB_NAME = "search"
    }
}
