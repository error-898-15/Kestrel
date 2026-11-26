// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost

import com.kestrel.domain.provider.SourceProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdapterRegistry @Inject constructor() {
    private val _providers = MutableStateFlow<Map<String, SourceProvider>>(emptyMap())
    val providers: StateFlow<Map<String, SourceProvider>> = _providers.asStateFlow()

    fun register(provider: SourceProvider) {
        _providers.update { it + (provider.providerId to provider) }
    }

    fun unregisterByPlugin(pluginId: String) {
        _providers.update { current ->
            current.filterValues { it.pluginId != pluginId }
        }
    }

    fun get(providerId: String): SourceProvider? = _providers.value[providerId]
}
