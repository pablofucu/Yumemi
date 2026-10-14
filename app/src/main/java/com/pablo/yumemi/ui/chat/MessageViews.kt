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
import androidx.compose.material.icons.rounded.Build
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.ToolStatus
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.components.softShadow
import com.pablo.yumemi.ui.theme.Yu

private val UserShape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
private val AssistantShape = RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)

@Composable
fun UserBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n\n") { it.text }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
) {
    val blocks = remember(message.parts) { buildBlocks(message.parts) }
    val hasText = message.parts.any { it is MessagePart.Text && it.text.isNotBlank() }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        SoftCard(Modifier.widthIn(max = 360.dp), shape = AssistantShape, elevation = 6.dp) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                blocks.forEach { b ->
                    when (b) {
                        is Block.Actions -> ActionsGroup(b.parts, detailsExpanded)
                        is Block.Single -> when (val p = b.part) {
                            is MessagePart.Text ->
                                if (p.text.isNotBlank()) Markdown(content = p.text)
                            is MessagePart.Reasoning ->
                                ReasoningSection(p.text, message.isStreaming && b.isLast, detailsExpanded)
                            is MessagePart.Commentary ->
                                Text(
                                    p.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Yu.Muted,
                                )
                            else -> Unit
                        }
                    }
                }
                if (message.isStreaming && !hasText) TypingDots()
                if (showInlineError && message.error != null) {
                    Text(
                        message.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Yu.Danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningSection(text: String, streaming: Boolean, detailsExpanded: Boolean) {
    var expanded by remember(detailsExpanded) { mutableStateOf(detailsExpanded) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Yu.LilacSoft)
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Psychology, null, Modifier.size(18.dp), tint = Yu.PeriwinkleDeep)
            Spacer(Modifier.width(8.dp))
            Text(
                "Razonamiento",
                style = MaterialTheme.typography.labelLarge,
                color = Yu.PeriwinkleDeep,
            )
            if (streaming) {
                Spacer(Modifier.width(8.dp))
                ShimmerText("pensando…")
            }
            Spacer(Modifier.weight(1f))
            Chevron(expanded)
        }
        AnimatedVisibility(expanded) {
            Text(
                text.ifBlank { "…" },
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = Yu.Muted,
            )
        }
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

@Composable
private fun ActionsGroup(parts: List<MessagePart>, detailsExpanded: Boolean) {
    var open by remember(detailsExpanded) { mutableStateOf(detailsExpanded || parts.size <= 3) }
    val anyRunning = parts.any {
        (it is MessagePart.ToolCall && it.status == ToolStatus.RUNNING) ||
            (it is MessagePart.Subagent && it.status == ToolStatus.RUNNING)
    }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (anyRunning) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Yu.Periwinkle)
            } else {
                Icon(Icons.Rounded.Build, null, Modifier.size(14.dp), tint = Yu.Muted)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "Acciones · ${parts.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Yu.Muted,
            )
            Spacer(Modifier.width(2.dp))
            Chevron(open)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                parts.forEach { p ->
                    when (p) {
                        is MessagePart.ToolCall -> ActionRow(
                            title = p.tool,
                            status = p.status,
                            durationSec = p.durationSec,
                            details = listOfNotNull(
                                p.argsPreview.takeIf { it.isNotBlank() }?.let { "Argumentos" to it },
                                p.resultPreview?.takeIf { it.isNotBlank() }?.let { "Resultado" to it },
                            ),
                            detailsExpanded = detailsExpanded,
                        )
                        is MessagePart.Subagent -> ActionRow(
                            title = "Subagente: ${p.summary}",
                            status = p.status,
                            durationSec = p.durationSec,
                            details = listOf("Resumen" to p.summary),
                            detailsExpanded = detailsExpanded,
                            isSubagent = true,
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    status: ToolStatus,
    durationSec: Double?,
    details: List<Pair<String, String>>,
    detailsExpanded: Boolean,
    isSubagent: Boolean = false,
) {
    var expanded by remember(detailsExpanded) { mutableStateOf(detailsExpanded) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Yu.SkySoft.copy(alpha = 0.6f))
            .clickable(enabled = details.isNotEmpty()) { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (status) {
                ToolStatus.RUNNING -> CircularProgressIndicator(
                    Modifier.size(16.dp), strokeWidth = 2.dp, color = Yu.Periwinkle,
                )
                ToolStatus.OK -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = Yu.Success)
                ToolStatus.FAILED -> Icon(Icons.Rounded.Error, null, Modifier.size(18.dp), tint = Yu.Danger)
            }
            if (isSubagent) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.SmartToy, null, Modifier.size(16.dp), tint = Yu.PeriwinkleDeep)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = Yu.Indigo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                            .background(Color.White.copy(alpha = 0.85f))
                            .padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Yu.Indigo,
                    )
                }
            }
        }
    }
}
