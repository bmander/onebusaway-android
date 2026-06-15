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
import org.onebusaway.android.map.DefaultRouteMapRepository
import org.onebusaway.android.map.DefaultStopsRepository
import org.onebusaway.android.map.RouteMapRepository
import org.onebusaway.android.map.StopsRepository
import org.onebusaway.android.map.bike.BikeStationsRepository
import org.onebusaway.android.map.bike.DefaultBikeStationsRepository
import org.onebusaway.android.ui.agencies.AgenciesRepository
import org.onebusaway.android.ui.agencies.DefaultAgenciesRepository
import org.onebusaway.android.ui.home.DefaultWeatherRepository
import org.onebusaway.android.ui.home.WeatherRepository
import org.onebusaway.android.ui.regions.DefaultRegionsRepository
import org.onebusaway.android.ui.regions.RegionsRepository
import org.onebusaway.android.ui.report.customerservice.CustomerServiceRepository
import org.onebusaway.android.ui.report.customerservice.DefaultCustomerServiceRepository
import org.onebusaway.android.ui.report.types.DefaultReportTypeRepository
import org.onebusaway.android.ui.report.types.ReportTypeRepository
import org.onebusaway.android.ui.searchresults.DefaultSearchResultsRepository
import org.onebusaway.android.ui.searchresults.SearchResultsRepository
import org.onebusaway.android.ui.tripdetails.DefaultTripDetailsRepository
import org.onebusaway.android.ui.tripdetails.TripDetailsRepository
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
    abstract fun bindWeatherRepository(impl: DefaultWeatherRepository): WeatherRepository

    @Binds
    abstract fun bindStopsRepository(impl: DefaultStopsRepository): StopsRepository

    @Binds
    abstract fun bindRouteMapRepository(impl: DefaultRouteMapRepository): RouteMapRepository

    @Binds
    abstract fun bindBikeStationsRepository(impl: DefaultBikeStationsRepository): BikeStationsRepository
}
