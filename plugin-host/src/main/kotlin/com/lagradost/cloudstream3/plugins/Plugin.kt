// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream3.actions.VideoClickAction

/**
 * Thin compatibility shim matching CloudStream's Android Plugin base class
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
        // VideoClickAction hooks are host-optional in Kestrel.
    }
}
