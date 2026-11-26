// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.kestrelDataStore: DataStore<Preferences> by preferencesDataStore(name = "kestrel_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
    }

    val disclaimerAccepted: Flow<Boolean> = context.kestrelDataStore.data.map { prefs ->
        prefs[Keys.DISCLAIMER_ACCEPTED] ?: false
    }

    suspend fun acceptDisclaimer() {
        context.kestrelDataStore.edit { prefs ->
            prefs[Keys.DISCLAIMER_ACCEPTED] = true
        }
    }
}
