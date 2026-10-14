package com.pablo.yumemi.data.remote

import okio.BufferedSource

data class SseEvent(val event: String?, val data: String, val id: String? = null)

/** Incremental Server-Sent-Events parser working line by line. */
class SseLineParser {
    private var event: String? = null
    private var id: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feed one line (without line terminator). Returns an event when a blank line dispatches one. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null // comment / keepalive
        val idx = line.indexOf(':')
        val field: String
        var value: String
        if (idx < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, idx)
            value = line.substring(idx + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        }
        when (field) {
            "event" -> event = value
            "id" -> id = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            else -> Unit // retry and unknown fields are ignored
        }
        return null
    }

    /** Call at end of stream to flush an unterminated event. */
    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        if (!hasData) {
            event = null
            id = null
            return null
        }
        val ev = SseEvent(event, data.toString(), id)
        event = null
        id = null
        data.setLength(0)
        hasData = false
        return ev
    }
}

object SseParser {
    suspend fun read(source: BufferedSource, onEvent: suspend (SseEvent) -> Unit) {
        val parser = SseLineParser()
        while (true) {
            val line = source.readUtf8Line() ?: break
            val ev = parser.feed(line)
            if (ev != null) onEvent(ev)
        }
        parser.finish()?.let { onEvent(it) }
    }
}
