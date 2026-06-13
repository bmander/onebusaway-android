/*
 * Copyright (C) 2017 Rodrigo Carvalho (carvalhorr@gmail.com),
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
package org.onebusaway.android.map;

/**
 * Implemented by the map fragment to add/remove a map layer (currently only bikeshare). Previously
 * nested in the android-fab {@code LayersSpeedDialAdapter}; extracted here when the layers
 * speed-dial was replaced by the Compose {@code MapChrome} layers FAB.
 */
public interface LayerActivationListener {

    void onActivateLayer(LayerInfo layer);

    void onDeactivateLayer(LayerInfo layer);
}
