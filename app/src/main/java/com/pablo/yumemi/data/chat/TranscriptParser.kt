package com.pablo.yumemi.data.chat

import com.pablo.yumemi.data.remote.arr
import com.pablo.yumemi.data.remote.contentText
import com.pablo.yumemi.data.remote.parseJson
import com.pablo.yumemi.data.remote.parseJsonObject
import com.pablo.yumemi.data.remote.str
import com.pablo.yumemi.data.remote.time
import com.pablo.yumemi.data.remote.truncated
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.domain.ToolStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses GET /api/sessions/{id}/messages defensively. Consecutive assistant/tool messages
 * are grouped into a single assistant [ChatMessage], like the streamed turn looks.
 */
object TranscriptParser {

    fun parse(body: String): List<ChatMessage> {
        val root = parseJson(body) ?: return emptyList()
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root.arr("messages", "data", "items", "results") ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }

        val result = mutableListOf<ChatMessage>()
        var curParts: MutableList<MessagePart>? = null
        var curId = ""
        var curTime = 0L

        fun flush() {
            val p = curParts
            if (p != null && p.isNotEmpty()) {
                result.add(ChatMessage(id = curId, role = Role.ASSISTANT, parts = p.toList(), createdAt = curTime))
            }
            curParts = null
        }

        for ((i, el) in array.withIndex()) {
            val o = el as? JsonObject ?: continue
            val role = o.str("role")?.lowercase() ?: continue
            val time = o.time("timestamp", "created_at", "createdAt", "time", "ts")
            val id = o.str("id", "message_id") ?: "srv-$i"
            when (role) {
                "user", "human" -> {
                    flush()
                    val text = contentText(o["content"]) ?: o.str("text")
                    if (!text.isNullOrBlank()) {
                        result.add(ChatMessage(id, Role.USER, listOf(MessagePart.Text(text)), time))
                    }
                }
                "assistant", "ai", "model" -> {
                    if (curParts == null) {
                        curParts = mutableListOf()
                        curId = id
                        curTime = time
                    }
                    val parts = curParts!!
                    val reasoning = o.str("reasoning", "reasoning_content", "thinking")
                    if (!reasoning.isNullOrBlank()) parts.add(MessagePart.Reasoning(reasoning))
                    val text = contentText(o["content"])
                    if (!text.isNullOrBlank()) parts.add(MessagePart.Text(text))
                    for (call in toolCalls(o["tool_calls"])) parts.add(call)
                }
                "tool", "function" -> {
                    if (curParts == null) {
                        curParts = mutableListOf()
                        curId = id
                        curTime = time
                    }
                    val parts = curParts!!
                    val callId = o.str("tool_call_id", "call_id")
                    val name = o.str("name", "tool_name", "tool")
                    val content = contentText(o["content"])?.truncated(600)
                    var idx = if (callId != null) parts.indexOfFirst { it is MessagePart.ToolCall && it.id == callId } else -1
                    if (idx < 0 && name != null) {
                        idx = parts.indexOfFirst {
                            it is MessagePart.ToolCall && it.tool == name && it.resultPreview == null
                        }
                    }
                    if (idx >= 0) {
                        parts[idx] = (parts[idx] as MessagePart.ToolCall).copy(resultPreview = content)
                    } else {
                        parts.add(
                            MessagePart.ToolCall(
                                id = callId ?: "srv-tool-$i",
                                tool = name ?: "herramienta",
                                argsPreview = "",
                                resultPreview = content,
                                status = ToolStatus.OK,
                                durationSec = null,
                            ),
                        )
                    }
                }
                else -> Unit // system etc.
            }
        }
        flush()
        return result
    }

    private fun toolCalls(e: JsonElement?): List<MessagePart.ToolCall> {
        val arr: JsonArray = when {
            e is JsonArray -> e
            e is JsonPrimitive && e.isString -> parseJson(e.content) as? JsonArray
            else -> null
        } ?: return emptyList()
        val out = mutableListOf<MessagePart.ToolCall>()
        for ((n, item) in arr.withIndex()) {
            val c = item as? JsonObject ?: continue
            val fn = (c["function"] as? JsonObject) ?: c
            val name = fn.str("name", "tool") ?: continue
            val argsEl = fn["arguments"] ?: fn["args"]
            val args = when (argsEl) {
                null -> ""
                is JsonPrimitive -> argsEl.contentOrNull.orEmpty()
                else -> argsEl.toString()
            }
            out.add(
                MessagePart.ToolCall(
                    id = c.str("id", "call_id") ?: "srv-call-$n-$name",
                    tool = name,
                    argsPreview = args.truncated(300),
                    resultPreview = null,
                    status = ToolStatus.OK,
                    durationSec = null,
                ),
            )
        }
        return out
    }
}

/** Parsed entry of GET /api/sessions. */
data class RemoteSession(val id: String, val title: String, val updatedAt: Long, val preview: String)

object SessionParser {
    fun parseOne(o: JsonObject): RemoteSession? {
        val id = o.str("id", "session_id", "sessionId") ?: return null
        return RemoteSession(
            id = id,
            title = o.str("title", "name").orEmpty(),
            updatedAt = o.time("updated_at", "last_active", "last_activity", "last_message_at", "ended_at", "started_at", "created_at"),
            preview = (o.str("preview", "last_message", "summary", "snippet") ?: "").truncated(200),
        )
    }

    fun parseList(body: String): List<RemoteSession> {
        val root = parseJson(body) ?: return emptyList()
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root.arr("sessions", "data", "items", "results") ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { (it as? JsonObject)?.let(::parseOne) }
    }

    /** Create/get responses: the session object, possibly wrapped in "session"/"data". */
    fun parseCreated(body: String): RemoteSession? {
        val root = parseJsonObject(body) ?: return null
        return parseOne(root)
            ?: (root["session"] as? JsonObject)?.let(::parseOne)
            ?: (root["data"] as? JsonObject)?.let(::parseOne)
    }
}
