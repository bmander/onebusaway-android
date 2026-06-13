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
package org.onebusaway.android.ui.compose

import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * A [ViewModelStoreOwner] backed by a fresh [ViewModelStore] per [key], cleared when the key changes
 * or the composition leaves — so ViewModels scoped to it (via `LocalViewModelStoreOwner`) are
 * properly destroyed (their `viewModelScope` cancelled) instead of living on in the host
 * activity's store. Use it to host a short-lived, identity-keyed ViewModel from Compose.
 */
@Composable
fun rememberClearedViewModelStoreOwner(key: Any?): ViewModelStoreOwner {
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

/**
 * Unwraps the [AppCompatActivity] from a (possibly themed) Compose `LocalContext` chain — the
 * canonical bridge for a composable that needs its hosting activity on `activity-compose` versions
 * before `LocalActivity` (1.10.0, which would bump minSdk to 23).
 */
tailrec fun Context.findActivity(): AppCompatActivity = when (this) {
    is AppCompatActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No AppCompatActivity found in the context chain")
}
