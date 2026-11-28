// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kestrel.domain.model.MangaReaderSettings
import com.kestrel.domain.model.PlayerOrientationMode
import com.kestrel.domain.model.ServerSearchSettings
import com.kestrel.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.kestrelDataStore: DataStore<Preferences> by preferencesDataStore(name = "kestrel_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) : SettingsRepository {

    private object Keys {
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val DEFAULT_SPEED = floatPreferencesKey("default_playback_speed")
        val PLAYER_ORIENTATION_MODE = stringPreferencesKey("player_orientation_mode")
        val SERVER_SEARCH_SETTINGS = stringPreferencesKey("server_search_settings_json")
        val SEEDED_DEFAULT_REPO_URLS = stringSetPreferencesKey("seeded_default_repo_urls")
        val SEEDED_MANGA_REPO_URLS = stringSetPreferencesKey("seeded_manga_repo_urls")
        val MANGA_READER_SETTINGS = stringPreferencesKey("manga_reader_settings_json")
    }

    val disclaimerAccepted: Flow<Boolean> = context.kestrelDataStore.data.map { prefs ->
        prefs[Keys.DISCLAIMER_ACCEPTED] ?: false
    }

    override val defaultPlaybackSpeed: Flow<Float> = context.kestrelDataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_SPEED] ?: 1.0f
    }

    override val playerOrientationMode: Flow<PlayerOrientationMode> = context.kestrelDataStore.data.map { prefs ->
        val raw = prefs[Keys.PLAYER_ORIENTATION_MODE]
        if (raw.isNullOrBlank()) {
            PlayerOrientationMode.LANDSCAPE
        } else {
            runCatching { PlayerOrientationMode.valueOf(raw) }
                .getOrDefault(PlayerOrientationMode.LANDSCAPE)
        }
    }

    override val serverSearchSettings: Flow<ServerSearchSettings> = context.kestrelDataStore.data.map { prefs ->
        val raw = prefs[Keys.SERVER_SEARCH_SETTINGS]
        if (raw.isNullOrBlank()) {
            ServerSearchSettings()
        } else {
            runCatching {
                json.decodeFromString(ServerSearchSettings.serializer(), raw)
            }.getOrDefault(ServerSearchSettings())
        }
    }

    suspend fun getSeededDefaultRepoUrls(): Set<String> =
        context.kestrelDataStore.data.first()[Keys.SEEDED_DEFAULT_REPO_URLS].orEmpty()

    suspend fun markDefaultRepoSeeded(url: String) {
        context.kestrelDataStore.edit { prefs ->
            val current = prefs[Keys.SEEDED_DEFAULT_REPO_URLS].orEmpty()
            prefs[Keys.SEEDED_DEFAULT_REPO_URLS] = current + url
        }
    }

    suspend fun clearSeededDefaultRepos() {
        context.kestrelDataStore.edit { prefs ->
            prefs.remove(Keys.SEEDED_DEFAULT_REPO_URLS)
        }
    }

    val mangaReaderSettings: Flow<MangaReaderSettings> =
        context.kestrelDataStore.data.map { prefs ->
            val raw = prefs[Keys.MANGA_READER_SETTINGS]
            if (raw.isNullOrBlank()) {
                MangaReaderSettings()
            } else {
                runCatching {
                    json.decodeFromString(MangaReaderSettings.serializer(), raw)
                }.getOrDefault(MangaReaderSettings())
            }
        }

    suspend fun updateMangaReaderSettings(
        transform: (MangaReaderSettings) -> MangaReaderSettings
    ) {
        context.kestrelDataStore.edit { prefs ->
            val current = prefs[Keys.MANGA_READER_SETTINGS]
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching {
                        json.decodeFromString(MangaReaderSettings.serializer(), raw)
                    }.getOrNull()
                }
                ?: MangaReaderSettings()
            prefs[Keys.MANGA_READER_SETTINGS] = json.encodeToString(
                MangaReaderSettings.serializer(),
                transform(current)
            )
        }
    }

    suspend fun getSeededMangaRepoUrls(): Set<String> =
        context.kestrelDataStore.data.first()[Keys.SEEDED_MANGA_REPO_URLS].orEmpty()

    suspend fun markMangaRepoSeeded(url: String) {
        context.kestrelDataStore.edit { prefs ->
            val current = prefs[Keys.SEEDED_MANGA_REPO_URLS].orEmpty()
            prefs[Keys.SEEDED_MANGA_REPO_URLS] = current + url
        }
    }

    suspend fun clearSeededMangaRepos() {
        context.kestrelDataStore.edit { prefs ->
            prefs.remove(Keys.SEEDED_MANGA_REPO_URLS)
        }
    }

    suspend fun acceptDisclaimer() {
        context.kestrelDataStore.edit { prefs ->
            prefs[Keys.DISCLAIMER_ACCEPTED] = true
        }
    }

    override suspend fun resetDisclaimer() {
        context.kestrelDataStore.edit { prefs ->
            prefs[Keys.DISCLAIMER_ACCEPTED] = false
        }
    }

    override suspend fun setDefaultPlaybackSpeed(speed: Float) {
        context.kestrelDataStore.edit { prefs ->
            prefs[Keys.DEFAULT_SPEED] = speed.coerceIn(0.5f, 2.0f)
        }
    }

    override suspend fun setPlayerOrientationMode(mode: PlayerOrientationMode) {
        context.kestrelDataStore.edit { prefs ->
            prefs[Keys.PLAYER_ORIENTATION_MODE] = mode.name
        }
    }

    override suspend fun updateServerSearchSettings(
        transform: (ServerSearchSettings) -> ServerSearchSettings
    ) {
        context.kestrelDataStore.edit { prefs ->
            val current = prefs[Keys.SERVER_SEARCH_SETTINGS]
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching { json.decodeFromString(ServerSearchSettings.serializer(), raw) }.getOrNull()
                }
                ?: ServerSearchSettings()
            val updated = transform(current)
            prefs[Keys.SERVER_SEARCH_SETTINGS] = json.encodeToString(ServerSearchSettings.serializer(), updated)
        }
    }
}
