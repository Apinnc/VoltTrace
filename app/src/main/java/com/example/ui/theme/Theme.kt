package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeMode {
    DARK, LIGHT, SYSTEM
}

@Immutable
data class TelemetryColors(
    val healthy: Color,
    val charging: Color,
    val warning: Color,
    val critical: Color,
    val standby: Color,
    val gridLine: Color,
    val cardBorder: Color,
    val screenOnBand: Color,
    val isDark: Boolean
)

val LocalTelemetryColors = staticCompositionLocalOf {
    TelemetryColors(
        healthy = VoltLime,
        charging = TelemetryCyan,
        warning = ThermalAmber,
        critical = DrainCrimson,
        standby = StandbyIndigo,
        gridLine = Color(0xFF233041),
        cardBorder = Color(0xFF263445),
        screenOnBand = Color(0x1EA6FF4D),
        isDark = true
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = VoltLime,
    onPrimary = VoltLimeOn,
    primaryContainer = VoltLimeContainer,
    onPrimaryContainer = VoltLimeOnContainer,
    secondary = TelemetryCyan,
    onSecondary = TelemetryCyanOn,
    secondaryContainer = TelemetryCyanContainer,
    onSecondaryContainer = TelemetryCyanOnContainer,
    tertiary = ThermalAmber,
    onTertiary = ThermalAmberOn,
    tertiaryContainer = ThermalAmberContainer,
    onTertiaryContainer = ThermalAmberOnContainer,
    error = DrainCrimson,
    onError = DrainCrimsonOn,
    errorContainer = DrainCrimsonContainer,
    onErrorContainer = DrainCrimsonOnContainer,
    background = ObsidianCanvas,
    onBackground = SlateInkPrimary,
    surface = ObsidianSurface,
    onSurface = SlateInkPrimary,
    surfaceVariant = ObsidianContainer,
    onSurfaceVariant = SlateInkSecondary,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianContainer,
    surfaceContainerHigh = ObsidianContainerHigh,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF1F2B38)
)

private val LightColorScheme = lightColorScheme(
    primary = ForestVolt,
    onPrimary = ForestVoltOn,
    primaryContainer = ForestVoltContainer,
    onPrimaryContainer = ForestVoltOnContainer,
    secondary = DeepCyan,
    onSecondary = DeepCyanOn,
    secondaryContainer = DeepCyanContainer,
    onSecondaryContainer = DeepCyanOnContainer,
    tertiary = DeepAmber,
    onTertiary = DeepAmberOn,
    tertiaryContainer = DeepAmberContainer,
    onTertiaryContainer = DeepAmberOnContainer,
    error = DeepCrimson,
    onError = DeepCrimsonOn,
    errorContainer = DeepCrimsonContainer,
    onErrorContainer = DeepCrimsonOnContainer,
    background = AlabasterCanvas,
    onBackground = LightInkPrimary,
    surface = AlabasterSurface,
    onSurface = LightInkPrimary,
    surfaceVariant = AlabasterContainer,
    onSurfaceVariant = LightInkSecondary,
    surfaceContainerLowest = AlabasterSurface,
    surfaceContainerLow = AlabasterCanvas,
    surfaceContainer = AlabasterContainer,
    surfaceContainerHigh = AlabasterContainerHigh,
    outline = AlabasterOutline,
    outlineVariant = Color(0xFFD5E0EA)
)

val VoltTraceShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun VoltTraceTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (useDark) DarkColorScheme else LightColorScheme
    val telemetryColors = if (useDark) {
        TelemetryColors(
            healthy = VoltLime,
            charging = TelemetryCyan,
            warning = ThermalAmber,
            critical = DrainCrimson,
            standby = StandbyIndigo,
            gridLine = Color(0xFF233041),
            cardBorder = Color(0xFF263445),
            screenOnBand = Color(0x1AA6FF4D),
            isDark = true
        )
    } else {
        TelemetryColors(
            healthy = ForestVolt,
            charging = DeepCyan,
            warning = DeepAmber,
            critical = DeepCrimson,
            standby = Color(0xFF4F46E5),
            gridLine = Color(0xFFD3DDE7),
            cardBorder = Color(0xFFC9D5E0),
            screenOnBand = Color(0x1A1E7E24),
            isDark = false
        )
    }

    CompositionLocalProvider(LocalTelemetryColors provides telemetryColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VoltTraceTypography,
            shapes = VoltTraceShapes,
            content = content
        )
    }
}
