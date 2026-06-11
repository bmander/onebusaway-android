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
package org.onebusaway.android.ui.home

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.onebusaway.android.ui.compose.theme.ObaTheme

/**
 * Java-friendly bridge that wraps the legacy Home content view in a Compose
 * `ModalNavigationDrawer` + a hosted toolbar. Lets `HomeActivity` (still Java) host the Compose
 * drawer without a full Kotlin port: the activity passes its inflated content island + toolbar and
 * a selection callback, and drives the drawer via [setItems]/[setSelected]/[openDrawer]. Same
 * approach as the maps-compose ComposeMapHost bridge.
 */
class HomeShellHost(
    context: Context,
    private val toolbar: View,
    private val content: View,
    private val onItemSelected: NavItemSelectedListener
) {

    /** SAM interface so the Java HomeActivity can pass a method reference. */
    fun interface NavItemSelectedListener {
        fun onSelected(item: HomeNavItem)
    }

    private var itemsState by mutableStateOf<List<HomeNavItem>>(emptyList())
    private var selectedState by mutableStateOf(HomeNavItem.NEARBY)
    private var openRequests by mutableStateOf(0)

    /** Updates the (already region-gated) drawer item list. */
    fun setItems(items: List<HomeNavItem>) {
        itemsState = items
    }

    /** Highlights the current in-place selection. */
    fun setSelected(item: HomeNavItem) {
        selectedState = item
    }

    /** Opens the drawer (e.g. from the toolbar hamburger). */
    fun openDrawer() {
        openRequests++
    }

    /** The view to pass to Activity.setContentView. */
    val view: View = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            ObaTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = androidx.compose.runtime.rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    // drop(1) skips the initial 0 so we don't open on first composition.
                    snapshotFlow { openRequests }.drop(1).collect { drawerState.open() }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        HomeNavDrawerSheet(items = itemsState, selected = selectedState) { item ->
                            scope.launch { drawerState.close() }
                            onItemSelected.onSelected(item)
                        }
                    }
                ) {
                    Column(Modifier.fillMaxSize()) {
                        AndroidView(factory = { toolbar }, modifier = Modifier.fillMaxWidth())
                        AndroidView(factory = { content }, modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}
