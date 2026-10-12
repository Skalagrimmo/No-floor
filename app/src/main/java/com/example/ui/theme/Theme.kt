package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val StealthColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = TacticalNavy,
    primaryContainer = TacticalSurfaceVariant,
    onPrimaryContainer = NeonCyan,
    secondary = NeonAmber,
    onSecondary = TacticalNavy,
    tertiary = AlertRed,
    background = TacticalNavy,
    onBackground = PureWhite,
    surface = TacticalSurface,
    onSurface = PureWhite,
    surfaceVariant = TacticalSurfaceVariant,
    onSurfaceVariant = DimText,
    outline = TacticalBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StealthColorScheme,
        typography = Typography,
        content = content
    )
}
