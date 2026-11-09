package com.pahlevi.levi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF1F8A70)
private val TealLight = Color(0xFF5FD4B8)
private val Amber = Color(0xFFFFB547)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0E5141),
    onPrimaryContainer = Color(0xFFB6F2DF),
    secondary = Amber,
    onSecondary = Color(0xFF3F2800),
    secondaryContainer = Color(0xFF4A3A16),
    onSecondaryContainer = Color(0xFFFFDDAF),
    background = Color(0xFF0F1A17),
    onBackground = Color(0xFFE0E6E3),
    surface = Color(0xFF0F1A17),
    onSurface = Color(0xFFE0E6E3),
    surfaceVariant = Color(0xFF1E2B27),
    onSurfaceVariant = Color(0xFFBCC9C4),
    surfaceContainer = Color(0xFF17231F),
    surfaceContainerHigh = Color(0xFF1C2925),
    surfaceContainerHighest = Color(0xFF23312C),
    outline = Color(0xFF87938F),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F2DF),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDAF),
    onSecondaryContainer = Color(0xFF2B1800),
    background = Color(0xFFF6FBF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF6FBF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceContainer = Color(0xFFEAF2EE),
    surfaceContainerHigh = Color(0xFFE4ECE8),
    surfaceContainerHighest = Color(0xFFDEE6E2),
)

@Composable
fun LeviTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
