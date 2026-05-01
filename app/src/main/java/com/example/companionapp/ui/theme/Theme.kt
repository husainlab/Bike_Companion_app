package com.example.companionapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = M3Primary,
    onPrimary = M3OnPrimary,
    primaryContainer = M3PrimaryContainer,
    onPrimaryContainer = M3OnPrimaryContainer,
    secondary = M3Secondary,
    onSecondary = M3OnSecondary,
    secondaryContainer = M3SecondaryContainer,
    onSecondaryContainer = M3OnSecondaryContainer,
    surface = M3Surface,
    onSurface = M3OnSurface,
    surfaceVariant = M3SurfaceVariant,
    onSurfaceVariant = M3OnSurfaceVariant,
    error = M3Error,
    onError = M3OnError,
    background = M3Surface
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF59DBC2),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005044),
    onPrimaryContainer = Color(0xFF76F8DE),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1D352F),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCCE8E3),
    surface = Color(0xFF191C1B),
    onSurface = Color(0xFFE1E3E0),
    background = Color(0xFF191C1B)
)

@Composable
fun CompanionAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
