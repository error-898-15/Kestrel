// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.app

import android.app.Application
import com.kestrel.core.designsystem.KestrelImageLoading
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class KestrelApplication : Application() {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        KestrelImageLoading.install(this, okHttpClient)
    }
}
