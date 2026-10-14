package com.pablo.yumemi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val YumemiColors = lightColorScheme(
    primary = Yu.Periwinkle,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E5FF),
    onPrimaryContainer = Yu.Indigo,
    secondary = Yu.Sky,
    onSecondary = Color(0xFF1D3A55),
    secondaryContainer = Yu.SkySoft,
    onSecondaryContainer = Color(0xFF1D3A55),
    tertiary = Yu.Lilac,
    onTertiary = Yu.Indigo,
    tertiaryContainer = Yu.LilacSoft,
    onTertiaryContainer = Yu.Indigo,
    background = Yu.BgTop,
    onBackground = Yu.Indigo,
    surface = Color.White,
    onSurface = Yu.Indigo,
    surfaceVariant = Color(0xFFEEF0FB),
    onSurfaceVariant = Yu.Muted,
    surfaceTint = Yu.Periwinkle,
    outline = Color(0xFFB7BCE0),
    outlineVariant = Color(0xFFE2E5F7),
    error = Yu.Danger,
    onError = Color.White,
    errorContainer = Yu.DangerSoft,
    onErrorContainer = Color(0xFF8A2B42),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FF),
    surfaceContainer = Color(0xFFF3F4FF),
    surfaceContainerHigh = Color(0xFFEEF0FF),
    surfaceContainerHighest = Color(0xFFE8EBFC),
)

private val YumemiTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

private val YumemiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Light mode only, by design. */
@Composable
fun YumemiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = YumemiColors,
        typography = YumemiTypography,
        shapes = YumemiShapes,
        content = content,
    )
}
