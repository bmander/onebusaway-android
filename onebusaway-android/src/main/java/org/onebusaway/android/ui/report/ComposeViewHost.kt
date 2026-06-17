/*
 * Copyright (C) 2026 Open Transit Software Foundation
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
package org.onebusaway.android.ui.report

import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.onebusaway.android.ui.compose.theme.ObaTheme

/**
 * Builds a [ComposeView] hosting [content] wrapped in [ObaTheme], for a Fragment's
 * onCreateView. The view is created from the inflater's context (not requireContext()) so it
 * works for an unattached fragment, e.g. when ViewPager2's FragmentStateAdapter passes a null
 * container (see issue #1564).
 */
fun composeFragmentView(
    inflater: LayoutInflater,
    content: @Composable () -> Unit
): ComposeView = ComposeView(inflater.context).apply {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent {
        ObaTheme { content() }
    }
}
