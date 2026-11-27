// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.repository

import com.kestrel.domain.model.ServerSearchSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val serverSearchSettings: Flow<ServerSearchSettings>
    val defaultPlaybackSpeed: Flow<Float>

    suspend fun updateServerSearchSettings(transform: (ServerSearchSettings) -> ServerSearchSettings)
    suspend fun setDefaultPlaybackSpeed(speed: Float)
    suspend fun resetDisclaimer()
}
