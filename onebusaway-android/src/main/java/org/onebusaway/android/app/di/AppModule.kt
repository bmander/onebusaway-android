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

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.onebusaway.android.app.Application
import org.onebusaway.android.donations.DonationsManager
import org.onebusaway.android.region.RegionRepository

/**
 * Provides the process-wide singletons that today live on [Application] (the de-facto DI container), so
 * Hilt-managed code can inject them instead of reaching `Application.getX()` statically. During the
 * transition they're sourced from the existing [Application] instances — Application stays the single
 * owner/writer (notably [RegionRepository], whose single write chokepoint Campaign A established, so this
 * must return Application's instance, never a new one). More singletons get added here as converted
 * consumers need them.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideRegionRepository(): RegionRepository = Application.getRegionRepository()

    @Provides
    @Singleton
    fun provideDonationsManager(): DonationsManager = Application.getDonationsManager()
}
