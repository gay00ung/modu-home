package io.moduhome.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val VehicleTypography = Typography().let { base -> base.copy(
    displayLarge = base.displayLarge.copy(fontSize = 60.sp, lineHeight = 68.sp),
    displayMedium = base.displayMedium.copy(fontSize = 56.sp, lineHeight = 64.sp),
    displaySmall = base.displaySmall.copy(fontSize = 48.sp, lineHeight = 56.sp),
    headlineLarge = base.headlineLarge.copy(fontSize = 44.sp, lineHeight = 52.sp),
    headlineMedium = base.headlineMedium.copy(fontSize = 40.sp, lineHeight = 48.sp),
    headlineSmall = base.headlineSmall.copy(fontSize = 36.sp, lineHeight = 44.sp),
    titleLarge = base.titleLarge.copy(fontSize = 32.sp, lineHeight = 40.sp),
    titleMedium = base.titleMedium.copy(fontSize = 30.sp, lineHeight = 38.sp),
    titleSmall = base.titleSmall.copy(fontSize = 28.sp, lineHeight = 36.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 30.sp, lineHeight = 40.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 28.sp, lineHeight = 38.sp),
    bodySmall = base.bodySmall.copy(fontSize = 22.sp, lineHeight = 30.sp),
    labelLarge = base.labelLarge.copy(fontSize = 26.sp, lineHeight = 34.sp),
    labelMedium = base.labelMedium.copy(fontSize = 24.sp, lineHeight = 32.sp),
    labelSmall = base.labelSmall.copy(fontSize = 22.sp, lineHeight = 30.sp),
) }

private val LightColors = lightColorScheme(
    primary = Color(0xFF255BD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECF2FF),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    surfaceContainer = Color(0xFFEEF2F7),
    onSurface = Color(0xFF192332),
    onSurfaceVariant = Color(0xFF566579),
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
    vehicle: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (vehicle) 80.dp else 48.dp) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = if (vehicle) VehicleTypography else Typography(),
            content = content,
        )
    }
}
