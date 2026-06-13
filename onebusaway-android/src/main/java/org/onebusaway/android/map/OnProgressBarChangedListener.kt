/*
 * Copyright (C) 2011-2014 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com), and individual contributors.
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

/**
 * Notified when the map starts or finishes loading information, so the owner can show/hide a
 * progress bar. Lifted out of [ObaMapFragment] so the fragment-less host can use it too.
 */
fun interface OnProgressBarChangedListener {

    /**
     * @param showProgressBar true if the map is loading information and the progress bar should be
     *                        shown, false if the map has finished loading and the bar should hide.
     */
    fun onProgressBarChanged(showProgressBar: Boolean)
}
