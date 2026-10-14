package com.pablo.yumemi.data.chat

import android.util.Log
import com.pablo.yumemi.data.remote.SseEvent
import com.pablo.yumemi.data.remote.arr
import com.pablo.yumemi.data.remote.bool
import com.pablo.yumemi.data.remote.num
import com.pablo.yumemi.data.remote.parseJsonObject
import com.pablo.yumemi.data.remote.str
import com.pablo.yumemi.data.remote.truncated
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Transport-independent events of one assistant turn. */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data class Commentary(val text: String, val alreadyStreamed: Boolean) : StreamEvent
    data class ToolStarted(val id: String?, val tool: String, val preview: String) : StreamEvent
    data class ToolFinished(
        val id: String?,
        val tool: String,
        val ok: Boolean,
        val durationSec: Double?,
        val preview: String?,
    ) : StreamEvent
    data class SubagentStarted(val id: String?, val summary: String) : StreamEvent
    data class SubagentFinished(
        val id: String?,
        val summary: String?,
        val ok: Boolean,
        val durationSec: Double?,
    ) : StreamEvent
    data object Completed : StreamEvent
    data class Failed(val message: String) : StreamEvent
    data object Cancelled : StreamEvent
    data class Unknown(val type: String) : StreamEvent
}

/** Maps raw SSE events of each transport to [StreamEvent]s. Pure, unit-testable. */
object EventMapper {
    private const val TAG = "EventMapper"
    private const val PREVIEW_MAX = 600

    private fun log(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun errorText(obj: JsonObject?): String? {
        if (obj == null) return null
        val e = obj["error"]
        if (e is JsonObject) return e.str("message", "detail", "error", "code")
        return obj.str("error", "message", "detail")
    }

    private fun isError(obj: JsonObject): Boolean {
        val e = obj["error"]
        if (e != null && e !is JsonNull) {
            if (e is JsonPrimitive) {
                val b = e.content.lowercase()
                if (b == "false" || b.isEmpty() || b == "null" || b == "0") return false
            }
            return true
        }
        val status = obj.str("status")?.lowercase()
        if (status == "failed" || status == "error") return true
        if (obj.bool("failed", "is_error") == true) return true
        return false
    }

    fun mapSession(sse: SseEvent): List<StreamEvent> {
        val data = sse.data.trim()
        if (data == "[DONE]") return listOf(StreamEvent.Completed)
        val obj = parseJsonObject(data)
        val type = (sse.event?.takeIf { it.isNotBlank() && it != "message" } ?: obj?.str("type", "event"))
            ?.lowercase().orEmpty()

        if (type.contains("reasoning") || type.contains("thinking")) {
            val t = obj?.str("text", "delta", "reasoning", "reasoning_content", "content") ?: if (obj == null) data else null
            return if (t.isNullOrEmpty()) emptyList() else listOf(StreamEvent.ReasoningDelta(t))
        }

        when (type) {
            "assistant.delta", "message.delta" -> {
                val t = obj?.str("delta", "text", "content", "token") ?: if (obj == null) sse.data else null
                return if (t.isNullOrEmpty()) emptyList() else listOf(StreamEvent.TextDelta(t))
            }
            "assistant.commentary", "message.interim" -> {
                if (obj == null) return emptyList()
                val t = obj.str("text", "content", "message") ?: return emptyList()
                return listOf(StreamEvent.Commentary(t, obj.bool("already_streamed") ?: false))
            }
            "tool.started", "tool.start" -> {
                if (obj == null) return emptyList()
                return listOf(
                    StreamEvent.ToolStarted(
                        id = obj.str("tool_call_id", "call_id", "id"),
                        tool = obj.str("tool", "name", "tool_name") ?: "herramienta",
                        preview = (obj.str("preview", "args", "arguments", "input") ?: "").truncated(PREVIEW_MAX),
                    ),
                )
            }
            "tool.completed", "tool.complete", "tool.failed" -> {
                if (obj == null) return emptyList()
                val failed = type == "tool.failed" || isError(obj)
                return listOf(
                    StreamEvent.ToolFinished(
                        id = obj.str("tool_call_id", "call_id", "id"),
                        tool = obj.str("tool", "name", "tool_name") ?: "herramienta",
                        ok = !failed,
                        durationSec = obj.num("duration", "duration_s", "duration_sec", "elapsed"),
                        preview = (obj.str("preview", "result", "output") ?: errorText(obj))?.truncated(PREVIEW_MAX),
                    ),
                )
            }
            "subagent.start", "subagent.started" -> {
                if (obj == null) return emptyList()
                return listOf(
                    StreamEvent.SubagentStarted(
                        id = obj.str("id", "subagent_id", "task_id"),
                        summary = obj.str("summary", "goal", "task", "preview", "name") ?: "Subagente",
                    ),
                )
            }
            "subagent.complete", "subagent.completed", "subagent.failed" -> {
                if (obj == null) return emptyList()
                return listOf(
                    StreamEvent.SubagentFinished(
                        id = obj.str("id", "subagent_id", "task_id"),
                        summary = obj.str("summary", "goal", "task", "preview", "name"),
                        ok = type != "subagent.failed" && !isError(obj),
                        durationSec = obj.num("duration", "duration_s", "duration_sec", "elapsed"),
                    ),
                )
            }
            "run.completed", "done" -> return listOf(StreamEvent.Completed)
            "run.failed" -> return listOf(
                StreamEvent.Failed(errorText(obj) ?: "La ejecución falló en Hermes"),
            )
            "run.cancelled", "run.canceled" -> return listOf(StreamEvent.Cancelled)
        }

        if (obj != null) {
            val r = obj.str("reasoning", "reasoning_content")
            if (r != null) return listOf(StreamEvent.ReasoningDelta(r))
        }
        log("Unknown session event type='$type' data=${data.take(200)}")
        return listOf(StreamEvent.Unknown(type))
    }

    fun mapChatCompletions(sse: SseEvent): List<StreamEvent> {
        val data = sse.data.trim()
        if (data == "[DONE]") return listOf(StreamEvent.Completed)
        val eventName = sse.event?.lowercase().orEmpty()
        val obj = parseJsonObject(data)

        if (eventName.contains("tool")) {
            val tool: String
            val label: String
            if (obj != null) {
                tool = obj.str("tool", "name", "tool_name") ?: "herramienta"
                label = (obj.str("label", "preview", "message", "text", "args") ?: "").truncated(PREVIEW_MAX)
            } else {
                tool = "herramienta"
                label = data.truncated(PREVIEW_MAX)
            }
            return listOf(StreamEvent.ToolStarted(null, tool, label))
        }
        if (obj == null) {
            log("Unparseable chat-completions chunk: ${data.take(200)}")
            return listOf(StreamEvent.Unknown(eventName))
        }
        val topError = obj["error"]
        if (topError != null && topError !is JsonNull) {
            return listOf(StreamEvent.Failed(errorText(obj) ?: "La ejecución falló en Hermes"))
        }
        val out = mutableListOf<StreamEvent>()
        val choice = obj.arr("choices")?.firstOrNull() as? JsonObject
        val delta = (choice?.get("delta") as? JsonObject) ?: (choice?.get("message") as? JsonObject)
        if (delta != null) {
            val r = delta.str("reasoning_content", "reasoning")
            if (!r.isNullOrEmpty()) out.add(StreamEvent.ReasoningDelta(r))
            val c = delta.str("content")
            if (!c.isNullOrEmpty()) out.add(StreamEvent.TextDelta(c))
        }
        if (out.isEmpty() && delta == null) {
            log("Ignored chat-completions chunk: ${data.take(200)}")
        }
        return out
    }
}
