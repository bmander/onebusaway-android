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
package org.onebusaway.android.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.onebusaway.android.map.DefaultRouteMapRepository
import org.onebusaway.android.map.DefaultStopsRepository
import org.onebusaway.android.map.RouteMapRepository
import org.onebusaway.android.map.StopsRepository
import org.onebusaway.android.map.bike.BikeStationsRepository
import org.onebusaway.android.map.bike.DefaultBikeStationsRepository
import org.onebusaway.android.ui.agencies.AgenciesRepository
import org.onebusaway.android.ui.agencies.DefaultAgenciesRepository
import org.onebusaway.android.ui.arrivals.ArrivalsRepository
import org.onebusaway.android.ui.arrivals.DefaultArrivalsRepository
import org.onebusaway.android.ui.home.DefaultNavItemsRepository
import org.onebusaway.android.ui.home.DefaultStartupPreferencesRepository
import org.onebusaway.android.ui.home.DefaultWeatherRepository
import org.onebusaway.android.ui.home.DefaultWideAlertsRepository
import org.onebusaway.android.ui.home.NavItemsRepository
import org.onebusaway.android.ui.home.StartupPreferencesRepository
import org.onebusaway.android.ui.home.WeatherRepository
import org.onebusaway.android.ui.home.WideAlertsRepository
import org.onebusaway.android.location.DefaultLocationRepository
import org.onebusaway.android.location.LocationRepository
import org.onebusaway.android.region.DefaultRegionRepository
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.ui.regions.DefaultRegionsRepository
import org.onebusaway.android.ui.regions.RegionsRepository
import org.onebusaway.android.ui.report.customerservice.CustomerServiceRepository
import org.onebusaway.android.ui.report.customerservice.DefaultCustomerServiceRepository
import org.onebusaway.android.ui.report.types.DefaultReportTypeRepository
import org.onebusaway.android.ui.report.types.ReportTypeRepository
import org.onebusaway.android.ui.routeinfo.DefaultRouteInfoRepository
import org.onebusaway.android.ui.routeinfo.RouteInfoRepository
import org.onebusaway.android.ui.searchresults.DefaultSearchResultsRepository
import org.onebusaway.android.ui.searchresults.SearchResultsRepository
import org.onebusaway.android.ui.tripdetails.DefaultTripDetailsRepository
import org.onebusaway.android.ui.tripdetails.TripDetailsRepository
import org.onebusaway.android.ui.tripinfo.DefaultTripInfoRepository
import org.onebusaway.android.ui.tripinfo.TripInfoRepository
import org.onebusaway.android.ui.tripplan.AdvancedSettingsRepository
import org.onebusaway.android.ui.tripplan.DefaultAdvancedSettingsRepository
import org.onebusaway.android.ui.tripplan.DefaultGeocodeRepository
import org.onebusaway.android.ui.tripplan.DefaultTripPlanRepository
import org.onebusaway.android.preferences.DefaultPreferencesRepository
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.ui.tripplan.GeocodeRepository
import org.onebusaway.android.ui.tripplan.TripPlanRepository
import org.onebusaway.android.ui.tripresults.DefaultTripResultsRepository
import org.onebusaway.android.ui.tripresults.TripResultsRepository

/**
 * Binds the DI-only repositories (interface -> Default impl). These are stateless per-call fetchers, so
 * they're unscoped — a fresh instance per injection. The Default impls take only `@ApplicationContext`
 * (or nothing), so Hilt constructs them directly. Repos with runtime-arg constructors (e.g. Open311) are
 * not here — they keep their factories until Campaign C.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindRegionsRepository(impl: DefaultRegionsRepository): RegionsRepository

    @Binds
    abstract fun bindAgenciesRepository(impl: DefaultAgenciesRepository): AgenciesRepository

    @Binds
    abstract fun bindSearchResultsRepository(impl: DefaultSearchResultsRepository): SearchResultsRepository

    @Binds
    abstract fun bindCustomerServiceRepository(
        impl: DefaultCustomerServiceRepository
    ): CustomerServiceRepository

    @Binds
    abstract fun bindReportTypeRepository(impl: DefaultReportTypeRepository): ReportTypeRepository

    @Binds
    abstract fun bindTripResultsRepository(impl: DefaultTripResultsRepository): TripResultsRepository

    @Binds
    abstract fun bindTripDetailsRepository(impl: DefaultTripDetailsRepository): TripDetailsRepository

    @Binds
    abstract fun bindRouteInfoRepository(impl: DefaultRouteInfoRepository): RouteInfoRepository

    @Binds
    abstract fun bindTripInfoRepository(impl: DefaultTripInfoRepository): TripInfoRepository

    @Binds
    abstract fun bindGeocodeRepository(impl: DefaultGeocodeRepository): GeocodeRepository

    @Binds
    abstract fun bindTripPlanRepository(impl: DefaultTripPlanRepository): TripPlanRepository

    @Binds
    abstract fun bindAdvancedSettingsRepository(
        impl: DefaultAdvancedSettingsRepository
    ): AdvancedSettingsRepository

    @Binds
    abstract fun bindPreferencesRepository(impl: DefaultPreferencesRepository): PreferencesRepository

    @Binds
    abstract fun bindWeatherRepository(impl: DefaultWeatherRepository): WeatherRepository

    @Binds
    abstract fun bindStopsRepository(impl: DefaultStopsRepository): StopsRepository

    @Binds
    abstract fun bindRouteMapRepository(impl: DefaultRouteMapRepository): RouteMapRepository

    @Binds
    abstract fun bindBikeStationsRepository(impl: DefaultBikeStationsRepository): BikeStationsRepository

    // HomeViewModel's collaborators (so it can become a plain @HiltViewModel — D6).
    @Binds
    abstract fun bindWideAlertsRepository(impl: DefaultWideAlertsRepository): WideAlertsRepository

    @Binds
    abstract fun bindNavItemsRepository(impl: DefaultNavItemsRepository): NavItemsRepository

    @Binds
    abstract fun bindStartupPreferencesRepository(
        impl: DefaultStartupPreferencesRepository
    ): StartupPreferencesRepository

    // Campaign A (A2): the region repository is a real Hilt @Singleton — the one process-wide instance
    // that owns region state + resolution + the canonical region write (A7). (Was sourced from
    // Application via an AppModule bridge.)
    @Binds
    @Singleton
    abstract fun bindRegionRepository(impl: DefaultRegionRepository): RegionRepository

    // Campaign A (B1): the location repository is a real Hilt @Singleton — the one process-wide instance
    // that owns last-known-location state + provider polling. (Was sourced from Application via a bridge.)
    @Binds
    @Singleton
    abstract fun bindLocationRepository(impl: DefaultLocationRepository): LocationRepository

    // Arrivals: unscoped on purpose — DefaultArrivalsRepository is stateful (lastGood) and 1:1 with its
    // (assisted) ArrivalsViewModel, so each VM gets its own. Do NOT make this @Singleton.
    @Binds
    abstract fun bindArrivalsRepository(impl: DefaultArrivalsRepository): ArrivalsRepository
}
