package com.wlzxj.tunnel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TealPrimary = Color(0xFF187B73)
val TealDark = Color(0xFF0D4A45)
val BgLight = Color(0xFFF4F6F8)
val CardLight = Color.White
val BgDark = Color(0xFF121416)
val CardDark = Color(0xFF1E2226)
val TealContainerLight = Color(0xFFCFEBE8)
val TealContainerDark = Color(0xFF2A4F4B)

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    secondary = TealPrimary,
    onSecondary = Color.White,
    secondaryContainer = TealContainerLight,
    onSecondaryContainer = TealDark,
    tertiary = TealPrimary,
    background = BgLight,
    onBackground = Color(0xFF1A1C1E),
    surface = CardLight,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE8EBEE),
    onSurfaceVariant = Color(0xFF44474A),
    surfaceContainer = CardLight,
    surfaceContainerLow = BgLight,
    surfaceContainerHigh = CardLight,
    surfaceContainerHighest = CardLight,
)

private val DarkColors = darkColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    secondary = TealPrimary,
    onSecondary = Color.White,
    secondaryContainer = TealContainerDark,
    onSecondaryContainer = Color(0xFFB8E4DE),
    tertiary = TealPrimary,
    background = BgDark,
    onBackground = Color(0xFFE3E2E6),
    surface = CardDark,
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF2B3035),
    onSurfaceVariant = Color(0xFFC4C7CA),
    surfaceContainer = CardDark,
    surfaceContainerLow = BgDark,
    surfaceContainerHigh = Color(0xFF252A2E),
    surfaceContainerHighest = Color(0xFF2B3035),
)

@Composable
fun WssTunnelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
