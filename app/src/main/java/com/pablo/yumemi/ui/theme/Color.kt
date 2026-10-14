package com.pablo.yumemi.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Yumemi palette: light blues, lilacs, a touch of gold. */
object Yu {
    val Periwinkle = Color(0xFF7B7FE0)
    val PeriwinkleDeep = Color(0xFF5B60B8)
    val Sky = Color(0xFF8FD3F4)
    val SkySoft = Color(0xFFDDF1FC)
    val Lilac = Color(0xFFB9A8F0)
    val LilacSoft = Color(0xFFEFEAFF)
    val Gold = Color(0xFFF5C95A)
    val GoldSoft = Color(0xFFFFF4D6)
    val Indigo = Color(0xFF2F3566)
    val Muted = Color(0xFF6B719E)
    val Outline = Color(0xFFD5D9F2)
    val BgTop = Color(0xFFEEF5FF)
    val BgBottom = Color(0xFFF3EEFF)
    val Success = Color(0xFF2E9E7E)
    val SuccessSoft = Color(0xFFDDF6EC)
    val Danger = Color(0xFFD9546F)
    val DangerSoft = Color(0xFFFFE8EE)
    val ShadowTint = Color(0xFF5B60B8)

    val BackgroundBrush: Brush
        get() = Brush.verticalGradient(listOf(BgTop, BgBottom))
    val UserBubbleBrush: Brush
        get() = Brush.linearGradient(listOf(Color(0xFF7B7FE0), Color(0xFF9B8CEB)))
    val PrimaryBrush: Brush
        get() = Brush.linearGradient(listOf(Color(0xFF7B7FE0), Color(0xFFA08DEE)))
}
