package io.moduhome.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
    primary = Color(0xFF3549B9),
    onPrimary = Color(0xFFFFFDFB),
    primaryContainer = Color(0xFFE7EAFF),
    onPrimaryContainer = Color(0xFF222840),
    secondary = Color(0xFF565D85),
    onSecondary = Color(0xFFFFFDFB),
    secondaryContainer = Color(0xFFFBE7D8),
    onSecondaryContainer = Color(0xFF683A28),
    tertiary = Color(0xFF76543B),
    onTertiary = Color(0xFFFFFDFB),
    tertiaryContainer = Color(0xFFFBE7D8),
    onTertiaryContainer = Color(0xFF683A28),
    background = Color(0xFFFDF9F3),
    onBackground = Color(0xFF222840),
    surface = Color(0xFFFDF9F3),
    surfaceVariant = Color(0xFFECE7E2),
    surfaceDim = Color(0xFFE8E2DC),
    surfaceBright = Color(0xFFFFFDFB),
    surfaceContainerLowest = Color(0xFFFFFDFB),
    surfaceContainerLow = Color(0xFFFAF5EE),
    surfaceContainer = Color(0xFFF3EEE8),
    surfaceContainerHigh = Color(0xFFECE7E2),
    surfaceContainerHighest = Color(0xFFE7E2DE),
    onSurface = Color(0xFF222840),
    onSurfaceVariant = Color(0xFF526078),
    outline = Color(0xFF758198),
    outlineVariant = Color(0xFFCCD2E4),
    inverseSurface = Color(0xFF252C43),
    inverseOnSurface = Color(0xFFF4F5FF),
    inversePrimary = Color(0xFFBEC8FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBEC8FF),
    onPrimary = Color(0xFF1B2348),
    primaryContainer = Color(0xFF343F69),
    onPrimaryContainer = Color(0xFFF4F5FF),
    secondary = Color(0xFFC2CBE4),
    onSecondary = Color(0xFF252C43),
    secondaryContainer = Color(0xFF62484A),
    onSecondaryContainer = Color(0xFFFFE4D1),
    tertiary = Color(0xFFF1BD9F),
    onTertiary = Color(0xFF402A23),
    tertiaryContainer = Color(0xFF62484A),
    onTertiaryContainer = Color(0xFFFFE4D1),
    background = Color(0xFF1B2033),
    onBackground = Color(0xFFF4F5FF),
    surface = Color(0xFF1B2033),
    surfaceVariant = Color(0xFF303951),
    surfaceDim = Color(0xFF171B2D),
    surfaceBright = Color(0xFF303951),
    surfaceContainerLowest = Color(0xFF171B2D),
    surfaceContainerLow = Color(0xFF20263C),
    surfaceContainer = Color(0xFF252C43),
    surfaceContainerHigh = Color(0xFF2B334A),
    surfaceContainerHighest = Color(0xFF303951),
    onSurface = Color(0xFFF4F5FF),
    onSurfaceVariant = Color(0xFFC2CBE4),
    outline = Color(0xFF9CA8C7),
    outlineVariant = Color(0xFF52607C),
    inverseSurface = Color(0xFFF3EEE8),
    inverseOnSurface = Color(0xFF222840),
    inversePrimary = Color(0xFF3549B9),
)

private val VehicleShapes = Shapes(
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val CompanionShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
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
            shapes = if (vehicle) VehicleShapes else CompanionShapes,
            content = content,
        )
    }
}
