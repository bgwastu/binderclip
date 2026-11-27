package net.wastu.binderclip

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

internal val Ink = Color(0xFF385579)
internal val OnInk = Color(0xFFF4F7FB)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = OnInk,
    primaryContainer = Color(0xFFD9E4F3),
    onPrimaryContainer = Color(0xFF16283E),
    secondary = Color(0xFF495E76),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E8F2),
    onSecondaryContainer = Color(0xFF24384F),
    tertiary = Color(0xFF355F52),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC9E8D9),
    onTertiaryContainer = Color(0xFF173B31),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF1B2634),
    surface = Color(0xFFF7F8FB),
    onSurface = Color(0xFF1B2634),
    surfaceVariant = Color(0xFFE1E7EF),
    onSurfaceVariant = Color(0xFF465468),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F3F8),
    surfaceContainer = Color(0xFFE9EEF5),
    surfaceContainerHigh = Color(0xFFE3E9F2),
    surfaceContainerHighest = Color(0xFFDBE3EE),
    surfaceDim = Color(0xFFD8DFEA),
    surfaceBright = Color(0xFFF7F8FB),
    outline = Color(0xFF68778B),
    outlineVariant = Color(0xFFC6D0DE),
    inverseSurface = Color(0xFF263449),
    inverseOnSurface = Color(0xFFEFF4FB),
    inversePrimary = Color(0xFFB9CCE5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9CCE5),
    onPrimary = Color(0xFF102238),
    primaryContainer = Color(0xFF263C56),
    onPrimaryContainer = Color(0xFFDCEAFF),
    secondary = Color(0xFFBECFE2),
    onSecondary = Color(0xFF213144),
    secondaryContainer = Color(0xFF293C51),
    onSecondaryContainer = Color(0xFFD7E5F6),
    tertiary = Color(0xFF9FD0BA),
    onTertiary = Color(0xFF12362B),
    tertiaryContainer = Color(0xFF21463A),
    onTertiaryContainer = Color(0xFFC1EAD6),
    background = Color(0xFF192535),
    onBackground = Color(0xFFE5ECF6),
    surface = Color(0xFF192535),
    onSurface = Color(0xFFE5ECF6),
    surfaceVariant = Color(0xFF253447),
    onSurfaceVariant = Color(0xFFBCCADD),
    surfaceContainerLowest = Color(0xFF141F2E),
    surfaceContainerLow = Color(0xFF1E2B3C),
    surfaceContainer = Color(0xFF233044),
    surfaceContainerHigh = Color(0xFF28374B),
    surfaceContainerHighest = Color(0xFF314156),
    surfaceDim = Color(0xFF192535),
    surfaceBright = Color(0xFF35465B),
    outline = Color(0xFF899AB0),
    outlineVariant = Color(0xFF3F4E62),
    inverseSurface = Color(0xFFE5ECF6),
    inverseOnSurface = Color(0xFF202E40),
    inversePrimary = Ink,
)

private val ClipShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

private val ClipTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
internal fun groupedListColors() =
    ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

@Composable
fun BinderClipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialExpressiveTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        motionScheme = MotionScheme.expressive(),
        shapes = ClipShapes,
        typography = ClipTypography,
        content = content,
    )
}
