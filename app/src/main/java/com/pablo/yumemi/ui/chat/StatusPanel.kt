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
import androidx.compose.foundation.layout.Spacer
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

/** Card tint per mood, matching the colour of her ribbon. */
private fun moodTint(mood: YumemiMood): Color = when (mood) {
    YumemiMood.IDLE -> Yu.LilacSoft
    YumemiMood.HAPPY -> Yu.GoldSoft
    YumemiMood.THINKING -> Color(0xFFE9E2FF)
    YumemiMood.WORKING -> Color(0xFFDDF5F1)
    YumemiMood.SURPRISED -> Yu.SuccessSoft
    YumemiMood.ERROR -> Yu.DangerSoft
    YumemiMood.SLEEPING -> Color(0xFFE8E9F6)
}

/**
 * Collapsible card under the top bar showing Yumemi's current state: her sprite plus what she
 * is doing. Collapsed it is a one-line strip; tapping it toggles.
 */
@Composable
fun StatusPanel(
    state: ChatUiState,
    talking: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = statusText(state, talking)
    val tint by animateColorAsState(moodTint(state.mood), tween(500), label = "statusTint")
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "statusChevron")

    SoftCard(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        color = tint,
        elevation = 3.dp,
    ) {
        Box(Modifier.clickable(onClick = onToggle)) {
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
                            .size(width = 118.dp, height = 132.dp)
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
                Spacer(Modifier.width(if (expanded) 8.dp else 18.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = if (expanded) 16.dp else 10.dp),
                ) {
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
                            maxLines = if (expanded) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
