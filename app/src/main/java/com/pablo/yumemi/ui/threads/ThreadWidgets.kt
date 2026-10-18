package com.pablo.yumemi.ui.threads

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiNature
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Icecream
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pablo.yumemi.domain.ActivityKind
import com.pablo.yumemi.domain.ChatThread
import com.pablo.yumemi.domain.ThreadActivity
import com.pablo.yumemi.ui.theme.Yu
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.sin

private val Pink = Color(0xFFF4A7C6)
private val SkyDeep = Color(0xFF3C7FA8)
private val GoldDeep = Color(0xFFC99A2E)
private val Peach = Color(0xFFFFEEDD)
private val PeachDeep = Color(0xFFD9822B)

/** Background and icon colour of a thread's avatar, picked from its id so it never changes. */
private val AVATAR_COLORS = listOf(
    Yu.LilacSoft to Yu.PeriwinkleDeep,
    Yu.SkySoft to SkyDeep,
    Yu.GoldSoft to GoldDeep,
    Yu.SuccessSoft to Yu.Success,
    Yu.DangerSoft to Yu.Danger,
    Peach to PeachDeep,
)

private val AVATAR_ICONS = listOf(
    Icons.Rounded.Star, Icons.Rounded.Favorite, Icons.Rounded.Cloud, Icons.Rounded.LocalFlorist,
    Icons.Rounded.Pets, Icons.Rounded.Bedtime, Icons.Rounded.WbSunny, Icons.Rounded.Icecream,
    Icons.Rounded.Cake, Icons.Rounded.MusicNote, Icons.Rounded.Spa, Icons.Rounded.EmojiNature,
)

/** Accent of an activity: card border, avatar and text. */
internal fun activityColor(kind: ActivityKind): Color = when (kind) {
    ActivityKind.THINKING, ActivityKind.WRITING -> Yu.Periwinkle
    ActivityKind.TERMINAL -> Yu.Success
    ActivityKind.WAITING -> GoldDeep
}

private fun activitySoft(kind: ActivityKind): Color = when (kind) {
    ActivityKind.THINKING, ActivityKind.WRITING -> Yu.LilacSoft
    ActivityKind.TERMINAL -> Yu.SuccessSoft
    ActivityKind.WAITING -> Yu.GoldSoft
}

/** Round avatar: a cute icon while idle, an animated one while the thread is busy. */
@Composable
internal fun ThreadAvatar(thread: ChatThread, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val activity = thread.activity
    if (activity == null) {
        val h = thread.id.hashCode().absoluteValue
        val (bg, fg) = AVATAR_COLORS[h % AVATAR_COLORS.size]
        val icon = AVATAR_ICONS[(h / AVATAR_COLORS.size) % AVATAR_ICONS.size]
        Box(modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(size * 0.48f), tint = fg)
        }
    } else {
        BusyAvatar(activity.kind, modifier, size)
    }
}

@Composable
private fun BusyAvatar(kind: ActivityKind, modifier: Modifier, size: Dp) {
    val color = activityColor(kind)
    val t = rememberInfiniteTransition(label = "busy-avatar")
    val pulse by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse",
    )
    val bob by t.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "bob",
    )
    val wiggle by t.animateFloat(
        -22f, 16f, infiniteRepeatable(tween(320), RepeatMode.Reverse), label = "wiggle",
    )
    val twinkle by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "twinkle",
    )
    Box(
        modifier
            .size(size)
            .drawBehind {
                // A soft ring that keeps growing out of the avatar.
                drawCircle(color.copy(alpha = 0.45f * (1f - pulse)), radius = this.size.minDimension / 2 * (1f + 0.35f * pulse))
            }
            .background(activitySoft(kind), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (kind) {
            ActivityKind.WRITING -> Icons.Rounded.Edit
            ActivityKind.THINKING -> Icons.Rounded.Psychology
            ActivityKind.TERMINAL -> Icons.Rounded.Build
            ActivityKind.WAITING -> Icons.Rounded.NotificationsActive
        }
        Icon(
            icon,
            null,
            Modifier
                .size(size * 0.48f)
                .graphicsLayer {
                    if (kind == ActivityKind.TERMINAL) {
                        transformOrigin = TransformOrigin(0.7f, 0.7f)
                        rotationZ = wiggle
                    } else if (kind == ActivityKind.WAITING) {
                        rotationZ = bob * 14f
                    } else {
                        translationY = bob * 2.5.dp.toPx()
                    }
                },
            tint = color,
        )
        if (kind == ActivityKind.WRITING || kind == ActivityKind.THINKING) {
            Icon(
                Icons.Rounded.AutoAwesome,
                null,
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .size(size * 0.3f)
                    .graphicsLayer {
                        alpha = twinkle
                        scaleX = 0.6f + 0.4f * twinkle
                        scaleY = 0.6f + 0.4f * twinkle
                    },
                tint = Yu.Gold,
            )
        }
    }
}

/** The line under the title while the thread is busy. */
@Composable
internal fun ActivityLine(activity: ThreadActivity, modifier: Modifier = Modifier) {
    val color = activityColor(activity.kind)
    when (activity.kind) {
        ActivityKind.WRITING, ActivityKind.THINKING -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (activity.kind == ActivityKind.WRITING) "Yumemi escribe" else "Yumemi está pensando",
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
            BouncingDots(Modifier.padding(start = 6.dp))
        }
        ActivityKind.TERMINAL -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "~ ${activity.detail}",
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BlinkingCursor(color, Modifier.padding(start = 2.dp))
        }
        ActivityKind.WAITING -> Text(
            "Te necesita: hay un permiso pendiente",
            modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Pink, lilac and sky dots hopping one after the other. */
@Composable
internal fun BouncingDots(modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "dots").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "t",
    )
    Row(modifier.height(12.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        listOf(Pink, Yu.Lilac, Yu.Sky).forEachIndexed { i, c ->
            val p = ((t - i * 0.12f) % 1f + 1f) % 1f
            val lift = if (p < 0.3f) sin(p / 0.3f * PI).toFloat() else 0f
            Box(
                Modifier
                    .graphicsLayer { translationY = -lift * 5.dp.toPx() }
                    .size(6.dp)
                    .background(c, CircleShape),
            )
        }
    }
}

@Composable
private fun BlinkingCursor(color: Color, modifier: Modifier = Modifier) {
    val v by rememberInfiniteTransition(label = "cursor").animateFloat(
        0f, 1f, infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse), label = "blink",
    )
    Box(
        modifier
            .graphicsLayer { alpha = if (v > 0.5f) 1f else 0f }
            .width(6.dp)
            .height(12.dp)
            .background(color, RoundedCornerShape(2.dp)),
    )
}

/** Little pastel tag ("Telegram", "CLI"...) for sessions that live outside the app. */
@Composable
internal fun SourceTag(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier
            .background(Yu.SkySoft, CircleShape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = SkyDeep,
        maxLines = 1,
    )
}

/** Sources that are this app (or a generic API client): no tag for them. */
private val OWN_SOURCES = setOf("api_server", "api", "app", "yumemi", "http", "webui", "web")

/** Label for a session's source, or null when it is not worth showing. */
internal fun sourceLabel(source: String?): String? {
    val s = source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (s in OWN_SOURCES) return null
    return when (s) {
        "cli", "terminal", "tui" -> "CLI"
        else -> s.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

