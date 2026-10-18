package com.pablo.yumemi

import com.pablo.yumemi.data.chat.ThreadActivityRules
import com.pablo.yumemi.domain.ActivityKind
import com.pablo.yumemi.domain.ApprovalStatus
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.domain.ToolStatus
import com.pablo.yumemi.ui.chat.plainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadActivityTest {

    private val now = 1_000_000_000L

    private fun user(text: String) = ChatMessage("u", Role.USER, listOf(MessagePart.Text(text)), 0)
    private fun assistant(vararg parts: MessagePart) = ChatMessage("a", Role.ASSISTANT, parts.toList(), 0)
    private fun tool(args: String, status: ToolStatus = ToolStatus.OK) =
        MessagePart.ToolCall("c", "terminal", args, null, status, null)

    @Test
    fun transcriptEndingInUserMessageIsThinkingWhileRecent() {
        val msgs = listOf(user("hola"))
        assertEquals(ActivityKind.THINKING, ThreadActivityRules.fromTranscript(msgs, now - 30_000, now)?.kind)
        assertNull(ThreadActivityRules.fromTranscript(msgs, now - ThreadActivityRules.USER_WAIT_MS, now))
    }

    @Test
    fun transcriptEndingInToolCallIsTerminalWithItsCommand() {
        val msgs = listOf(user("haz backup"), assistant(tool("""{"command":"rsync -av /data /mnt\nmore"}""")))
        val a = ThreadActivityRules.fromTranscript(msgs, now - 5 * 60_000, now)
        assertEquals(ActivityKind.TERMINAL, a?.kind)
        assertEquals("rsync -av /data /mnt", a?.detail)
        assertNull(ThreadActivityRules.fromTranscript(msgs, now - ThreadActivityRules.TOOL_WAIT_MS, now))
    }

    @Test
    fun transcriptEndingInAnswerIsIdle() {
        val msgs = listOf(user("hola"), assistant(tool("ls"), MessagePart.Text("Hecho")))
        assertNull(ThreadActivityRules.fromTranscript(msgs, now, now))
    }

    @Test
    fun liveTurnActivity() {
        val approval = MessagePart.Approval("r", "run", "rm -rf x", "", listOf("once", "deny"), ApprovalStatus.PENDING, null)
        assertEquals(ActivityKind.WAITING, ThreadActivityRules.fromLiveTurn(assistant(approval)).kind)
        assertEquals(ActivityKind.TERMINAL, ThreadActivityRules.fromLiveTurn(assistant(tool("ls -la", ToolStatus.RUNNING))).kind)
        assertEquals(ActivityKind.WRITING, ThreadActivityRules.fromLiveTurn(assistant(MessagePart.Text("Ho"))).kind)
        assertEquals(ActivityKind.THINKING, ThreadActivityRules.fromLiveTurn(assistant()).kind)
    }

    @Test
    fun snippetFlattensMarkdownAndFallsBackToTools() {
        val m = assistant(MessagePart.Text("# Título\n\n- **uno**\n- `dos`"))
        assertEquals("Título uno dos", ThreadActivityRules.snippetOf(m))
        assertEquals("› ls -la", ThreadActivityRules.snippetOf(assistant(tool("ls -la"))))
    }

    @Test
    fun copiedTextDropsMarkdownMarks() {
        assertEquals(
            "Paso 1\nDeja reposar 20 minutos y usa ls\nshoyu 50ml",
            plainText("## Paso 1\nDeja reposar **20 minutos** y usa `ls`\n```\nshoyu 50ml\n```"),
        )
    }
}
