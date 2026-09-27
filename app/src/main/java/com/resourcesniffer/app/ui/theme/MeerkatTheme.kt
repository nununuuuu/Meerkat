package com.resourcesniffer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MeerkatDarkColors = darkColorScheme(
    primary = Color(0xFF78E6C0),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005140),
    onPrimaryContainer = Color(0xFF96F4D5),
    secondary = Color(0xFFB2CCC1),
    onSecondary = Color(0xFF1E352D),
    secondaryContainer = Color(0xFF354C43),
    onSecondaryContainer = Color(0xFFCDE8DC),
    tertiary = Color(0xFFA8C7FA),
    onTertiary = Color(0xFF0B305F),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE0E4E1),
    surface = Color(0xFF101412),
    onSurface = Color(0xFFE0E4E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF89938E),
)

@Composable
fun MeerkatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeerkatDarkColors,
        content = content,
    )
}
