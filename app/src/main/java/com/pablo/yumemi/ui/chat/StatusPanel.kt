package com.pablo.yumemi.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pablo.yumemi.domain.ApprovalStatus
import com.pablo.yumemi.domain.ChatUiState
import com.pablo.yumemi.domain.ConnectionState
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.domain.ToolStatus
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.components.connectionLabel
import com.pablo.yumemi.ui.sprite.YumemiSprite
import com.pablo.yumemi.ui.theme.Yu
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import com.pablo.yumemi.ui.components.softShadow
import kotlinx.coroutines.delay

/** What Yumemi is doing right now, in words. */
private data class StatusText(val title: String, val detail: String?, val mono: Boolean = false)

private fun statusText(state: ChatUiState, talking: Boolean): StatusText {
    val last = state.messages.lastOrNull()?.takeIf { it.role == Role.ASSISTANT }
    val parts = last?.parts.orEmpty()
    val actions = parts.count { it is MessagePart.ToolCall || it is MessagePart.Subagent }
    val actionsLabel = when (actions) {
        0 -> null
        1 -> "1 acción"
        else -> "$actions acciones"
    }
    parts.lastOrNull { it is MessagePart.Approval && it.status == ApprovalStatus.PENDING }?.let {
        return StatusText("Necesito tu permiso", (it as MessagePart.Approval).command.lineSequence().firstOrNull()?.trim(), mono = true)
    }
    return when (state.mood) {
        YumemiMood.WORKING -> {
            when (val running = parts.lastOrNull { it.isRunning() }) {
                is MessagePart.ToolCall -> StatusText(
                    "Usando ${running.tool}",
                    running.argsPreview.lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
                    mono = true,
                )
                is MessagePart.Subagent -> StatusText("Trabajando en una subtarea", running.summary)
                else -> StatusText("Trabajando…", actionsLabel)
            }
        }
        YumemiMood.THINKING -> StatusText(
            "Pensando…",
            parts.lastOrNull { it is MessagePart.Reasoning || it is MessagePart.Commentary }
                ?.let { (it as? MessagePart.Reasoning)?.text ?: (it as MessagePart.Commentary).text }
                ?.lines()?.lastOrNull { it.isNotBlank() }?.trim()
                ?: actionsLabel,
        )
        YumemiMood.SURPRISED -> StatusText("¡Oh! Una cosa más…", actionsLabel)
        YumemiMood.HAPPY -> StatusText("¡Hecho!", actionsLabel?.let { "$it en esta respuesta" })
        YumemiMood.ERROR -> StatusText("¡Ay! Algo ha salido mal", state.error ?: last?.error)
        YumemiMood.SLEEPING -> StatusText("Zzz…", connectionLabel(state.connection))
        YumemiMood.IDLE -> when {
            talking || state.isSending -> StatusText("Escribiendo…", actionsLabel)
            state.connection is ConnectionState.Connected -> StatusText("Lista para ayudarte", null)
            else -> StatusText("Aquí estoy", connectionLabel(state.connection))
        }
    }
}

private fun MessagePart.isRunning(): Boolean =
    (this is MessagePart.ToolCall && status == ToolStatus.RUNNING) ||
        (this is MessagePart.Subagent && status == ToolStatus.RUNNING)

/** Card tints per mood (gradient start/end), matching the colour of her ribbon. */
private fun moodTints(mood: YumemiMood): Pair<Color, Color> = when (mood) {
    YumemiMood.IDLE -> Yu.LilacSoft to Yu.SkySoft
    YumemiMood.HAPPY -> Yu.GoldSoft to Yu.LilacSoft
    YumemiMood.THINKING -> Color(0xFFE9E2FF) to Yu.SkySoft
    YumemiMood.WORKING -> Color(0xFFDDF5F1) to Color(0xFFE9E2FF)
    YumemiMood.SURPRISED -> Yu.SuccessSoft to Yu.SkySoft
    YumemiMood.ERROR -> Yu.DangerSoft to Yu.LilacSoft
    YumemiMood.SLEEPING -> Color(0xFFE8E9F6) to Yu.LilacSoft
}

private val BubbleShape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)

/** Seconds since [start], ticking once a second while [running]; null otherwise. */
@Composable
private fun rememberElapsed(start: Long?, running: Boolean): Long? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start, running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return start?.takeIf { running }?.let { ((now - it) / 1000).coerceAtLeast(0) }
}

