package com.bubu.cast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Nền tối xanh đen (theo spec BrowserScreen)
val BgDeep = Color(0xFF0F1419)
val BgCard = Color(0xFF1A1F24) // App bar
val BgCardLight = Color(0xFF222A33)

// Điểm nhấn tím neon (theo spec)
val NeonPurple = Color(0xFF9D4EDD)
val NeonPurpleDark = Color(0xFF7C4DFF)
val NeonCyan = Color(0xFF64FFDA)

// Nút Cast active: xanh lá
val CastGreen = Color(0xFF4CAF50)

// Chữ
val TextPrimary = Color(0xFFECEFF1)
val TextSecondary = Color(0xFF90A4AE)

// Trạng thái
val SuccessGreen = Color(0xFF69F0AE)
val ErrorRed = Color(0xFFFF5252)
val WarningYellow = Color(0xFFFFD740)

private val DarkColors = darkColorScheme(
    primary = NeonPurple,
    onPrimary = Color.Black,
    primaryContainer = NeonPurpleDark,
    onPrimaryContainer = Color.White,
    secondary = NeonCyan,
    onSecondary = Color.Black,
    background = BgDeep,
    onBackground = TextPrimary,
    surface = BgCard,
    onSurface = TextPrimary,
    surfaceVariant = BgCardLight,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = Color.White
)

private val LightColors = lightColorScheme(
    primary = NeonPurpleDark,
    onPrimary = Color.White,
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF212121),
    surface = Color.White,
    onSurface = Color(0xFF212121),
    error = Color(0xFFD32F2F)
)

/** Theme Bubu Cast — tối mặc định, có chế độ sáng */
@Composable
fun BubuCastTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
