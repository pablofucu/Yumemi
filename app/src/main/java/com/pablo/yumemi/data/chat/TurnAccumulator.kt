package com.pablo.yumemi.data.chat

import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.ToolStatus

/**
 * Folds [StreamEvent]s of one assistant turn into an ordered list of [MessagePart]s.
 * [autoCompleteTools]: for transports that only announce tool starts (chat completions),
 * running tools are marked OK as soon as the next content arrives.
 */
class TurnAccumulator(private val autoCompleteTools: Boolean = false) {

    var parts: List<MessagePart> = emptyList()
        private set
    var finished: Boolean = false
        private set
    var error: String? = null
        private set
    var stopped: Boolean = false
        private set

    private var counter = 0

    /** Applies an event. Returns true when a tool started after some answer text had already streamed. */
    fun apply(ev: StreamEvent): Boolean {
        val list = parts.toMutableList()
        var toolAfterText = false
        when (ev) {
            is StreamEvent.TextDelta -> {
                if (autoCompleteTools) finishRunning(list, ToolStatus.OK)
                val last = list.lastOrNull()
                if (last is MessagePart.Text) {
                    list[list.lastIndex] = MessagePart.Text(last.text + ev.text)
                } else {
                    list.add(MessagePart.Text(ev.text))
                }
            }
            is StreamEvent.ReasoningDelta -> {
                if (autoCompleteTools) finishRunning(list, ToolStatus.OK)
                val last = list.lastOrNull()
                if (last is MessagePart.Reasoning) {
                    list[list.lastIndex] = MessagePart.Reasoning(last.text + ev.text)
                } else {
                    list.add(MessagePart.Reasoning(ev.text))
                }
            }
            is StreamEvent.Commentary -> {
                if (ev.alreadyStreamed) {
                    // The same words already arrived as answer deltas: turn that text into commentary.
                    val idx = list.indexOfLast { it is MessagePart.Text }
                    if (idx >= 0) {
                        val t = (list[idx] as MessagePart.Text).text
                        if (t.trim().endsWith(ev.text.trim()) && t.trim() == ev.text.trim()) {
                            list[idx] = MessagePart.Commentary(ev.text.trim())
                        }
                    }
                } else if (ev.text.isNotBlank()) {
                    list.add(MessagePart.Commentary(ev.text))
                }
            }
            is StreamEvent.ToolStarted -> {
                if (autoCompleteTools) finishRunning(list, ToolStatus.OK)
                toolAfterText = list.any { it is MessagePart.Text && it.text.isNotBlank() }
                list.add(
                    MessagePart.ToolCall(
                        id = ev.id ?: "tool-${counter++}",
                        tool = ev.tool,
                        argsPreview = ev.preview,
                        resultPreview = null,
                        status = ToolStatus.RUNNING,
                        durationSec = null,
                    ),
                )
            }
            is StreamEvent.ToolFinished -> {
                var idx = if (ev.id != null) list.indexOfFirst { it is MessagePart.ToolCall && it.id == ev.id } else -1
                if (idx < 0) {
                    idx = list.indexOfFirst {
                        it is MessagePart.ToolCall && it.status == ToolStatus.RUNNING && it.tool == ev.tool
                    }
                }
                val status = if (ev.ok) ToolStatus.OK else ToolStatus.FAILED
                if (idx >= 0) {
                    val old = list[idx] as MessagePart.ToolCall
                    list[idx] = old.copy(
                        status = status,
                        durationSec = ev.durationSec ?: old.durationSec,
                        resultPreview = ev.preview ?: old.resultPreview,
                    )
                } else {
                    list.add(
                        MessagePart.ToolCall(
                            id = ev.id ?: "tool-${counter++}",
                            tool = ev.tool,
                            argsPreview = "",
                            resultPreview = ev.preview,
                            status = status,
                            durationSec = ev.durationSec,
                        ),
                    )
                }
            }
            is StreamEvent.SubagentStarted -> {
                list.add(
                    MessagePart.Subagent(
                        id = ev.id ?: "sub-${counter++}",
                        summary = ev.summary,
                        status = ToolStatus.RUNNING,
                        durationSec = null,
                    ),
                )
            }
            is StreamEvent.SubagentFinished -> {
                var idx = if (ev.id != null) list.indexOfFirst { it is MessagePart.Subagent && it.id == ev.id } else -1
                if (idx < 0) idx = list.indexOfFirst { it is MessagePart.Subagent && it.status == ToolStatus.RUNNING }
                val status = if (ev.ok) ToolStatus.OK else ToolStatus.FAILED
                if (idx >= 0) {
                    val old = list[idx] as MessagePart.Subagent
                    list[idx] = old.copy(
                        status = status,
                        durationSec = ev.durationSec ?: old.durationSec,
                        summary = ev.summary ?: old.summary,
                    )
                } else {
                    list.add(
                        MessagePart.Subagent(
                            id = ev.id ?: "sub-${counter++}",
                            summary = ev.summary ?: "Subagente",
                            status = status,
                            durationSec = ev.durationSec,
                        ),
                    )
                }
            }
            StreamEvent.Completed -> {
                finishRunning(list, ToolStatus.OK)
                finished = true
            }
            is StreamEvent.Failed -> {
                finishRunning(list, ToolStatus.FAILED)
                error = ev.message
                finished = true
            }
            StreamEvent.Cancelled -> {
                finishRunning(list, ToolStatus.FAILED)
                stopped = true
                finished = true
            }
            is StreamEvent.Unknown -> Unit
        }
        parts = list
        return toolAfterText
    }

    /** Local failure (network error etc.) keeping what already arrived. */
    fun fail(message: String) {
        apply(StreamEvent.Failed(message))
    }

    /** User pressed stop. */
    fun stop() {
        if (!finished) apply(StreamEvent.Cancelled)
    }

    private fun finishRunning(list: MutableList<MessagePart>, status: ToolStatus) {
        for (i in list.indices) {
            val p = list[i]
            if (p is MessagePart.ToolCall && p.status == ToolStatus.RUNNING) list[i] = p.copy(status = status)
            if (p is MessagePart.Subagent && p.status == ToolStatus.RUNNING) list[i] = p.copy(status = status)
        }
    }
}