private fun formatElapsed(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

/**
 * The "stage" under the top bar: Yumemi with a speech bubble saying what she is doing, plus
 * counters for the current turn. Collapsed it is a one-line strip; tapping it toggles. The
 * counters open the detailed (timeline) view of the messages.
 */
@Composable
fun StatusPanel(
    state: ChatUiState,
    talking: Boolean,
    expanded: Boolean,
    detailsOn: Boolean,
    onToggle: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = statusText(state, talking)
    val (tintA, tintB) = moodTints(state.mood)
    val top by animateColorAsState(tintA, tween(500), label = "statusTintA")
    val bottom by animateColorAsState(tintB, tween(500), label = "statusTintB")
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "statusChevron")

    val last = state.messages.lastOrNull()?.takeIf { it.role == Role.ASSISTANT }
    val parts = last?.parts.orEmpty()
    val done = parts.count { it.statusOf() == ToolStatus.OK }
    val running = parts.count { it.statusOf() == ToolStatus.RUNNING }
    val failed = parts.count { it.statusOf() == ToolStatus.FAILED }
    val turnStart = state.messages.lastOrNull { it.role == Role.USER }?.createdAt
    val elapsed = rememberElapsed(turnStart, last?.isStreaming == true)

    SoftCard(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        elevation = 3.dp,
    ) {
        Box(
            Modifier
                .background(Brush.linearGradient(listOf(top, bottom)))
                .clickable(onClick = onToggle)
                .animateContentSize(),
        ) {
            // A soft light disc in the corner, like a stage spotlight.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 36.dp, y = (-36).dp)
                    .size(120.dp)
                    .background(Color.White.copy(alpha = 0.45f), CircleShape),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    // Show her from the top down; the card clips her below the waist.
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(width = 112.dp, height = 132.dp)
                            .clipToBounds(),
                    ) {
                        YumemiSprite(
                            state.mood,
                            Modifier
                                .wrapContentSize(Alignment.TopStart, unbounded = true)
                                // Frame is 528:576 with her head ~2/3 to the right; shift it left.
                                .offset(x = (-40).dp)
                                .requiredSize(width = 158.dp, height = 172.dp),
                            talking = talking,
                        )
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(
                            start = if (expanded) 0.dp else 18.dp,
                            top = if (expanded) 14.dp else 10.dp,
                            bottom = if (expanded) 14.dp else 10.dp,
                        ),
                ) {
                    if (expanded) {
                        // Her speech bubble, tail pointing back at her.
                        Column(
                            Modifier
                                .softShadow(3.dp, BubbleShape)
                                .clip(BubbleShape)
                                .background(Color.White)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) { StatusLines(status, expanded = true) }
                        if (done + running + failed > 0 || elapsed != null) {
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (done > 0) CounterChip(Icons.Rounded.Check, "$done", Yu.Success, onDetails)
                                if (running > 0) CounterChip(null, "$running", Yu.PeriwinkleDeep, onDetails, spinning = true)
                                if (failed > 0) CounterChip(Icons.Rounded.Close, "$failed", Yu.Danger, onDetails)
                                if (elapsed != null) CounterChip(Icons.Rounded.Schedule, formatElapsed(elapsed), Yu.Muted, onDetails)
                                CounterChip(
                                    Icons.Rounded.Timeline,
                                    null,
                                    if (detailsOn) Color.White else Yu.PeriwinkleDeep,
                                    onDetails,
                                    background = if (detailsOn) Yu.Periwinkle else Color.White,
                                    description = if (detailsOn) "Ocultar detalles" else "Ver detalles",
                                )
                            }
                        }
                    } else {
                        StatusLines(status, expanded = false)
                    }
                }
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Plegar" else "Desplegar",
                    tint = Yu.Muted,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .rotate(chevron),
                )
            }
        }
    }
}

@Composable
private fun StatusLines(status: StatusText, expanded: Boolean) {
    AnimatedContent(
        status.title,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
        label = "statusTitle",
    ) { title ->
        Text(
            title,
            style = if (expanded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            color = Yu.Indigo,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (status.detail != null) {
        Text(
            status.detail,
            Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Yu.Muted,
            fontFamily = if (status.mono) FontFamily.Monospace else null,
            maxLines = if (expanded) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small white pill with an icon and/or a count; all of them open the detailed view. */
@Composable
private fun CounterChip(
    icon: ImageVector?,
    label: String?,
    tint: Color,
    onClick: () -> Unit,
    spinning: Boolean = false,
    background: Color = Color.White,
    description: String? = null,
) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (spinning) {
            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = tint)
        } else if (icon != null) {
            Icon(icon, description, Modifier.size(13.dp), tint = tint)
        }
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun MessagePart.statusOf(): ToolStatus? = when (this) {
    is MessagePart.ToolCall -> status
    is MessagePart.Subagent -> status
    else -> null
}
