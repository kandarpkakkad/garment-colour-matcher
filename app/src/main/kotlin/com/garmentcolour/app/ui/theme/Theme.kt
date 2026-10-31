package com.garmentcolour.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Achromatic chrome (D22, design system §1-§2): every token is grey, so the photo and
 * the swatches are the only colour on screen. No dynamic colour.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF2B2B2B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDADADA),
    onPrimaryContainer = Color(0xFF1A1A1A),
    secondary = Color(0xFF2B2B2B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDADADA),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF2B2B2B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDADADA),
    onTertiaryContainer = Color(0xFF1A1A1A),
    background = Color(0xFFF4F4F4),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFF555555),
    surfaceTint = Color(0xFF2B2B2B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFC4C4C4),
    error = Color(0xFF1A1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFDADADA),
    onErrorContainer = Color(0xFF1A1A1A),
    inverseSurface = Color(0xFF2B2B2B),
    inverseOnSurface = Color(0xFFF4F4F4),
    inversePrimary = Color(0xFFE0E0E0),
    scrim = Color(0xFF000000),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE0E0E0),
    onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color(0xFFEDEDED),
    secondary = Color(0xFFE0E0E0),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFF3A3A3A),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFE0E0E0),
    onTertiary = Color(0xFF121212),
    tertiaryContainer = Color(0xFF3A3A3A),
    onTertiaryContainer = Color(0xFFEDEDED),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceTint = Color(0xFFE0E0E0),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),
    outline = Color(0xFF6E6E6E),
    outlineVariant = Color(0xFF444444),
    error = Color(0xFFEDEDED),
    onError = Color(0xFF121212),
    errorContainer = Color(0xFF3A3A3A),
    onErrorContainer = Color(0xFFEDEDED),
    inverseSurface = Color(0xFFE0E0E0),
    inverseOnSurface = Color(0xFF1C1C1C),
    inversePrimary = Color(0xFF2B2B2B),
    scrim = Color(0xFF000000),
)

/** The photo's surround: mid-grey, L* ≈ 50, in both themes (design system §1). */
val Stage = Color(0xFF767676)

@Composable
fun GarmentColourTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
