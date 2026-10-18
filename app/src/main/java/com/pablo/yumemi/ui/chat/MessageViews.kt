package com.pablo.yumemi.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.pablo.yumemi.domain.ApprovalStatus
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.ToolStatus
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.components.softShadow
import com.pablo.yumemi.ui.theme.Yu
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.sprite.YumemiSprite

private val UserShape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
private val AssistantShape = RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)

@Composable
fun UserBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n\n") { it.text }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        MessageActions(text, alignEnd = true) {
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .softShadow(6.dp, UserShape)
                    .clip(UserShape)
                    .background(Yu.UserBubbleBrush)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A run of consecutive parts, grouped so tool calls collapse into one "Acciones" block. */
private sealed interface Block {
    data class Single(val part: MessagePart, val isLast: Boolean) : Block
    data class Actions(val parts: List<MessagePart>) : Block
}

private fun buildBlocks(parts: List<MessagePart>): List<Block> {
    val out = mutableListOf<Block>()
    var run = mutableListOf<MessagePart>()
    fun flush() {
        if (run.isNotEmpty()) { out += Block.Actions(run.toList()); run = mutableListOf() }
    }
    parts.forEachIndexed { i, p ->
        if (p is MessagePart.ToolCall || p is MessagePart.Subagent) run += p
        else { flush(); out += Block.Single(p, i == parts.lastIndex) }
    }
    flush()
    return out
}

@Composable
fun AssistantMessage(
    message: ChatMessage,
    detailsExpanded: Boolean,
    showInlineError: Boolean,
    modifier: Modifier = Modifier,
    onApprovalChoice: (requestId: String, choice: String) -> Unit = { _, _ -> },
) {
    val hasDetails = message.parts.any { it !is MessagePart.Text && it !is MessagePart.Approval }
    // Folded, the message is a plain bubble with a one-line summary of the work; unfolded (per
    // message, or for all of them from the stage counters) it becomes a timeline of every step.
    var showWork by remember(detailsExpanded) { mutableStateOf(detailsExpanded) }
    AnimatedContent(
        showWork && hasDetails,
        modifier.fillMaxWidth(),
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
        label = "messageMode",
    ) { timeline ->
        if (timeline) {
            TimelineMessage(message, showInlineError, onFold = { showWork = false }, onApprovalChoice)
        } else {
            BubbleMessage(message, hasDetails, showInlineError, onUnfold = { showWork = true }, onApprovalChoice)
        }
    }
}

@Composable
private fun BubbleMessage(
    message: ChatMessage,
    hasDetails: Boolean,
    showInlineError: Boolean,
    onUnfold: () -> Unit,
    onApprovalChoice: (requestId: String, choice: String) -> Unit,
) {
    val hasText = message.parts.any { it is MessagePart.Text && it.text.isNotBlank() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        MessageActions(message.copyableText()) {
            SoftCard(Modifier.widthIn(max = 360.dp), shape = AssistantShape, elevation = 6.dp) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (hasDetails) WorkSummary(message.parts, message.isStreaming, open = false, onToggle = onUnfold)
                    message.parts.forEach { p ->
                        when (p) {
                            is MessagePart.Text -> if (p.text.isNotBlank()) Markdown(content = p.text)
                            // Never folded: Hermes is waiting on the answer.
                            is MessagePart.Approval -> ApprovalCard(p) { choice -> onApprovalChoice(p.requestId, choice) }
                            else -> Unit
                        }
                    }
                    val waiting = message.parts.any { it is MessagePart.Approval && it.status == ApprovalStatus.PENDING }
                    if (message.isStreaming && !hasText && !waiting) TypingDots()
                    if (showInlineError && message.error != null) InlineError(message.error)
                }
            }
        }
    }
}

@Composable
private fun InlineError(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Yu.Danger)
}

private val RailX = 14.dp
private val RailTop = 34.dp

/**
 * Detailed view of one answer: her avatar on a vertical rail and every step in the order it
 * happened — reasoning, commentary, actions with their status and time, and the reply.
 */
@Composable
private fun TimelineMessage(
    message: ChatMessage,
    showInlineError: Boolean,
    onFold: () -> Unit,
    onApprovalChoice: (requestId: String, choice: String) -> Unit,
) {
    val blocks = remember(message.parts) { buildBlocks(message.parts) }
    val hasText = message.parts.any { it is MessagePart.Text && it.text.isNotBlank() }
    val running = message.parts.any {
        (it is MessagePart.ToolCall && it.status == ToolStatus.RUNNING) ||
            (it is MessagePart.Subagent && it.status == ToolStatus.RUNNING)
    }
    val state = when {
        message.error != null -> "con errores"
        running -> "trabajando"
        message.isStreaming -> "pensando"
        else -> "hecho"
    }
    MessageActions(message.copyableText(), Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        Yu.Outline,
                        start = Offset(RailX.toPx(), RailTop.toPx()),
                        end = Offset(RailX.toPx(), size.height - 4.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                },
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Yu.SkySoft)
                    .border(1.dp, Yu.Lilac.copy(alpha = 0.6f), CircleShape),
            ) {
                YumemiSprite(YumemiMood.IDLE, Modifier.fillMaxSize(), face = true)
            }
            Column(
                Modifier.padding(start = 38.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onFold)
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Yumemi · $state",
                        style = MaterialTheme.typography.labelMedium,
                        color = Yu.Muted,
                    )
                    Spacer(Modifier.width(2.dp))
                    Chevron(true)
                }
                blocks.forEach { b ->
                    when (b) {
                        is Block.Actions -> StepList(b.parts)
                        is Block.Single -> when (val p = b.part) {
                            is MessagePart.Text -> if (p.text.isNotBlank()) Markdown(content = p.text)
                            is MessagePart.Reasoning -> Aside(
                                p.text,
                                Yu.Lilac,
                                italic = true,
                                streaming = message.isStreaming && b.isLast,
                            )
                            is MessagePart.Commentary -> Aside(p.text, Yu.Sky, italic = false, streaming = false)
                            is MessagePart.Approval -> ApprovalCard(p) { choice -> onApprovalChoice(p.requestId, choice) }
                            else -> Unit
                        }
                    }
                }
                val waiting = message.parts.any { it is MessagePart.Approval && it.status == ApprovalStatus.PENDING }
                if (message.isStreaming && !hasText && !waiting && !running) TypingDots()
                if (showInlineError && message.error != null) InlineError(message.error)
            }
        }
    }
}

