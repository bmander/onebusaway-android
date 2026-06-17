/*
 * Copyright (C) 2011 Paul Watts (paulcwatts@gmail.com)
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
package org.onebusaway.android.ui.mylists;

import org.onebusaway.android.app.di.RegionEntryPoint;
import org.onebusaway.android.io.ObaApi;
import org.onebusaway.android.io.elements.ObaRegion;
import org.onebusaway.android.io.request.ObaRouteRequest;
import org.onebusaway.android.io.request.ObaRouteResponse;
import org.onebusaway.android.provider.ObaContract;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.loader.app.LoaderManager;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;

/**
 * Region-scoped query helper plus route favoriting and route-info loading.
 *
 * @author paulw
 */
public final class QueryUtils {

    public static String getRegionWhere(String regionFieldName, long regionId) {
        return "(" + regionFieldName + "=" + regionId +
                " OR " + regionFieldName + " IS NULL)";
    }

    /**
     * Sets the given route and headsign and stop as a favorite, including checking to make sure that the
     * route has already been added to the local provider.  If this route/headsign should be marked
     * as a favorite for all stops, stopId should be null.
     *
     * @param routeUri Uri for the route to be added
     * @param headsign the headsign to be marked as favorite, along with the routeUri
     * @param stopId the stopId to be marked as a favorite, along with with route and headsign.  If
     *               this route/headsign should be marked for all stops, then stopId should be null
     * @param routeValues   content routeValues to be set for the route details (see ObaContract.RouteColumns)
     *                 (may be null)
     * @param favorite true if this route/headsign should be marked as a favorite, false if it
     *                 should not
     */
    public static void setFavoriteRouteAndHeadsign(Context context, Uri routeUri,
            String headsign, String stopId, ContentValues routeValues, boolean favorite) {
        if (routeValues == null) {
            routeValues = new ContentValues();
        }
        ObaRegion currentRegion = RegionEntryPoint.get(context).getRegion().getValue();
        if (currentRegion != null) {
            routeValues.put(ObaContract.Routes.REGION_ID,
                    currentRegion.getId());
        }

        String routeId = routeUri.getLastPathSegment();

        // Make sure this route has been inserted into the routes table
        ObaContract.Routes.insertOrUpdate(context, routeId, routeValues, true);
        // Mark the combination of route and headsign as a favorite or not favorite
        ObaContract.RouteHeadsignFavorites
                .markAsFavorite(context, routeId, headsign, stopId, favorite);
    }

    final static class RouteInfoLoader extends AsyncTaskLoader<ObaRouteResponse> {

        private final String mRouteId;

        RouteInfoLoader(Context context, String routeId) {
            super(context);
            mRouteId = routeId;
        }

        @Override
        public void onStartLoading() {
            forceLoad();
        }

        @Override
        public ObaRouteResponse loadInBackground() {
            return ObaRouteRequest.newRequest(getContext(), mRouteId).call();
        }
    }

    public final static class RouteLoaderCallback
            implements LoaderManager.LoaderCallbacks<ObaRouteResponse> {

        private String mRouteId;
        private Context mContext;

        public RouteLoaderCallback(Context context, String routeId) {
            super();
            mRouteId = routeId;
            mContext = context;
        }

        @Override
        public Loader<ObaRouteResponse> onCreateLoader(int id, Bundle args) {
            return new QueryUtils.RouteInfoLoader(mContext, mRouteId);
        }

        @Override
        public void onLoadFinished(Loader<ObaRouteResponse> loader,
                                   ObaRouteResponse data) {
            recordRouteInfo(data);
        }

        @Override
        public void onLoaderReset(Loader<ObaRouteResponse> loader) {
            // Nothing to do right here...
        }

        private void recordRouteInfo(ObaRouteResponse routeInfo) {
            if (routeInfo.getCode() == ObaApi.OBA_OK) {
                String url = routeInfo.getUrl();

                String shortName = routeInfo.getShortName();
                String longName = routeInfo.getLongName();

                if (TextUtils.isEmpty(shortName)) {
                    shortName = longName;
                }
                if (TextUtils.isEmpty(longName) || shortName.equals(longName)) {
                    longName = routeInfo.getDescription();
                }

                ContentValues values = new ContentValues();
                values.put(ObaContract.Routes.SHORTNAME, shortName);
                values.put(ObaContract.Routes.LONGNAME, longName);
                values.put(ObaContract.Routes.URL, url);
                ObaRegion currentRegion =
                        RegionEntryPoint.get(mContext).getRegion().getValue();
                if (currentRegion != null) {
                    values.put(ObaContract.Routes.REGION_ID,
                            currentRegion.getId());
                }
                ObaContract.Routes.insertOrUpdate(mContext, routeInfo.getId(), values,
                        true);
            }
        }
    }
}
