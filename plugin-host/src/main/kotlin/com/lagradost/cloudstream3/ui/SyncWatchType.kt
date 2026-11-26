// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.ui

enum class SyncWatchType(val internalId: Int) {
    WATCHING(0),
    COMPLETED(1),
    ONHOLD(2),
    DROPPED(3),
    PLANTOWATCH(4),
    REWATCHING(5),
    NONE(-1)
}
