/*
 * Copyright (C) 2014-2026 University of South Florida, Open Transit Software Foundation
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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.Marker;

import org.onebusaway.android.R;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaTrip;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.elements.ObaTripStatusExtensionsKt;
import org.onebusaway.android.io.elements.OccupancyState;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.util.ArrivalInfoUtils;
import org.onebusaway.android.util.UIUtils;

import java.util.concurrent.TimeUnit;

/**
 * Custom info window adapter for vehicle markers on the map.
 * Shows route name, schedule deviation, last-updated time, and occupancy.
 */
class VehicleInfoWindowAdapter implements GoogleMap.InfoWindowAdapter {

    /**
     * Provides the data needed by the info window to render vehicle status.
     */
    interface InfoSource {
        ObaTripStatus getStatusFromMarker(Marker marker);
        boolean isDataReceivedMarker(Marker marker);
        ObaTripsForRouteResponse getLastResponse();
    }

    private final LayoutInflater mInflater;
    private final Context mContext;
    private final InfoSource mSource;

    VehicleInfoWindowAdapter(Context context, InfoSource source) {
        mInflater = LayoutInflater.from(context);
        mContext = context;
        mSource = source;
    }

    @Override
    public View getInfoWindow(Marker marker) {
        return null;
    }

    @Override
    public View getInfoContents(Marker marker) {
        if (mSource.isDataReceivedMarker(marker)) {
            return createDataReceivedInfoView(marker);
        }

        ObaTripStatus status = mSource.getStatusFromMarker(marker);
        if (status == null) return null;

        ObaTripsForRouteResponse response = mSource.getLastResponse();
        if (response == null) return null;

        ObaTrip trip = response.getTrip(status.getActiveTripId());
        if (trip == null) return null;
        ObaRoute route = response.getRoute(trip.getRouteId());
        if (route == null) return null;

        View view = mInflater.inflate(R.layout.vehicle_info_window, null);
        Resources r = mContext.getResources();

        // Google Maps info windows always have a white background regardless of theme,
        // so force dark text to avoid white-on-white in dark mode
        TextView routeView = view.findViewById(R.id.route_and_destination);
        routeView.setTextColor(0xDE000000); // 87% black, Material dark-on-light primary
        TextView statusView = view.findViewById(R.id.status);
        TextView lastUpdatedView = view.findViewById(R.id.last_updated);
        lastUpdatedView.setTextColor(0x8A000000); // 54% black, Material dark-on-light secondary
        ImageView moreView = view.findViewById(R.id.trip_more_info);
        moreView.setColorFilter(r.getColor(R.color.switch_thumb_normal_material_dark));
        ViewGroup occupancyView = view.findViewById(R.id.occupancy);

        routeView.setText(UIUtils.getRouteDisplayName(route) + " " +
                mContext.getString(R.string.trip_info_separator) + " " +
                UIUtils.formatDisplayText(trip.getHeadsign()));

        long now = System.currentTimeMillis();
        boolean isRealtime = ObaTripStatusExtensionsKt.isLocationRealtime(status)
                || ObaTripStatusExtensionsKt.isRealtimeSpeedEstimable(status, now);

        statusView.setBackgroundResource(R.drawable.round_corners_style_b_status);
        GradientDrawable d = (GradientDrawable) statusView.getBackground();
        int pSides = UIUtils.dpToPixels(mContext, 5);
        int pTopBottom = UIUtils.dpToPixels(mContext, 2);

        if (!isRealtime) {
            statusView.setText(r.getString(R.string.stop_info_scheduled));
            d.setColor(r.getColor(R.color.stop_info_scheduled_time));
            statusView.setPadding(pSides, pTopBottom, pSides, pTopBottom);
            lastUpdatedView.setText(r.getString(R.string.vehicle_last_updated_scheduled));
            UIUtils.setOccupancyVisibilityAndColor(occupancyView, null, OccupancyState.HISTORICAL);
            UIUtils.setOccupancyContentDescription(occupancyView, null, OccupancyState.HISTORICAL);
            return view;
        }

        long deviationMin = TimeUnit.SECONDS.toMinutes(status.getScheduleDeviation());
        statusView.setText(ArrivalInfoUtils.computeArrivalLabelFromDelay(r, deviationMin));
        d.setColor(r.getColor(ArrivalInfoUtils.computeColorFromDeviation(deviationMin)));
        statusView.setPadding(pSides, pTopBottom, pSides, pTopBottom);

        long lastUpdateTime = status.getLastLocationUpdateTime() != 0
                ? status.getLastLocationUpdateTime()
                : status.getLastUpdateTime();
        long elapsedSec = TimeUnit.MILLISECONDS.toSeconds(now - lastUpdateTime);
        long elapsedMin = TimeUnit.SECONDS.toMinutes(elapsedSec);
        long secMod60 = elapsedSec % 60;

        String lastUpdated = elapsedSec < 60
                ? r.getString(R.string.vehicle_last_updated_sec, elapsedSec)
                : r.getString(R.string.vehicle_last_updated_min_and_sec, elapsedMin, secMod60);
        lastUpdatedView.setText(lastUpdated);

        if (status.getOccupancyStatus() != null) {
            UIUtils.setOccupancyVisibilityAndColor(occupancyView, status.getOccupancyStatus(), OccupancyState.REALTIME);
            UIUtils.setOccupancyContentDescription(occupancyView, status.getOccupancyStatus(), OccupancyState.REALTIME);
        } else {
            UIUtils.setOccupancyVisibilityAndColor(occupancyView, null, OccupancyState.REALTIME);
            UIUtils.setOccupancyContentDescription(occupancyView, null, OccupancyState.REALTIME);
        }

        return view;
    }

    private View createDataReceivedInfoView(Marker marker) {
        View view = mInflater.inflate(R.layout.vehicle_info_window, null);
        Resources r = mContext.getResources();

        TextView routeView = view.findViewById(R.id.route_and_destination);
        routeView.setTextColor(0xDE000000);
        routeView.setText(marker.getTitle());

        TextView statusView = view.findViewById(R.id.status);
        statusView.setVisibility(View.GONE);

        TextView lastUpdatedView = view.findViewById(R.id.last_updated);
        lastUpdatedView.setTextColor(0x8A000000);
        lastUpdatedView.setText(marker.getSnippet());

        ImageView moreView = view.findViewById(R.id.trip_more_info);
        moreView.setColorFilter(r.getColor(R.color.switch_thumb_normal_material_dark));

        ViewGroup occupancyView = view.findViewById(R.id.occupancy);
        occupancyView.setVisibility(View.GONE);

        return view;
    }
}
