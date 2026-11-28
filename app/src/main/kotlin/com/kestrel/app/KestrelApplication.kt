// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.app

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import com.kestrel.core.designsystem.KestrelImageLoading
import com.kestrel.domain.repository.ExtensionRepository
import com.kestrel.domain.repository.MangaRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import uy.kohesive.injekt.InjektRegistry
import javax.inject.Inject

@HiltAndroidApp
class KestrelApplication : HikariApp() {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var extensionRepository: ExtensionRepository

    @Inject
    lateinit var mangaRepository: MangaRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        HikariApp.instance = this
        Http.init(this, okHttpClient)
        InjektRegistry.ensureInitialized(this, okHttpClient)
        KestrelImageLoading.install(this, okHttpClient)
        appScope.launch {
            extensionRepository.ensureDefaultRepositoriesSeeded()
        }
        appScope.launch {
            mangaRepository.ensureDefaultMangaRepositoriesSeeded()
            mangaRepository.ensureInstalledExtensionsLoaded()
        }
    }
}
