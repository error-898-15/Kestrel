// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.di

import android.content.Context
import androidx.room.Room
import com.kestrel.data.local.KestrelDatabase
import com.kestrel.data.local.LibraryDao
import com.kestrel.data.local.PluginDao
import com.kestrel.data.local.RepoDao
import com.kestrel.data.preferences.AppPreferences
import com.kestrel.data.repository.CatalogRepositoryImpl
import com.kestrel.data.repository.ExtensionRepositoryImpl
import com.kestrel.data.repository.LibraryRepositoryImpl
import com.kestrel.domain.repository.CatalogRepository
import com.kestrel.domain.repository.ExtensionRepository
import com.kestrel.domain.repository.LibraryRepository
import com.kestrel.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindModule {
    @Binds
    @Singleton
    abstract fun bindExtensionRepository(impl: ExtensionRepositoryImpl): ExtensionRepository

    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: AppPreferences): SettingsRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvideModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KestrelDatabase =
        Room.databaseBuilder(context, KestrelDatabase::class.java, "kestrel.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideRepoDao(db: KestrelDatabase): RepoDao = db.repoDao()

    @Provides
    fun providePluginDao(db: KestrelDatabase): PluginDao = db.pluginDao()

    @Provides
    fun provideLibraryDao(db: KestrelDatabase): LibraryDao = db.libraryDao()
}
