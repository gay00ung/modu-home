package io.moduhome.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF255BD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECF2FF),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    surfaceContainer = Color(0xFFEEF2F7),
    onSurface = Color(0xFF192332),
    onSurfaceVariant = Color(0xFF627083),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF96B8FF),
    onPrimary = Color(0xFF10244B),
    primaryContainer = Color(0xFF263B60),
    background = Color(0xFF14181E),
    surface = Color(0xFF20262E),
    surfaceContainer = Color(0xFF2A3340),
    onSurface = Color(0xFFF1F4FA),
    onSurfaceVariant = Color(0xFFB0BDCF),
)

@Composable
internal fun ModuHomeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
