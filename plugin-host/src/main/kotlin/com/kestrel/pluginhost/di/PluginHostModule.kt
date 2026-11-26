// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.di

import com.kestrel.domain.repository.PluginRuntimeLoader
import com.kestrel.pluginhost.CompositePluginRuntimeLoader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PluginHostModule {
    @Binds
    @Singleton
    abstract fun bindPluginRuntimeLoader(impl: CompositePluginRuntimeLoader): PluginRuntimeLoader
}
