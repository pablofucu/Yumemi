package com.pablo.yumemi

import com.pablo.yumemi.data.chat.EventMapper
import com.pablo.yumemi.data.chat.PartCodec
import com.pablo.yumemi.data.chat.SessionParser
import com.pablo.yumemi.data.chat.StreamEvent
import com.pablo.yumemi.data.chat.TranscriptMerger
import com.pablo.yumemi.data.chat.TranscriptParser
import com.pablo.yumemi.data.chat.TurnAccumulator
import com.pablo.yumemi.data.remote.SseEvent
import com.pablo.yumemi.data.remote.SseLineParser
import com.pablo.yumemi.data.settings.HostRules
import com.pablo.yumemi.domain.ApprovalStatus
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.domain.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLayerTest {

    private fun parseSse(raw: String): List<SseEvent> {
        val p = SseLineParser()
        val out = mutableListOf<SseEvent>()
        raw.split("\n").forEach { line -> p.feed(line)?.let { out.add(it) } }
        p.finish()?.let { out.add(it) }
        return out
    }

    @Test
    fun sseHandlesEventsMultilineCommentsAndDone() {
        val raw = ": keepalive\n" +
            "event: assistant.delta\ndata: {\"delta\":\"Hola\"}\n\n" +
            "data: line1\ndata: line2\n\n" +
            ": another comment\n\n" +
            "data: [DONE]\n\n"
        val evs = parseSse(raw)
        assertEquals(3, evs.size)
        assertEquals("assistant.delta", evs[0].event)
        assertEquals("{\"delta\":\"Hola\"}", evs[0].data)
        assertNull(evs[1].event)
        assertEquals("line1\nline2", evs[1].data)
        assertEquals("[DONE]", evs[2].data)
    }

    @Test
    fun sseFlushesUnterminatedEvent() {
        val evs = parseSse("data: x")
        assertEquals(1, evs.size)
    }

    @Test
    fun sessionTurnMapsToPartsInOrder() {
        val acc = TurnAccumulator()
        fun feed(event: String, data: String) =
            EventMapper.mapSession(SseEvent(event, data)).forEach { acc.apply(it) }
        feed("assistant.delta", "{\"delta\":\"Hola \"}")
        feed("assistant.delta", "{\"delta\":\"mundo\"}")
        feed("tool.started", "{\"tool\":\"terminal\",\"preview\":\"ls\"}")
        feed("tool.completed", "{\"tool\":\"terminal\",\"duration\":1.5,\"error\":false,\"preview\":\"a b\"}")
        feed("reasoning.delta", "{\"text\":\"pienso\"}")
        feed("assistant.delta", "{\"delta\":\"fin\"}")
        feed("mystery.event", "{}")
        feed("run.completed", "{}")
        assertTrue(acc.finished)
        assertEquals(4, acc.parts.size)
        assertEquals(MessagePart.Text("Hola mundo"), acc.parts[0])
        val tool = acc.parts[1] as MessagePart.ToolCall
        assertEquals(ToolStatus.OK, tool.status)
        assertEquals(1.5, tool.durationSec!!, 0.0001)
        assertEquals("a b", tool.resultPreview)
        assertEquals(MessagePart.Reasoning("pienso"), acc.parts[2])
        assertEquals(MessagePart.Text("fin"), acc.parts[3] )
    }

    @Test
    fun toolFailedAndRunFailed() {
        val acc = TurnAccumulator()
        EventMapper.mapSession(SseEvent("tool.started", "{\"tool\":\"web\"}")).forEach { acc.apply(it) }
        EventMapper.mapSession(SseEvent("tool.failed", "{\"tool\":\"web\"}")).forEach { acc.apply(it) }
        EventMapper.mapSession(SseEvent("run.failed", "{\"error\":\"boom\"}")).forEach { acc.apply(it) }
        assertEquals(ToolStatus.FAILED, (acc.parts[0] as MessagePart.ToolCall).status)
        assertEquals("boom", acc.error)
    }

    @Test
    fun chatCompletionsMapping() {
        val acc = TurnAccumulator(autoCompleteTools = true)
        fun feed(event: String?, data: String) =
            EventMapper.mapChatCompletions(SseEvent(event, data)).forEach { acc.apply(it) }
        feed(null, "{\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm\"}}]}")
        feed("hermes.tool.progress", "{\"tool\":\"terminal\",\"label\":\"ls\"}")
        feed(null, "{\"choices\":[{\"delta\":{\"content\":\"Listo\"}}]}")
        feed(null, "[DONE]")
        assertEquals(3, acc.parts.size)
        assertEquals(ToolStatus.OK, (acc.parts[1] as MessagePart.ToolCall).status)
        assertTrue(acc.finished)
    }

    @Test
    fun transcriptParsingGroupsToolTurns() {
        val body = """{"messages":[
          {"role":"user","content":"hola"},
          {"role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"terminal","arguments":"{\"cmd\":\"ls\"}"}}]},
          {"role":"tool","tool_call_id":"c1","content":"file.txt"},
          {"role":"assistant","content":"Hecho","reasoning":"pensando"}
        ]}"""
        val msgs = TranscriptParser.parse(body)
        assertEquals(2, msgs.size)
        assertEquals(Role.USER, msgs[0].role)
        val parts = msgs[1].parts
        assertTrue(parts[0] is MessagePart.ToolCall)
        assertEquals("file.txt", (parts[0] as MessagePart.ToolCall).resultPreview)
        assertTrue(parts.any { it is MessagePart.Reasoning })
        assertTrue(parts.any { it is MessagePart.Text && it.text == "Hecho" })
    }

    @Test
    fun sessionListParsingIsDefensive() {
        val body = """{"sessions":[{"session_id":"a","title":"T","updated_at":1700000000},{"id":"b","created_at":"2024-01-01T00:00:00Z"},{"nothing":1}]}"""
        val list = SessionParser.parseList(body)
        assertEquals(2, list.size)
        assertEquals(1_700_000_000_000L, list[0].updatedAt)
        assertTrue(list[1].updatedAt > 0)
        assertEquals(1, SessionParser.parseList("""[{"id":"x"}]""").size)
    }

    @Test
    fun mergerPrefersLocalDetail() {
        val server = listOf(
            ChatMessage("s1", Role.USER, listOf(MessagePart.Text("hola")), 0),
            ChatMessage("s2", Role.ASSISTANT, listOf(MessagePart.Text("Hecho")), 0),
        )
        val local = listOf(
            ChatMessage("l1", Role.USER, listOf(MessagePart.Text("hola")), 1),
            ChatMessage("l2", Role.ASSISTANT, listOf(MessagePart.Reasoning("r"), MessagePart.Text("Hecho")), 2),
            ChatMessage("l3", Role.USER, listOf(MessagePart.Text("nuevo")), 3),
        )
        val merged = TranscriptMerger.merge(server, local)
        assertEquals(listOf("l1", "l2", "l3"), merged.map { it.id })
    }

    @Test
    fun mergerKeepsLocalOnlyTurnsBeforeLaterServerTurns() {
        val server = listOf(
            ChatMessage("s1", Role.USER, listOf(MessagePart.Text("b")), 0),
            ChatMessage("s2", Role.ASSISTANT, listOf(MessagePart.Text("rb")), 0),
        )
        val local = listOf(
            ChatMessage("l1", Role.USER, listOf(MessagePart.Text("a")), 1),
            ChatMessage("l2", Role.ASSISTANT, listOf(MessagePart.Text("ra")), 2),
            ChatMessage("l3", Role.USER, listOf(MessagePart.Text("b")), 3),
            ChatMessage("l4", Role.ASSISTANT, listOf(MessagePart.Text("rb")), 4),
        )
        val merged = TranscriptMerger.merge(server, local)
        assertEquals(listOf("l1", "l2", "l3", "l4"), merged.map { it.id })
    }

    @Test
    fun partCodecRoundTrip() {
        val parts = listOf(
            MessagePart.Text("a"),
            MessagePart.Reasoning("b"),
            MessagePart.Commentary("c"),
            MessagePart.ToolCall("1", "t", "args", "res", ToolStatus.OK, 2.0),
            MessagePart.Subagent("2", "sum", ToolStatus.FAILED, null),
        )
        assertEquals(parts, PartCodec.decode(PartCodec.encode(parts)))
    }

    @Test
    fun hostRules() {
        assertFalse(HostRules.isInsecure("http://100.101.102.103:8642"))
        assertFalse(HostRules.isInsecure("http://box.tail1234.ts.net:8642"))
        assertFalse(HostRules.isInsecure("http://192.168.1.5:8642"))
        assertFalse(HostRules.isInsecure("http://172.20.0.1"))
        assertFalse(HostRules.isInsecure("http://localhost:8642"))
        assertFalse(HostRules.isInsecure("https://example.com"))
        assertTrue(HostRules.isInsecure("http://35.1.2.3:8642"))
        assertTrue(HostRules.isInsecure("http://172.32.0.1"))
        assertTrue(HostRules.isInsecure("http://100.128.0.1"))
        assertEquals("http://100.64.0.1:8642", HostRules.normalizeBaseUrl("100.64.0.1:8642/"))
    }

    @Test
    fun approvalRequestBecomesCardAndExpiresWhenTurnEnds() {
        val ev = EventMapper.mapSession(
            SseEvent(
                "approval.request",
                "{\"event\":\"approval.request\",\"run_id\":\"run_1\",\"request_id\":\"r1\"," +
                    "\"command\":\"rm -rf build\",\"description\":\"recursive delete\",\"choices\":[\"once\",\"deny\"]}",
            ),
        ).single() as StreamEvent.ApprovalRequested
        assertEquals("run_1", ev.runId)
        assertEquals(listOf("once", "deny"), ev.choices)

        val acc = TurnAccumulator()
        acc.apply(ev)
        val card = acc.parts.single() as MessagePart.Approval
        assertEquals(ApprovalStatus.PENDING, card.status)
        assertEquals(card, PartCodec.decode(PartCodec.encode(acc.parts)).single().let { (it as MessagePart.Approval).copy(status = ApprovalStatus.PENDING) })

        acc.apply(StreamEvent.Completed)
        assertEquals(ApprovalStatus.EXPIRED, (acc.parts.single() as MessagePart.Approval).status)
    }

    @Test
    fun errorEventFailsAndCompletedContentFillsMissingAnswer() {
        val failed = EventMapper.mapSession(SseEvent("error", "{\"message\":\"boom\"}")).single()
        assertEquals(StreamEvent.Failed("boom"), failed)

        val acc = TurnAccumulator()
        acc.apply(StreamEvent.ToolStarted("t1", "terminal", "ls"))
        EventMapper.mapSession(SseEvent("assistant.completed", "{\"content\":\"Listo\"}")).forEach { acc.apply(it) }
        assertEquals("Listo", (acc.parts.last() as MessagePart.Text).text)
    }

    @Test
    fun sseRecordsIdPerEvent() {
        val evs = parseSse("id: 7\ndata: {\"a\":1}\n\ndata: x\n\n: keepalive\nid: 9\nevent: foo\ndata: y\n\n")
        assertEquals(3, evs.size)
        assertEquals("7", evs[0].id)
        assertNull(evs[1].id)
        assertEquals("9", evs[2].id)
        assertEquals("foo", evs[2].event)
    }

    @Test
    fun runCompletedCarriesOutputAsFinalText() {
        val evs = EventMapper.mapSession(
            SseEvent(null, "{\"event\":\"run.completed\",\"output\":\"Listo\",\"seq\":4}", "4"),
        )
        assertEquals(listOf(StreamEvent.FinalText("Listo"), StreamEvent.Completed), evs)

        val acc = TurnAccumulator()
        evs.forEach { acc.apply(it) }
        assertTrue(acc.finished)
        assertEquals(MessagePart.Text("Listo"), acc.parts.single())

        // Streamed text wins over the final output.
        val acc2 = TurnAccumulator()
        acc2.apply(StreamEvent.TextDelta("Hola"))
        evs.forEach { acc2.apply(it) }
        assertEquals(MessagePart.Text("Hola"), acc2.parts.single())

        assertEquals(
            listOf(StreamEvent.Completed),
            EventMapper.mapSession(SseEvent(null, "{\"event\":\"run.completed\",\"output\":\"\"}")),
        )
    }

    @Test
    fun runInterruptedAndTruncatedAndReasoning() {
        assertEquals(
            StreamEvent.Failed("apagado"),
            EventMapper.mapSession(SseEvent(null, "{\"event\":\"run.interrupted\",\"error\":\"apagado\"}")).single(),
        )
        assertEquals(
            StreamEvent.Failed("Hermes se reinició y cortó la respuesta"),
            EventMapper.mapSession(SseEvent(null, "{\"event\":\"run.interrupted\"}")).single(),
        )
        assertTrue(
            EventMapper.mapSession(SseEvent(null, "{\"event\":\"replay.truncated\"}")).single() is StreamEvent.Unknown,
        )
        assertEquals(
            StreamEvent.ReasoningDelta("pienso"),
            EventMapper.mapSession(SseEvent(null, "{\"event\":\"reasoning.available\",\"text\":\"pienso\"}")).single(),
        )
    }
}
