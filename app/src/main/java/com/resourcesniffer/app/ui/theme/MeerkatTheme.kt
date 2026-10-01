package com.resourcesniffer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MeerkatColors = lightColorScheme(
    primary = Color(0xFF466C80), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEAF0), onPrimaryContainer = Color(0xFF27485A),
    secondary = Color(0xFF9B5738), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6DFCD), onSecondaryContainer = Color(0xFF603B29),
    tertiary = Color(0xFFAD6845), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE5D1), onTertiaryContainer = Color(0xFF663C26),
    background = Color(0xFFFAF7F2), onBackground = Color(0xFF302E2B),
    surface = Color(0xFFFAF7F2), onSurface = Color(0xFF302E2B),
    surfaceVariant = Color(0xFFF0EAE1), onSurfaceVariant = Color(0xFF686158),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFCF8),
    surfaceContainer = Color(0xFFF4EFE8), surfaceContainerHigh = Color(0xFFEFE8DF),
    surfaceContainerHighest = Color(0xFFE8E0D6),
    outline = Color(0xFF8A8178), outlineVariant = Color(0xFFDED5CA),
    error = Color(0xFFAB4038), onError = Color.White,
    errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF702821),
)

@Composable
fun MeerkatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MeerkatColors, content = content)
}
