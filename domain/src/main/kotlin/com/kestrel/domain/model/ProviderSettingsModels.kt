// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.model

enum class ProviderSettingType {
    HEADER,
    TEXT,
    PASSWORD,
    NUMBER,
    TOGGLE,
    SELECT
}

data class ProviderSettingOption(
    val label: String,
    val value: String
)

data class ProviderSettingField(
    val key: String,
    val type: ProviderSettingType,
    val label: String,
    val description: String = "",
    val defaultValue: String = "",
    val currentValue: String = "",
    val options: List<ProviderSettingOption> = emptyList(),
    val required: Boolean = false
)

sealed class ProviderSettingsUiTarget {
    data class NativeCloudStreamOpened(val pluginId: String) : ProviderSettingsUiTarget()

    data class NuvioConfig(
        val pluginId: String,
        val displayName: String,
        val fields: List<ProviderSettingField>,
        val customHeaders: Map<String, String>
    ) : ProviderSettingsUiTarget()

    data class StremioConfig(
        val pluginId: String,
        val displayName: String,
        val manifestUrl: String,
        val configureWebUrl: String?,
        val isConfigurable: Boolean,
        val fields: List<ProviderSettingField>,
        val customHeaders: Map<String, String>
    ) : ProviderSettingsUiTarget()
}