/** Reasoning or commentary: small text hanging off a coloured bar. */
@Composable
private fun Aside(text: String, bar: Color, italic: Boolean, streaming: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(bar, size = Size(2.dp.toPx(), size.height))
            }
            .padding(start = 10.dp),
    ) {
        if (streaming) ShimmerText("pensando…", fontSize = 12.sp)
        Text(
            text.ifBlank { "…" },
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal),
            color = Yu.Muted,
        )
    }
}

/** A run of actions as one card, one row per step. */
@Composable
private fun StepList(parts: List<MessagePart>) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.9f))
            .border(1.dp, Yu.Outline.copy(alpha = 0.7f), shape),
    ) {
        parts.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(color = Yu.Outline.copy(alpha = 0.6f))
            when (p) {
                is MessagePart.ToolCall -> StepRow(
                    title = p.tool,
                    preview = p.argsPreview.lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
                    status = p.status,
                    durationSec = p.durationSec,
                    details = listOfNotNull(
                        p.argsPreview.takeIf { it.isNotBlank() }?.let { "Argumentos" to it },
                        p.resultPreview?.takeIf { it.isNotBlank() }?.let { "Resultado" to it },
                    ),
                )
                is MessagePart.Subagent -> StepRow(
                    title = "Subagente",
                    preview = p.summary,
                    status = p.status,
                    durationSec = p.durationSec,
                    details = listOf("Resumen" to p.summary),
                    isSubagent = true,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun StepRow(
    title: String,
    preview: String?,
    status: ToolStatus,
    durationSec: Double?,
    details: List<Pair<String, String>>,
    isSubagent: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = details.isNotEmpty()) { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (status) {
                ToolStatus.RUNNING -> CircularProgressIndicator(
                    Modifier.size(15.dp), strokeWidth = 2.dp, color = Yu.Periwinkle,
                )
                ToolStatus.OK -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(17.dp), tint = Yu.Success)
                ToolStatus.FAILED -> Icon(Icons.Rounded.Error, null, Modifier.size(17.dp), tint = Yu.Danger)
            }
            if (isSubagent) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.SmartToy, null, Modifier.size(15.dp), tint = Yu.PeriwinkleDeep)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Yu.Indigo,
                    fontFamily = if (isSubagent) null else FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!preview.isNullOrBlank() && !expanded) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.labelSmall,
                        color = Yu.Muted,
                        fontFamily = if (isSubagent) null else FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (durationSec != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "%.1f s".format(durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = Yu.Muted,
                )
            }
            if (details.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Chevron(expanded)
            }
        }
        AnimatedVisibility(expanded && details.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                details.forEach { (label, body) ->
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Yu.Muted)
                    Text(
                        body.take(2000),
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Yu.Indigo)
                            .padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Yu.SkySoft,
                    )
                }
            }
        }
    }
}

/** One-line "Razonamiento · 3 acciones" toggle for the folded work of a message. */
@Composable
private fun WorkSummary(parts: List<MessagePart>, streaming: Boolean, open: Boolean, onToggle: () -> Unit) {
    val actions = parts.count { it is MessagePart.ToolCall || it is MessagePart.Subagent }
    val failed = parts.count {
        (it is MessagePart.ToolCall && it.status == ToolStatus.FAILED) ||
            (it is MessagePart.Subagent && it.status == ToolStatus.FAILED)
    }
    val running = parts.any {
        (it is MessagePart.ToolCall && it.status == ToolStatus.RUNNING) ||
            (it is MessagePart.Subagent && it.status == ToolStatus.RUNNING)
    }
    val label = listOfNotNull(
        "Razonamiento".takeIf { parts.any { it is MessagePart.Reasoning || it is MessagePart.Commentary } },
        when (actions) {
            0 -> null
            1 -> "1 acción"
            else -> "$actions acciones"
        },
        failed.takeIf { it > 0 }?.let { "$it con error" },
    ).joinToString(" · ")
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running || (streaming && !open)) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Yu.Periwinkle)
        } else {
            Icon(Icons.Rounded.Psychology, null, Modifier.size(15.dp), tint = Yu.Muted)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (failed > 0) Yu.Danger else Yu.Muted,
        )
        Spacer(Modifier.width(2.dp))
        Chevron(open)
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Icon(
        Icons.Rounded.ExpandMore, null,
        Modifier.size(20.dp).rotate(angle),
        tint = Yu.Muted,
    )
}

