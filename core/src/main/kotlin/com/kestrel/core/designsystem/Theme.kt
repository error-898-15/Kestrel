// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalKestrelColors = staticCompositionLocalOf { DefaultKestrelColors }
val LocalKestrelSpacing = staticCompositionLocalOf { DefaultKestrelSpacing }

object KestrelTheme {
    val colors: KestrelColorTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalKestrelColors.current

    val spacing: KestrelSpacingTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalKestrelSpacing.current
}

@Composable
fun KestrelTheme(
    colors: KestrelColorTokens = DefaultKestrelColors,
    spacing: KestrelSpacingTokens = DefaultKestrelSpacing,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalKestrelColors provides colors,
        LocalKestrelSpacing provides spacing
    ) {
        MaterialTheme(
            colorScheme = KestrelDarkColorScheme,
            typography = KestrelTypography,
            shapes = KestrelShapes,
            content = content
        )
    }
}
