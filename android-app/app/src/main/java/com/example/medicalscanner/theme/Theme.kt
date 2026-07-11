package com.example.medicalscanner.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = MedicalTealLight,
    onPrimary = MedicalOnSurface,
    primaryContainer = MedicalNavy,
    onPrimaryContainer = MedicalTealLight,
    secondary = MedicalNavyLight,
    onSecondary = MedicalOnSurface,
    secondaryContainer = MedicalDarkSurfaceVariant,
    onSecondaryContainer = MedicalNavyLight,
    tertiary = MedicalAmberLight,
    onTertiary = MedicalOnSurface,
    tertiaryContainer = MedicalDarkSurfaceVariant,
    onTertiaryContainer = MedicalAmberLight,
    background = MedicalDarkBackground,
    onBackground = MedicalOnPrimary,
    surface = MedicalDarkSurface,
    onSurface = MedicalOnPrimary,
    surfaceVariant = MedicalDarkSurfaceVariant,
    onSurfaceVariant = MedicalOutlineVariant,
    outline = MedicalOutline,
    outlineVariant = MedicalOnSurfaceVariant,
)

private val LightColorScheme =
    lightColorScheme(
        primary = MedicalTeal,
        onPrimary = MedicalOnPrimary,
        primaryContainer = MedicalSurfaceVariant,
        onPrimaryContainer = MedicalNavy,
        secondary = MedicalNavy,
        onSecondary = MedicalOnSecondary,
        secondaryContainer = MedicalSurfaceVariant,
        onSecondaryContainer = MedicalNavy,
        tertiary = MedicalAmber,
        onTertiary = MedicalOnPrimary,
        tertiaryContainer = MedicalSurfaceVariant,
        onTertiaryContainer = MedicalNavy,
        background = MedicalBackground,
        onBackground = MedicalOnBackground,
        surface = MedicalSurface,
        onSurface = MedicalOnSurface,
        surfaceVariant = MedicalSurfaceVariant,
        onSurfaceVariant = MedicalOnSurfaceVariant,
        outline = MedicalOutline,
        outlineVariant = MedicalOutlineVariant,
    )

@Composable
fun MedicalScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Disabled: medical app should maintain consistent branding regardless of wallpaper
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

