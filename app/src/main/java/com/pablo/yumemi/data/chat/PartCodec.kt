package com.pablo.yumemi.data.chat

import com.pablo.yumemi.data.remote.LenientJson
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.ToolStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** JSON persistence of [MessagePart] lists (the contract types are not annotated). */
@Serializable
private data class PartDto(
    val type: String,
    val text: String = "",
    val id: String = "",
    val tool: String = "",
    val args: String = "",
    val result: String? = null,
    val status: String = "OK",
    val duration: Double? = null,
)

object PartCodec {
    private val serializer = ListSerializer(PartDto.serializer())

    fun encode(parts: List<MessagePart>): String =
        LenientJson.encodeToString(serializer, parts.map { toDto(it) })

    fun decode(json: String): List<MessagePart> =
        try {
            LenientJson.decodeFromString(serializer, json).mapNotNull { fromDto(it) }
        } catch (e: Exception) {
            emptyList()
        }

    private fun toDto(p: MessagePart): PartDto = when (p) {
        is MessagePart.Text -> PartDto(type = "text", text = p.text)
        is MessagePart.Reasoning -> PartDto(type = "reasoning", text = p.text)
        is MessagePart.Commentary -> PartDto(type = "commentary", text = p.text)
        is MessagePart.ToolCall -> PartDto(
            type = "tool", id = p.id, tool = p.tool, args = p.argsPreview,
            result = p.resultPreview, status = p.status.name, duration = p.durationSec,
        )
        is MessagePart.Subagent -> PartDto(
            type = "subagent", id = p.id, text = p.summary, status = p.status.name, duration = p.durationSec,
        )
    }

    private fun status(s: String): ToolStatus =
        try {
            ToolStatus.valueOf(s)
        } catch (_: Exception) {
            ToolStatus.OK
        }

    private fun fromDto(d: PartDto): MessagePart? = when (d.type) {
        "text" -> MessagePart.Text(d.text)
        "reasoning" -> MessagePart.Reasoning(d.text)
        "commentary" -> MessagePart.Commentary(d.text)
        "tool" -> MessagePart.ToolCall(
            id = d.id, tool = d.tool, argsPreview = d.args, resultPreview = d.result,
            // A persisted RUNNING call can never finish any more.
            status = status(d.status).let { if (it == ToolStatus.RUNNING) ToolStatus.FAILED else it },
            durationSec = d.duration,
        )
        "subagent" -> MessagePart.Subagent(
            id = d.id, summary = d.text,
            status = status(d.status).let { if (it == ToolStatus.RUNNING) ToolStatus.FAILED else it },
            durationSec = d.duration,
        )
        else -> null
    }
}
