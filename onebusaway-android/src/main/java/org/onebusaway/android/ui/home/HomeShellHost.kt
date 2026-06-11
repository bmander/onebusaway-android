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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.onebusaway.android.ui.compose.theme.ObaTheme

/**
 * Java-friendly bridge that wraps the legacy Home content in a Compose `ModalNavigationDrawer` +
 * a hosted toolbar + a Material3 `BottomSheetScaffold`. Lets `HomeActivity` (still Java) host the
 * Compose shell without a full Kotlin port: the activity passes its inflated map content + arrivals
 * sheet content + toolbar and a selection callback, and drives the drawer / sheet imperatively.
 *
 * The `BottomSheetScaffold` replaces the third-party `SlidingUpPanelLayout`. There is no half-anchor:
 * [Sheet.COLLAPSED] is `PartiallyExpanded` (peek) and [Sheet.EXPANDED] is `Expanded` (full). The
 * activity drives it via [setSheetPeekHeightPx]/[collapseSheet]/[expandSheet]/[hideSheet] and reacts
 * to user drags via [SheetStateListener]. Same bridge approach as the maps-compose ComposeMapHost.
 */
@OptIn(ExperimentalMaterial3Api::class)
class HomeShellHost(
    context: Context,
    private val toolbar: View,
    private val mapContent: View,
    private val sheetContent: View,
    private val onItemSelected: NavItemSelectedListener,
    private val sheetListener: SheetStateListener
) {

    /** The three sheet positions (the legacy ANCHORED half-state is intentionally dropped). */
    enum class Sheet { HIDDEN, COLLAPSED, EXPANDED }

    /** SAM interface so the Java HomeActivity can pass a method reference. */
    fun interface NavItemSelectedListener {
        fun onSelected(item: HomeNavItem)
    }

    /** SAM interface notified when the sheet settles into a new resting state (e.g. user drag). */
    fun interface SheetStateListener {
        fun onSheetState(state: Sheet)
    }

    // --- Drawer state ---
    private var itemsState by mutableStateOf<List<HomeNavItem>>(emptyList())
    private var selectedState by mutableStateOf(HomeNavItem.NEARBY)
    private var openRequests by mutableStateOf(0)

    // --- Sheet state ---
    private var peekPx by mutableStateOf(0)
    private var sheetCommandTarget = Sheet.HIDDEN
    private var sheetCommandNonce by mutableStateOf(0)

    /** Last observed resting state, mirrored here so Java can query it synchronously (main thread). */
    @Volatile
    private var currentSheet = Sheet.HIDDEN

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

    /** Sets the collapsed peek height (the arrivals header height), in pixels. */
    fun setSheetPeekHeightPx(px: Int) {
        peekPx = px
    }

    fun collapseSheet() = command(Sheet.COLLAPSED)

    fun expandSheet() = command(Sheet.EXPANDED)

    fun hideSheet() = command(Sheet.HIDDEN)

    /** Matches the legacy `isSlidingPanelCollapsed()`: collapsed = anything that isn't fully expanded. */
    fun isSheetCollapsed(): Boolean = currentSheet != Sheet.EXPANDED

    fun isSheetExpanded(): Boolean = currentSheet == Sheet.EXPANDED

    fun isSheetHidden(): Boolean = currentSheet == Sheet.HIDDEN

    private fun command(target: Sheet) {
        sheetCommandTarget = target
        sheetCommandNonce++
    }

    /** The view to pass to Activity.setContentView. */
    val view: View = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            ObaTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    // drop(1) skips the initial 0 so we don't open on first composition.
                    snapshotFlow { openRequests }.drop(1).collect { drawerState.open() }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    // Open only via the toolbar hamburger; without this an edge-swipe to pan the map
                    // would instead drag the drawer open. The scrim tap / Back still close it.
                    gesturesEnabled = false,
                    drawerContent = {
                        HomeNavDrawerSheet(items = itemsState, selected = selectedState) { item ->
                            scope.launch { drawerState.close() }
                            onItemSelected.onSelected(item)
                        }
                    }
                ) {
                    val density = LocalDensity.current
                    val sheetState = rememberStandardBottomSheetState(
                        initialValue = SheetValue.Hidden,
                        skipHiddenState = false
                    )
                    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

                    // Mirror user-driven resting states back to the activity (drag/settle).
                    LaunchedEffect(sheetState) {
                        snapshotFlow { sheetState.currentValue }.collect { value ->
                            val mapped = when (value) {
                                SheetValue.Hidden -> Sheet.HIDDEN
                                SheetValue.PartiallyExpanded -> Sheet.COLLAPSED
                                SheetValue.Expanded -> Sheet.EXPANDED
                            }
                            currentSheet = mapped
                            sheetListener.onSheetState(mapped)
                        }
                    }

                    // Carry out imperative commands from the activity (show/collapse/expand/hide).
                    LaunchedEffect(Unit) {
                        snapshotFlow { sheetCommandNonce }.drop(1).collect {
                            runCatching {
                                when (sheetCommandTarget) {
                                    Sheet.HIDDEN -> sheetState.hide()
                                    Sheet.COLLAPSED -> sheetState.partialExpand()
                                    Sheet.EXPANDED -> sheetState.expand()
                                }
                            }
                        }
                    }

                    // Android 15 forces edge-to-edge at targetSdk 36, so inset the toolbar below the
                    // status bar (the legacy XML toolbar sat below it).
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        AndroidView(factory = { toolbar }, modifier = Modifier.fillMaxWidth())
                        BottomSheetScaffold(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            scaffoldState = scaffoldState,
                            // The header height the panel reported, plus room for the drag handle
                            // that sits above it.
                            sheetPeekHeight = with(density) { peekPx.toDp() } + DRAG_HANDLE_ALLOWANCE,
                            sheetContent = {
                                AndroidView(
                                    factory = { sheetContent },
                                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                                )
                            }
                        ) {
                            AndroidView(factory = { mapContent }, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /**
         * Extra peek height for the scaffold drag handle that sits above the arrivals content. The
         * handle itself is ~48dp (4dp pill + 22dp vertical padding), but the peek dimens the panel
         * reports (arrival_header_height_*) already budgeted ~20dp for the old in-panel handle that
         * the BottomSheetScaffold handle now replaces, so only the net difference is added here.
         */
        val DRAG_HANDLE_ALLOWANCE = 28.dp
    }
}
