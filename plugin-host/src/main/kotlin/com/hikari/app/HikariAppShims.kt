// SPDX-License-Identifier: GPL-3.0-or-later
package com.hikari.app

import android.app.Application
import androidx.appcompat.app.AppCompatActivity

open class MainActivity : AppCompatActivity()

open class HikariApp : Application() {
    companion object {
        lateinit var instance: HikariApp

        @Volatile
        var mainActivity: MainActivity? = null
    }
}
