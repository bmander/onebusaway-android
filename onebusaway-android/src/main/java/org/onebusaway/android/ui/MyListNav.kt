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
package org.onebusaway.android.ui

import android.app.Activity
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import org.onebusaway.android.R
import org.onebusaway.android.ui.mylists.RouteListItem
import org.onebusaway.android.ui.mylists.RowAction
import org.onebusaway.android.ui.mylists.StopListItem
import org.onebusaway.android.util.UIUtils

/**
 * Shared navigation and row-action wiring for the four My-tab list fragments (recent/starred ×
 * stops/routes). They're thin hosts whose tap/long-press behavior is identical except for the
 * remove-action label, so it lives here as [Fragment] extensions rather than a base class. (This
 * file is in the `ui` package, not `ui.mylists`, so it can reach the package-private
 * [MyTabActivityBase] and [NavHelp].)
 */

/** True when the host activity launched this fragment as a launcher-shortcut picker. */
internal fun Fragment.isInShortcutMode(): Boolean =
    (activity as? MyTabActivityBase)?.isShortcutMode == true

private fun Fragment.stopArrivalsBuilder(stop: StopListItem) =
    ArrivalsListActivity.Builder(requireActivity(), stop.id)
        .setStopName(stop.name)
        .setStopDirection(stop.rawDirection)

/** Opens a stop's arrivals, or returns it as a launcher shortcut in shortcut mode. */
internal fun Fragment.openStop(stop: StopListItem) {
    val builder = stopArrivalsBuilder(stop)
    if (isInShortcutMode()) {
        val shortcut = UIUtils.createStopShortcut(requireActivity(), stop.name, builder)
        requireActivity().setResult(Activity.RESULT_OK, shortcut.intent)
        requireActivity().finish()
    } else {
        builder.setUpMode(NavHelp.UP_MODE_BACK)
        builder.start()
    }
}

/** A stop row's long-press actions (empty in shortcut mode); [removeLabel] is the only per-list delta. */
internal fun Fragment.stopActions(
    stop: StopListItem,
    @StringRes removeLabel: Int,
    onRemove: () -> Unit
): List<RowAction> {
    if (isInShortcutMode()) return emptyList()
    return listOf(
        RowAction(getString(R.string.my_context_showonmap)) {
            HomeActivity.start(requireActivity(), stop.id, stop.lat, stop.lon)
        },
        RowAction(getString(R.string.my_context_create_shortcut)) {
            UIUtils.createStopShortcut(requireActivity(), stop.name, stopArrivalsBuilder(stop))
        },
        RowAction(getString(removeLabel), onRemove)
    )
}

/** Opens a route on the map, or returns it as a launcher shortcut in shortcut mode. */
internal fun Fragment.openRoute(route: RouteListItem) {
    if (isInShortcutMode()) {
        val shortcut = UIUtils.createRouteShortcut(requireActivity(), route.id, route.shortName)
        requireActivity().setResult(Activity.RESULT_OK, shortcut.intent)
        requireActivity().finish()
    } else {
        HomeActivity.start(requireActivity(), route.id)
    }
}

/** A route row's long-press actions (empty in shortcut mode); [removeLabel] is the only per-list delta. */
internal fun Fragment.routeActions(
    route: RouteListItem,
    @StringRes removeLabel: Int,
    onRemove: () -> Unit
): List<RowAction> {
    if (isInShortcutMode()) return emptyList()
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
        add(RowAction(getString(removeLabel), onRemove))
    }
}
