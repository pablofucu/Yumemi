package com.pablo.yumemi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pablo.yumemi.domain.ConnectionState
import com.pablo.yumemi.ui.theme.Yu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Soft blue -> lilac gradient used behind every screen. */
@Composable
fun YumemiBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(Yu.BackgroundBrush)) { content() }
}

/** Soft, indigo-tinted shadow. */
fun Modifier.softShadow(elevation: Dp = 8.dp, shape: Shape = RoundedCornerShape(20.dp)): Modifier =
    shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = Yu.ShadowTint.copy(alpha = 0.18f),
        spotColor = Yu.ShadowTint.copy(alpha = 0.28f),
    )

/** White rounded card with a soft shadow. Clickable when [onClick] is given. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    color: Color = Color.White,
    elevation: Dp = 6.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.softShadow(elevation, shape),
        shape = shape,
        color = color,
        content = content,
    )
}

fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Connected -> "Conectado"
    ConnectionState.Checking -> "Conectando…"
    ConnectionState.Unauthorized -> "Clave incorrecta"
    ConnectionState.NotConfigured -> "Sin configurar"
    is ConnectionState.Unreachable -> "Sin conexión"
    ConnectionState.Unknown -> "Comprobando…"
}

fun connectionColor(state: ConnectionState): Color = when (state) {
    ConnectionState.Connected -> Yu.Success
    ConnectionState.Checking, ConnectionState.Unknown -> Yu.Sky
    ConnectionState.Unauthorized -> Yu.Gold
    ConnectionState.NotConfigured -> Yu.Lilac
    is ConnectionState.Unreachable -> Yu.Danger
}

@Composable
fun ConnectionDot(state: ConnectionState, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(
        modifier
            .size(size)
            .background(Color.White, CircleShape)
            .padding(1.5.dp)
            .background(connectionColor(state), CircleShape),
    )
}

@Composable
fun ConnectionChip(state: ConnectionState, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = connectionColor(state)
    val base = modifier
    Surface(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        shape = CircleShape,
        color = c.copy(alpha = 0.16f),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(c, CircleShape))
            Text(
                connectionLabel(state),
                style = MaterialTheme.typography.labelMedium,
                color = Yu.Indigo,
            )
        }
    }
}

/** Speech bubble whose small corner points at the chibi (bottom-start). */
@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier, tailAtStart: Boolean = true) {
    val shape = if (tailAtStart) RoundedCornerShape(22.dp, 22.dp, 22.dp, 4.dp)
    else RoundedCornerShape(22.dp, 22.dp, 4.dp, 22.dp)
    SoftCard(modifier, shape = shape, elevation = 5.dp) {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = Yu.Indigo,
        )
    }
}

private val es = Locale("es", "ES")

/** "ahora", "hace 5 min", "hace 3 h", "ayer", "hace 4 días", or "12 oct". */
fun formatRelativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return ""
    val diff = (now - millis).coerceAtLeast(0L)
    val min = diff / 60_000L
    val hours = min / 60
    val days = hours / 24
    return when {
        min < 1 -> "ahora"
        min < 60 -> "hace $min min"
        hours < 24 -> "hace $hours h"
        days == 1L -> "ayer"
        days < 7 -> "hace $days días"
        else -> SimpleDateFormat("d MMM", es).format(Date(millis))
    }
}
