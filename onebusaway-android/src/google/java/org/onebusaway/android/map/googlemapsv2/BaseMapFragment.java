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
package org.onebusaway.android.map.googlemapsv2;

import android.app.Activity;
import android.os.Bundle;

import org.onebusaway.android.map.AbstractObaMapHostFragment;
import org.onebusaway.android.map.MapHostDeps;
import org.onebusaway.android.map.ObaMapHost;

/**
 * Google Maps thin {@link androidx.fragment.app.Fragment} wrapper: supplies a {@link GoogleMapHost}
 * to {@link AbstractObaMapHostFragment} (which holds all the delegation/lifecycle plumbing). Used by
 * the non-Home map screens; Home hosts the map directly via {@link ObaMapHost}.
 */
public class BaseMapFragment extends AbstractObaMapHostFragment {

    /** The zoom threshold for showing stops; referenced by the stop tap handler in GoogleMapHost. */
    public static final float CAMERA_DEFAULT_ZOOM = GoogleMapHost.CAMERA_DEFAULT_ZOOM;

    @Override
    protected ObaMapHost createHost(Activity activity, MapHostDeps deps, Bundle args) {
        return new GoogleMapHost(activity, deps, args);
    }
}
