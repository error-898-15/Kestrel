// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.actions

/**
 * Minimal stub for VideoClickAction referenced by Plugin.registerVideoClickAction.
 */
abstract class VideoClickAction {
    open val name: String = ""
    var sourcePlugin: String? = null
}
