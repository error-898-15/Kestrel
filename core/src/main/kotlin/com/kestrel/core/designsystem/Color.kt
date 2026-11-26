// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class KestrelColorTokens(
    val canvas: Color = Color(0xFF090B10),
    val surfaceLow: Color = Color(0xFF10141D),
    val surface: Color = Color(0xFF161C28),
    val surfaceHigh: Color = Color(0xFF1E2636),
    val borderSubtle: Color = Color(0xFF263044),
    val borderStrong: Color = Color(0xFF3A4763),
    val textPrimary: Color = Color(0xFFF3F5F9),
    val textSecondary: Color = Color(0xFF98A4BC),
    val textMuted: Color = Color(0xFF67748E),
    val accentAmber: Color = Color(0xFFF5A623),
    val accentAmberDim: Color = Color(0x33F5A623),
    val accentTeal: Color = Color(0xFF2DD4BF),
    val accentTealDim: Color = Color(0x292DD4BF),
    val statusSuccess: Color = Color(0xFF34D399),
    val statusWarning: Color = Color(0xFFFBBF24),
    val statusError: Color = Color(0xFFF87171),
    val statusErrorDim: Color = Color(0x29F87171),
    val scrim: Color = Color(0xCC06080C)
)

val DefaultKestrelColors = KestrelColorTokens()

val KestrelDarkColorScheme = darkColorScheme(
    primary = DefaultKestrelColors.accentAmber,
    onPrimary = Color(0xFF1A1102),
    primaryContainer = DefaultKestrelColors.accentAmberDim,
    onPrimaryContainer = DefaultKestrelColors.accentAmber,
    secondary = DefaultKestrelColors.accentTeal,
    onSecondary = Color(0xFF04201C),
    secondaryContainer = DefaultKestrelColors.accentTealDim,
    onSecondaryContainer = DefaultKestrelColors.accentTeal,
    background = DefaultKestrelColors.canvas,
    onBackground = DefaultKestrelColors.textPrimary,
    surface = DefaultKestrelColors.surfaceLow,
    onSurface = DefaultKestrelColors.textPrimary,
    surfaceVariant = DefaultKestrelColors.surface,
    onSurfaceVariant = DefaultKestrelColors.textSecondary,
    surfaceContainerLowest = DefaultKestrelColors.canvas,
    surfaceContainerLow = DefaultKestrelColors.surfaceLow,
    surfaceContainer = DefaultKestrelColors.surface,
    surfaceContainerHigh = DefaultKestrelColors.surfaceHigh,
    outline = DefaultKestrelColors.borderStrong,
    outlineVariant = DefaultKestrelColors.borderSubtle,
    error = DefaultKestrelColors.statusError,
    onError = Color(0xFF2B0606),
    errorContainer = DefaultKestrelColors.statusErrorDim,
    onErrorContainer = DefaultKestrelColors.statusError,
    scrim = DefaultKestrelColors.scrim
)
