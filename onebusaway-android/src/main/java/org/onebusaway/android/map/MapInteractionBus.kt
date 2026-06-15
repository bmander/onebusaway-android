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
package org.onebusaway.android.map

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Home↔Map coordination seam — the reactive replacement for `HomeViewModel` holding a
 * `MapViewModel` (the last VM-on-VM dependency). The Home→Map coupling is purely outbound: Home issues
 * a few interactions and reads no map state back, so this is a minimal **command bus**, not a shared
 * domain-state holder. [HomeViewModel] writes; [MapViewModel] collects in its `init`.
 *
 * Scoped `@ActivityRetainedScoped`, so the two activity-scoped view models in `HomeActivity` share one
 * instance (surviving configuration change). A host with only a map and no Home (e.g. the trip-results
 * directions map) gets its own idle instance — nothing writes to it there, so the map's collector is a
 * no-op. Deliberately holds **no** `focusedStop`/`mapMode`/persistence: those keep their single owners
 * (`HomeViewModel`'s `SavedStateHandle`, `MapViewModel`, `HomeActivity.onSaveInstanceState`).
 */
interface MapInteractionBus {

    /** The map's bottom inset (driven by the arrivals sheet); idempotent shared state, last-wins. */
    val bottomPadding: StateFlow<Int>

    /** One-shot map interactions that cannot be modeled as state (camera animate / mode change). */
    val commands: SharedFlow<MapCommand>

    fun setBottomPadding(px: Int)

    fun send(command: MapCommand)
}

/** The one-shot Home→Map interactions (each fires exactly when Home decides — never on re-collect). */
sealed interface MapCommand {
    /** Animate the camera to recenter on the currently focused stop (sheet expanded). */
    data class RecenterOnFocusedStop(val lat: Double, val lon: Double) : MapCommand

    /** Enter route mode for the given route (the "show vehicles on map" action). */
    data class ShowRoute(val routeId: String) : MapCommand

    /** Clear the map's render focus (back-press from a peeking arrivals sheet). */
    object ClearFocus : MapCommand
}

/**
 * Default implementation: a private [MutableStateFlow]/[MutableSharedFlow] pair with single writers
 * (mirrors `RegionRepository`). The command flow keeps a small replay-less buffer + `tryEmit` so a
 * command issued before the map's collector is active (rare) isn't dropped.
 */
class DefaultMapInteractionBus @Inject constructor() : MapInteractionBus {

    private val _bottomPadding = MutableStateFlow(0)
    override val bottomPadding: StateFlow<Int> = _bottomPadding.asStateFlow()

    private val _commands = MutableSharedFlow<MapCommand>(extraBufferCapacity = 8)
    override val commands: SharedFlow<MapCommand> = _commands.asSharedFlow()

    override fun setBottomPadding(px: Int) {
        _bottomPadding.value = px
    }

    override fun send(command: MapCommand) {
        _commands.tryEmit(command)
    }
}

@Module
@InstallIn(ActivityRetainedComponent::class)
abstract class MapInteractionModule {

    @Binds
    @ActivityRetainedScoped
    abstract fun bindMapInteractionBus(impl: DefaultMapInteractionBus): MapInteractionBus
}
