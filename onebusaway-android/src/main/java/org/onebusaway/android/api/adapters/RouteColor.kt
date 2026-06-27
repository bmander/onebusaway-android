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
package org.onebusaway.android.api.adapters

import org.onebusaway.android.api.contract.RouteReference

import org.onebusaway.android.util.parseObaHexColor

/**
 * Reads [RouteReference.color] / [RouteReference.textColor] as Android ARGB ints (or null when
 * absent/malformed) via the shared [parseObaHexColor] parser, so color consumers don't re-implement
 * `Color.parseColor`.
 */
fun RouteReference.colorArgb(): Int? = parseObaHexColor(color)

/** Parses [RouteReference.textColor] to an Android ARGB int, or null when absent/invalid. */
fun RouteReference.textColorArgb(): Int? = parseObaHexColor(textColor)
