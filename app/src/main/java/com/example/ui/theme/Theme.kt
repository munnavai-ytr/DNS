package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SentinelDarkColorScheme =
    darkColorScheme(
        primary = ShieldEmerald,
        onPrimary = ShieldEmeraldDark,
        primaryContainer = ShieldEmeraldContainer,
        onPrimaryContainer = ShieldEmerald,
        secondary = ShieldCyan,
        onSecondary = ObsidianBg,
        secondaryContainer = ObsidianSurfaceHigh,
        onSecondaryContainer = ShieldCyan,
        tertiary = ShieldAmber,
        onTertiary = ObsidianBg,
        tertiaryContainer = ShieldAmberContainer,
        onTertiaryContainer = ShieldAmber,
        error = ShieldCoral,
        onError = ObsidianBg,
        errorContainer = ShieldCoralContainer,
        onErrorContainer = ShieldCoral,
        background = ObsidianBg,
        onBackground = TextPrimary,
        surface = ObsidianSurface,
        onSurface = TextPrimary,
        surfaceVariant = ObsidianSurfaceHigh,
        onSurfaceVariant = TextSecondary,
        surfaceContainerLow = ObsidianSurface,
        surfaceContainer = ObsidianSurfaceHigh,
        surfaceContainerHigh = ObsidianSurfaceHighest,
        outline = ObsidianBorder,
        outlineVariant = ObsidianBorder,
    )

private val SentinelShapes =
    Shapes(
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
    )

@Composable
fun DNSShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SentinelDarkColorScheme,
        typography = Typography,
        shapes = SentinelShapes,
        content = content,
    )
}
