package com.dmedit.app.ui.theme

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

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    secondary = Color(0xFFA5B4FC),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B)
)

private val LightPalette = lightColorScheme(
    primary = Color(0xFF0284C7),
    secondary = Color(0xFF4F46E5),
    tertiary = Color(0xFFD97706)
)

@Composable
fun DmeditTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkPalette
        else -> LightPalette
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
