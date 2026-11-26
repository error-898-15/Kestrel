// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder

/**
 * Compatibility shim matching CloudStream's Android Plugin base class
 * expected by compiled .cs3 extensions at runtime.
 */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    var resources: Resources? = null

    var openSettings: ((context: Context) -> Unit)? = null

    fun registerVideoClickAction(element: VideoClickAction) {
        Log.i("KestrelCS3", "Registering VideoClickAction: ${element.name}")
        element.sourcePlugin = this.filename
        VideoClickActionHolder.allVideoClickActions.add(element)
    }
}
