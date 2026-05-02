package com.pocketnode.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Vibrant Dark Mode for AI Gallery look
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8B6BFF), // Vibrant purple
    onPrimary = Color.White,
    primaryContainer = Color(0xFF382387),
    onPrimaryContainer = Color(0xFFE8DFFF),
    secondary = Color(0xFF00E5FF), // Cyan accent
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF004F59),
    onSecondaryContainer = Color(0xFFB3FDFF),
    tertiary = Color(0xFFFF3366), // Pink/Red accent
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF7A0022),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF0A0C10), // Very dark background
    onBackground = Color(0xFFE0E2E8),
    surface = Color(0xFF14171F), // Slightly lighter surface
    onSurface = Color(0xFFE0E2E8),
    surfaceVariant = Color(0xFF1E2330),
    onSurfaceVariant = Color(0xFFA0A5B5),
    outline = Color(0xFF4C5468),
    outlineVariant = Color(0xFF2E354A),
    error = Color(0xFFFF5252),
    onError = Color.White,
    errorContainer = Color(0xFF8C0000),
    onErrorContainer = Color(0xFFFFD6D6),
)

// Clean Light Mode
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF5331E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0D8FF),
    onPrimaryContainer = Color(0xFF160061),
    secondary = Color(0xFF00968A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFFFA),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFFE6004C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3E0010),
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF14171F),
    surface = Color.White,
    onSurface = Color(0xFF14171F),
    surfaceVariant = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFF565C6D),
    outline = Color(0xFF8A91A5),
    outlineVariant = Color(0xFFCFD3E0),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
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
        typography = AppTypography,
        content = content
    )
}
