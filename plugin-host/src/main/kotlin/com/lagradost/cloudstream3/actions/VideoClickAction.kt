// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.actions

import java.util.concurrent.CopyOnWriteArrayList

object VideoClickActionHolder {
    val allVideoClickActions: MutableList<VideoClickAction> = CopyOnWriteArrayList()
}

/**
 * Minimal stub for VideoClickAction referenced by Plugin.registerVideoClickAction.
 */
abstract class VideoClickAction {
    open val name: String = ""
    var sourcePlugin: String? = null
}
