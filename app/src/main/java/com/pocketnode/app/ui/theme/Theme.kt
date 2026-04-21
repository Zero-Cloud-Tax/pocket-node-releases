package com.pocketnode.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB8C2FF),
    onPrimary = Color(0xFF0D1A52),
    primaryContainer = Color(0xFF24306F),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC9C3FF),
    onSecondary = Color(0xFF241C5E),
    secondaryContainer = Color(0xFF38307A),
    onSecondaryContainer = Color(0xFFE8E0FF),
    tertiary = Color(0xFFE8B8FF),
    onTertiary = Color(0xFF4D186A),
    tertiaryContainer = Color(0xFF653383),
    onTertiaryContainer = Color(0xFFF7D8FF),
    background = Color(0xFF09101F),
    onBackground = Color(0xFFE2E8FF),
    surface = Color(0xFF11192B),
    onSurface = Color(0xFFE2E8FF),
    surfaceVariant = Color(0xFF202841),
    onSurfaceVariant = Color(0xFFC1C8E4),
    outline = Color(0xFF8D95B4),
    outlineVariant = Color(0xFF394160),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4257C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF6156C7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4DEFF),
    onSecondaryContainer = Color(0xFF1D136D),
    tertiary = Color(0xFF8E46B0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8D8FF),
    onTertiaryContainer = Color(0xFF39004F),
    background = Color(0xFFF6F8FF),
    onBackground = Color(0xFF161C2E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161C2E),
    surfaceVariant = Color(0xFFE1E6F7),
    onSurfaceVariant = Color(0xFF434B68),
    outline = Color(0xFF747D9C),
    outlineVariant = Color(0xFFC3C9DE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun PocketNodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
        typography = Typography(),
        content = content
    )
}
