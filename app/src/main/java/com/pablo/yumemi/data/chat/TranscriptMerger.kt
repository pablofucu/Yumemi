package com.pablo.yumemi.data.chat

import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role

/** Merges the server transcript with locally stored messages (which keep reasoning/tool detail). */
object TranscriptMerger {

    private class Turn(val user: ChatMessage?, val assistant: ChatMessage?)

    private fun turns(list: List<ChatMessage>): List<Turn> {
        val out = mutableListOf<Turn>()
        var i = 0
        while (i < list.size) {
            val m = list[i]
            if (m.role == Role.USER) {
                val next = list.getOrNull(i + 1)
                if (next != null && next.role == Role.ASSISTANT) {
                    out.add(Turn(m, next))
                    i += 2
                } else {
                    out.add(Turn(m, null))
                    i += 1
                }
            } else {
                out.add(Turn(null, m))
                i += 1
            }
        }
        return out
    }

    private fun text(m: ChatMessage): String =
        m.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }

    private fun norm(m: ChatMessage): String = text(m).trim().replace(Regex("\\s+"), " ")

    private fun textLen(m: ChatMessage): Int = text(m).length

    private fun hasDetail(m: ChatMessage): Boolean =
        m.parts.any { it !is MessagePart.Text }

    private fun pickAssistant(server: ChatMessage?, local: ChatMessage?): ChatMessage? {
        if (local == null) return server
        if (server == null) return local
        if (!local.isStreaming && text(local).isBlank() && text(server).isNotBlank() && hasDetail(local)) {
            // Detail survived locally but the answer never reached the stream: keep both.
            return local.copy(parts = local.parts + MessagePart.Text(text(server)))
        }
        val useLocal = local.isStreaming || hasDetail(local) || textLen(local) >= textLen(server)
        return if (useLocal) local else server.copy(id = local.id)
    }

    fun merge(server: List<ChatMessage>?, local: List<ChatMessage>): List<ChatMessage> {
        if (server == null) return local
        val st = turns(server)
        val lt = turns(local)
        val used = BooleanArray(lt.size)
        var cursor = 0
        val out = mutableListOf<ChatMessage>()

        for (s in st) {
            var idx = -1
            val su = s.user
            if (su != null) {
                val key = norm(su)
                for (j in cursor until lt.size) {
                    val lu = lt[j].user
                    if (!used[j] && lu != null && norm(lu) == key) {
                        idx = j
                        break
                    }
                }
            }
            if (idx >= 0) {
                // Local turns the server does not know yet and that precede this one keep their place;
                // left for the tail they would show up after newer messages.
                for (j in cursor until idx) {
                    if (!used[j]) {
                        used[j] = true
                        lt[j].user?.let { out.add(it) }
                        lt[j].assistant?.let { out.add(it) }
                    }
                }
                used[idx] = true
                cursor = idx + 1
                val l = lt[idx]
                out.add(l.user ?: su!!)
                pickAssistant(s.assistant, l.assistant)?.let { out.add(it) }
            } else {
                s.user?.let { out.add(it) }
                s.assistant?.let { out.add(it) }
            }
        }
        for (j in lt.indices) {
            if (!used[j]) {
                lt[j].user?.let { out.add(it) }
                lt[j].assistant?.let { out.add(it) }
            }
        }
        return out
    }
}
