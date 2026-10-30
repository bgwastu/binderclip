package net.wastu.binderclip

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0A5FD9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001C3D),
    secondary = Color(0xFF007ACC),
    secondaryContainer = Color(0xFFD0E4FF),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF99CBFF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF004880),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF82B1FF),
    secondaryContainer = Color(0xFF003B71),
)

@Composable
fun BinderClipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
