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
    bodySmall = base.bodySmall.copy(fontSize = 28.sp, lineHeight = 38.sp),
    labelLarge = base.labelLarge.copy(fontSize = 28.sp, lineHeight = 38.sp),
    labelMedium = base.labelMedium.copy(fontSize = 28.sp, lineHeight = 38.sp),
    labelSmall = base.labelSmall.copy(fontSize = 28.sp, lineHeight = 38.sp),
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

private val LightVehicleColors = LightColors.copy(
    primary = Color(0xFF3549B9),
    onPrimary = Color(0xFFFFFDFB),
    primaryContainer = Color(0xFFE7EAFF),
    onPrimaryContainer = Color(0xFF222840),
    secondaryContainer = Color(0xFFFFD4B6),
    onSecondaryContainer = Color(0xFF683A28),
    background = Color(0xFFFDF9F3),
    onBackground = Color(0xFF222840),
    surface = Color(0xFFFDF9F3),
    surfaceContainer = Color(0xFFF3EEE8),
    onSurface = Color(0xFF222840),
    onSurfaceVariant = Color(0xFF526078),
    outline = Color(0xFF758198),
    outlineVariant = Color(0xFFCCD2E4),
)

private val DarkVehicleColors = DarkColors.copy(
    primary = Color(0xFFBEC8FF),
    onPrimary = Color(0xFF1B2348),
    primaryContainer = Color(0xFF343F69),
    onPrimaryContainer = Color(0xFFF4F5FF),
    secondaryContainer = Color(0xFF62484A),
    onSecondaryContainer = Color(0xFFFFE4D1),
    background = Color(0xFF1B2033),
    onBackground = Color(0xFFF4F5FF),
    surface = Color(0xFF1B2033),
    surfaceContainer = Color(0xFF252C43),
    onSurface = Color(0xFFF4F5FF),
    onSurfaceVariant = Color(0xFFC2CBE4),
    outline = Color(0xFF9CA8C7),
    outlineVariant = Color(0xFF52607C),
)

@Composable
internal fun ModuHomeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    vehicle: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (vehicle) 80.dp else 48.dp) {
        MaterialTheme(
            colorScheme = when {
                vehicle && darkTheme -> DarkVehicleColors
                vehicle -> LightVehicleColors
                darkTheme -> DarkColors
                else -> LightColors
            },
            typography = if (vehicle) VehicleTypography else Typography(),
            content = content,
        )
    }
}
