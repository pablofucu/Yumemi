package com.pablo.yumemi.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pablo.yumemi.ui.theme.Yu

/** Three bouncing dots. */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(
        modifier.padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 3) {
            val v = t.animateFloat(
                0f, 1f,
                infiniteRepeatable(
                    tween(420),
                    RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 140),
                ),
                label = "dot$i",
            )
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        translationY = -5.dp.toPx() * v.value
                        alpha = 0.45f + 0.55f * v.value
                    }
                    .background(Yu.Periwinkle, CircleShape),
            )
        }
    }
}

/** Text with a light band sweeping across it. */
@Composable
fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x = t.animateFloat(
        -200f, 500f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    val brush = Brush.linearGradient(
        colors = listOf(Yu.Muted, Yu.Periwinkle, Yu.Lilac, Yu.Periwinkle, Yu.Muted),
        start = Offset(x.value, 0f),
        end = Offset(x.value + 200f, 0f),
    )
    Text(
        text,
        modifier,
        style = TextStyle(
            brush = brush,
            fontSize = fontSize,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Medium,
        ),
    )
}
