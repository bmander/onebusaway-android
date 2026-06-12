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
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.onebusaway.android.R
import org.onebusaway.android.io.request.ObaArrivalInfoResponse
import org.onebusaway.android.ui.arrivals.ArrivalsPanel
import org.onebusaway.android.ui.arrivals.ArrivalsUiState
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.ui.arrivals.DefaultArrivalsRepository
import org.onebusaway.android.ui.createArrivalActionHandler

/**
 * Hosts the [ArrivalsPanel] for the currently focused stop directly in the home bottom sheet,
 * replacing the old `ArrivalsPanelFragment`. Each focused stop gets its own [ArrivalsViewModel] in a
 * per-stop [ViewModelStore] that is **cleared when the stop changes or the sheet leaves composition**
 * (via [rememberClearedViewModelStoreOwner]) — so the VM's `viewModelScope`, and the refresh loop
 * `ArrivalsPanel` drives through it, are cancelled rather than accumulating in the activity's store.
 *
 * Polling lifecycle is owned by `ArrivalsPanel` itself (`ArrivalsPolling` → `repeatOnLifecycle`), so
 * it also pauses with the screen. Loaded responses are forwarded to the host via [onArrivalsLoaded]
 * (the map recenter / focus marker / tutorials), the chevron toggles the sheet via [onToggleSheet],
 * and the preview size drives the peek height via [onPreferredHeight].
 */
@Composable
internal fun ArrivalsSheetHost(
    focusedStop: FocusedStop?,
    collapsed: Boolean,
    onArrivalsLoaded: (ObaArrivalInfoResponse) -> Unit,
    onShowRouteOnMap: (String) -> Unit,
    onToggleSheet: () -> Unit,
    onPreferredHeight: (previewCount: Int, filtering: Boolean) -> Unit,
) {
    val stop = focusedStop ?: return
    key(stop.id) {
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides rememberClearedViewModelStoreOwner(stop.id)
        ) {
            val context = LocalContext.current
            val viewModel: ArrivalsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ArrivalsViewModel(stop.id, DefaultArrivalsRepository(context.applicationContext))
                    }
                }
            )
            val activity = context.findActivity()
            val handler = remember(viewModel) {
                createArrivalActionHandler(
                    activity = activity,
                    viewModel = viewModel,
                    currentContent = { viewModel.state.value as? ArrivalsUiState.Content },
                    onShowRouteOnMap = onShowRouteOnMap,
                )
            }
            val listState = remember(stop.id) { LazyListState() }

            // The BottomSheetScaffold sheet container is transparent; keep the legacy panel background.
            Surface(color = colorResource(R.color.trip_details_background)) {
                ArrivalsPanel(
                    viewModel = viewModel,
                    listState = listState,
                    collapsed = collapsed,
                    initialTitle = stop.name.orEmpty(),
                    handler = handler,
                    onToggleExpand = onToggleSheet,
                    onPreferredHeight = onPreferredHeight,
                )
            }

            // Forward each completed load to the host (replaces the fragment's onViewCreated collector).
            LaunchedEffect(viewModel) {
                viewModel.responses.collect(onArrivalsLoaded)
            }
        }
    }
}

/**
 * A [ViewModelStoreOwner] backed by a fresh [ViewModelStore] per [key], cleared when the key changes
 * or the composition leaves — so ViewModels scoped to it are properly destroyed (their
 * `viewModelScope` cancelled) instead of living on in the activity's store.
 */
@Composable
internal fun rememberClearedViewModelStoreOwner(key: Any?): ViewModelStoreOwner {
    val owner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(key) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}

/** Unwraps the Activity from a (possibly themed) Compose context chain. */
private tailrec fun Context.findActivity(): AppCompatActivity = when (this) {
    is AppCompatActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No AppCompatActivity found in the context chain")
}
