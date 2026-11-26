// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost

import com.kestrel.domain.model.PluginHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps every third-party plugin invocation in strict timeouts, transient retries,
 * and Throwable isolation so a broken or incompatible plugin never crashes Kestrel.
 */
@Singleton
class PluginSandbox @Inject constructor() {

    private val _healthByPlugin = MutableStateFlow<Map<String, PluginHealth>>(emptyMap())
    val healthByPlugin: StateFlow<Map<String, PluginHealth>> = _healthByPlugin.asStateFlow()

    suspend fun <T> execute(
        pluginId: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxRetries: Int = 1,
        block: suspend () -> T
    ): Result<T> {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= maxRetries) {
            try {
                val value = withTimeout(timeoutMs) { block() }
                markHealthy(pluginId)
                return Result.success(value)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                val retryable = t is IOException && attempt < maxRetries
                if (!retryable) break
                attempt++
                delay(250L * attempt)
            }
        }
        val error = lastError ?: IllegalStateException("Unknown plugin error")
        markError(pluginId, error.message ?: error::class.java.simpleName)
        return Result.failure(error)
    }

    fun markHealthy(pluginId: String) {
        _healthByPlugin.update { current ->
            current + (pluginId to PluginHealth(
                pluginId = pluginId,
                isHealthy = true,
                message = null,
                updatedAtEpochMs = System.currentTimeMillis()
            ))
        }
    }

    fun markError(pluginId: String, message: String) {
        _healthByPlugin.update { current ->
            current + (pluginId to PluginHealth(
                pluginId = pluginId,
                isHealthy = false,
                message = message,
                updatedAtEpochMs = System.currentTimeMillis()
            ))
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val SUPPORTED_CLOUDSTREAM_API_VERSION = 1
    }
}
