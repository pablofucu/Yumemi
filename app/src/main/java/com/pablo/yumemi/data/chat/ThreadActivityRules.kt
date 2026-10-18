package com.pablo.yumemi.data.chat

import com.pablo.yumemi.data.remote.parseJsonObject
import com.pablo.yumemi.data.remote.str
import com.pablo.yumemi.domain.ActivityKind
import com.pablo.yumemi.domain.ApprovalStatus
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.domain.ThreadActivity
import com.pablo.yumemi.domain.ToolStatus

/** How a thread's state is read off its messages, for the activity shown in the thread list. */
object ThreadActivityRules {

    /** A turn that went quiet this long after the user spoke is assumed dead. */
    const val USER_WAIT_MS = 3 * 60_000L
    /** Tools (a long terminal command) can stay silent for longer. */
    const val TOOL_WAIT_MS = 10 * 60_000L

    /** Activity of a turn running in this app. */
    fun fromLiveTurn(message: ChatMessage): ThreadActivity {
        val parts = message.parts
        if (parts.any { it is MessagePart.Approval && it.status == ApprovalStatus.PENDING }) {
            return ThreadActivity(ActivityKind.WAITING)
        }
        val tool = parts.lastOrNull { it is MessagePart.ToolCall && it.status == ToolStatus.RUNNING } as? MessagePart.ToolCall
        if (tool != null) return ThreadActivity(ActivityKind.TERMINAL, commandOf(tool))
        if (parts.any { it is MessagePart.Subagent && it.status == ToolStatus.RUNNING }) {
            return ThreadActivity(ActivityKind.THINKING)
        }
        return if (parts.lastOrNull() is MessagePart.Text) ThreadActivity(ActivityKind.WRITING)
        else ThreadActivity(ActivityKind.THINKING)
    }

    /**
     * Activity guessed from a server transcript (a turn driven by another client). Hermes stores a
     * turn's answer when it ends, so a transcript ending in the user's message or in a tool call
     * means the agent is still working, as long as the session moved recently.
     */
    fun fromTranscript(messages: List<ChatMessage>, updatedAt: Long, now: Long): ThreadActivity? {
        val last = messages.lastOrNull() ?: return null
        val age = now - updatedAt
        if (last.role == Role.USER) {
            return if (age < USER_WAIT_MS) ThreadActivity(ActivityKind.THINKING) else null
        }
        if (age >= TOOL_WAIT_MS) return null
        return when (val p = last.parts.lastOrNull()) {
            is MessagePart.ToolCall -> ThreadActivity(ActivityKind.TERMINAL, commandOf(p))
            is MessagePart.Subagent -> ThreadActivity(ActivityKind.THINKING)
            else -> null
        }
    }

    /** The command a tool runs, for a one-line terminal preview. */
    fun commandOf(tool: MessagePart.ToolCall): String {
        val args = tool.argsPreview.trim()
        val fromJson = if (args.startsWith("{")) {
            parseJsonObject(args)?.str("command", "cmd", "code", "query", "path", "url", "file_path")
        } else {
            null
        }
        val line = (fromJson ?: args).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return line.ifEmpty { tool.tool }.take(120)
    }

    /** One-line snippet of a message for the list ("" when it has nothing to show). */
    fun snippetOf(m: ChatMessage): String {
        val text = m.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        val raw = text.ifBlank {
            (m.parts.lastOrNull { it is MessagePart.ToolCall } as? MessagePart.ToolCall)?.let { "› ${commandOf(it)}" }.orEmpty()
        }
        return raw
            .replace(Regex("(?m)^\\s{0,3}(#+\\s*|>\\s*|[-*+]\\s+)"), "")
            .replace(Regex("[*`]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(160)
    }
}
