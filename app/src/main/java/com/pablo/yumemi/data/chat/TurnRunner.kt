package com.pablo.yumemi.data.chat

import android.content.Context
import com.pablo.yumemi.data.HermesRepository
import com.pablo.yumemi.data.remote.HermesClient
import com.pablo.yumemi.data.remote.HermesException
import com.pablo.yumemi.data.remote.NetworkMonitor
import com.pablo.yumemi.data.remote.num
import com.pablo.yumemi.data.remote.parseJsonObject
import com.pablo.yumemi.data.remote.str
import com.pablo.yumemi.data.remote.toUserMessage
import com.pablo.yumemi.data.settings.SettingsStore
import com.pablo.yumemi.domain.ApprovalStatus
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns assistant turns at app scope, so leaving the chat screen never cancels them.
 * Preferred transport is the Runs API (a run survives client disconnects and can be replayed);
 * older servers fall back to the session stream, which Hermes aborts whenever the socket drops.
 */
class TurnRunner(
    context: Context,
    private val repo: HermesRepository,
    private val client: HermesClient,
    private val settings: SettingsStore,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
) {

    /** What the UI shows for a thread: the assistant message of the current (or just finished) turn. */
    data class LiveTurn(
        val threadId: String,
        val message: ChatMessage,
        val running: Boolean,
        val error: String?,
        val succeeded: Boolean,
        val finishedAt: Long?,
        /** Increases each time a tool started after answer text had streamed. */
        val toolAfterTextCount: Int = 0,
    )

    private class Active(val threadId: String, val acc: TurnAccumulator, var assistant: ChatMessage) {
        @Volatile var runId: String? = null
        var toolAfterText = 0
        var lastPersist = 0L
        var lastSeq = -1L
    }

    private data class Pending(val runId: String, val assistantId: String, val createdAt: Long, val lastSeq: Long)

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PENDING_FILE, Context.MODE_PRIVATE)
    private val states = ConcurrentHashMap<String, MutableStateFlow<LiveTurn?>>()
    private val active = ConcurrentHashMap<String, Active>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Set once a server answers 404/405 to POST /v1/runs; later turns skip straight to the session stream. */
    @Volatile private var runsUnsupported = false

    private fun stateFor(threadId: String): MutableStateFlow<LiveTurn?> =
        states.getOrPut(threadId) { MutableStateFlow(null) }

    fun observe(threadId: String): StateFlow<LiveTurn?> = stateFor(threadId)

    private val _running = MutableStateFlow<Map<String, LiveTurn>>(emptyMap())
    /** Turns currently running in this app, by thread. */
    val running: StateFlow<Map<String, LiveTurn>> = _running

    private fun setState(threadId: String, turn: LiveTurn?) {
        stateFor(threadId).value = turn
        _running.update { m -> if (turn?.running == true) m + (threadId to turn) else m - threadId }
    }

    fun isRunning(threadId: String): Boolean = stateFor(threadId).value?.running == true

    /** Forgets a finished turn (e.g. before retrying, so the old message is not shown again). */
    fun clear(threadId: String) {
        if (!isRunning(threadId)) setState(threadId, null)
    }

    // ---------- public actions ----------

    fun start(threadId: String, text: String) {
        if (isRunning(threadId)) return
        val now = System.currentTimeMillis()
        val assistant = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = Role.ASSISTANT,
            parts = emptyList(),
            createdAt = now + 1,
            isStreaming = true,
        )
        val a = Active(threadId, TurnAccumulator(autoCompleteTools = settings.useChatCompletions.value), assistant)
        launchTurn(a) { execute(a, text, null) }
    }

    /** Re-attaches to a run that was in flight when the process died. */
    fun resumePending(threadId: String) {
        if (isRunning(threadId)) return
        val p = readPending(threadId) ?: return
        val assistant = ChatMessage(
            id = p.assistantId,
            role = Role.ASSISTANT,
            parts = emptyList(),
            createdAt = p.createdAt,
            isStreaming = true,
        )
        val a = Active(threadId, TurnAccumulator(), assistant)
        a.runId = p.runId
        launchTurn(a) { execute(a, null, p) }
    }

    fun stop(threadId: String) {
        val a = active[threadId] ?: return
        val runId = a.runId
        if (runId != null) {
            scope.launch {
                try {
                    client.stopRun(runId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best effort: the turn is stopped locally either way.
                }
            }
        }
        jobs[threadId]?.cancel()
    }

    /** Sends the user's answer to a permission question Hermes is waiting on. */
    fun answerApproval(threadId: String, requestId: String, choice: String) {
        val a = active[threadId] ?: return
        val acc = a.acc
        val part = acc.parts.firstOrNull { it is MessagePart.Approval && it.requestId == requestId }
            as? MessagePart.Approval ?: return
        if (part.status != ApprovalStatus.PENDING) return
        acc.setApproval(requestId, ApprovalStatus.SENDING, choice)
        publishParts(a)
        scope.launch {
            try {
                repo.answerApproval(part.runId, requestId, choice)
                acc.setApproval(requestId, ApprovalStatus.ANSWERED, choice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Shown on the card itself: the chat's error card would offer to resend the message.
                val gone = (e as? HermesException.Http)?.code == 409 || acc.finished
                if (gone) {
                    // Hermes stopped waiting (timeout or the turn ended).
                    acc.setApproval(requestId, ApprovalStatus.EXPIRED, null)
                } else {
                    // PENDING with an answer = the last attempt did not get through.
                    acc.setApproval(requestId, ApprovalStatus.PENDING, choice)
                }
            }
            publishParts(a)
            if (active[threadId] !== a) {
                // The turn already ended and was stored: store the card's final state too.
                val m = a.assistant.copy(parts = acc.parts, isStreaming = false)
                a.assistant = m
                try {
                    repo.saveMessage(threadId, m)
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------- turn lifecycle ----------

    private fun launchTurn(a: Active, body: suspend () -> Unit) {
        active[a.threadId] = a
        // Foreground service: keeps the process and network alive while the app is in the background.
        TurnService.setRunning(appContext, true)
        publish(a)
        // LAZY: the job is registered before it can possibly finish.
        val job = scope.launch(start = CoroutineStart.LAZY) { body() }
        jobs[a.threadId] = job
        job.start()
    }

    private fun publish(
        a: Active,
        running: Boolean = true,
        error: String? = null,
        succeeded: Boolean = false,
        finishedAt: Long? = null,
        message: ChatMessage = a.assistant,
    ) {
        setState(
            a.threadId,
            LiveTurn(
                threadId = a.threadId,
                message = message,
                running = running,
                error = error,
                succeeded = succeeded,
                finishedAt = finishedAt,
                toolAfterTextCount = a.toolAfterText,
            ),
        )
    }

    /** Refreshes the parts of the shown message without changing anything else (approval cards). */
    private fun publishParts(a: Active) {
        a.assistant = a.assistant.copy(parts = a.acc.parts)
        val cur = stateFor(a.threadId).value ?: return
        if (cur.message.id == a.assistant.id) setState(a.threadId, cur.copy(message = cur.message.copy(parts = a.acc.parts)))
    }

    private suspend fun execute(a: Active, text: String?, resume: Pending?) {
        val threadId = a.threadId
        val acc = a.acc
        var succeeded = false
        var stored: ChatMessage? = null
        try {
            if (text != null) {
                val userAt = a.assistant.createdAt - 1
                val userMsg = ChatMessage(UUID.randomUUID().toString(), Role.USER, listOf(MessagePart.Text(text)), userAt)
                repo.saveMessage(threadId, userMsg)
                repo.touchThread(threadId, userMsg)
                // Separate job: the title is still set if the user stops the turn.
                scope.launch { repo.autoTitleIfBlank(threadId, text) }
            }
            if (resume != null) {
                stored = repo.getMessage(resume.assistantId)
                if (stored != null) {
                    a.assistant = a.assistant.copy(parts = stored.parts)
                    publish(a)
                }
            }
            when {
                resume != null -> followRun(a, resume.runId)
                text == null -> Unit
                settings.useChatCompletions.value || runsUnsupported -> streamSession(a, text!!)
                else -> runViaRuns(a, text!!)
            }
            if (!acc.finished) {
                // Stream closed without a terminal event: treat as completed if anything arrived.
                if (acc.parts.isEmpty()) {
                    acc.fail("Hermes no devolvió ninguna respuesta")
                } else {
                    acc.apply(StreamEvent.Completed)
                }
            }
            succeeded = acc.error == null && !acc.stopped
        } catch (e: CancellationException) {
            acc.stop()
            throw e
        } catch (e: Exception) {
            acc.fail(e.toUserMessage())
        } finally {
            val storedCopy = stored
            withContext(NonCancellable) {
                // A resumed run Hermes no longer knows: keep the copy stored before the process died.
                val keepStored = resume != null && storedCopy != null && storedCopy.parts.isNotEmpty() &&
                    acc.parts.isEmpty() && !succeeded
                val finalMsg = if (keepStored && storedCopy != null) {
                    a.assistant.copy(parts = storedCopy.parts, error = storedCopy.error, isStreaming = false)
                } else {
                    a.assistant.copy(parts = acc.parts, error = acc.error, isStreaming = false)
                }
                a.assistant = finalMsg
                if (!keepStored && (finalMsg.parts.isNotEmpty() || finalMsg.error != null)) {
                    try {
                        repo.saveMessage(threadId, finalMsg)
                        repo.touchThread(threadId, finalMsg.copy(createdAt = System.currentTimeMillis()))
                    } catch (e: Exception) {
                        // Persisting is best effort.
                    }
                }
                clearPending(threadId)
                active.remove(threadId, a)
                if (active.isEmpty()) TurnService.setRunning(appContext, false)
                publish(
                    a,
                    running = false,
                    error = if (keepStored) null else acc.error,
                    succeeded = succeeded,
                    finishedAt = System.currentTimeMillis(),
                    message = finalMsg,
                )
            }
        }
    }

    private fun isTerminal(ev: StreamEvent): Boolean =
        ev is StreamEvent.Completed || ev is StreamEvent.Failed || ev is StreamEvent.Cancelled

    /** Applies [ev], publishes the new message and persists it about once a second. */
    private suspend fun applyEvent(a: Active, ev: StreamEvent) {
        if (a.acc.apply(ev)) a.toolAfterText++
        a.assistant = a.assistant.copy(parts = a.acc.parts, error = a.acc.error, isStreaming = !a.acc.finished)
        publish(a)
        val t = System.currentTimeMillis()
        if (t - a.lastPersist > 1_000) {
            a.lastPersist = t
            repo.saveMessage(a.threadId, a.assistant.copy(isStreaming = false))
            a.runId?.let { savePending(a.threadId, Pending(it, a.assistant.id, a.assistant.createdAt, a.lastSeq)) }
        }
    }

    // ---------- session stream / chat completions ----------

    private suspend fun streamSession(a: Active, text: String) {
        try {
            repo.streamTurn(a.threadId, text)
                .onEach { applyEvent(a, it) }
                .takeWhile { !isTerminal(it) }
                .collect { }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e is HermesException.Unreachable || e is IOException) && a.acc.parts.isNotEmpty()) {
                // Hermes aborts a turn on this transport as soon as the socket drops.
                applyEvent(a, StreamEvent.Failed("Se cortó la conexión y Hermes interrumpió la respuesta"))
            } else {
                throw e
            }
        }
    }

    // ---------- Runs API ----------

    private suspend fun runViaRuns(a: Active, text: String) {
        val key = UUID.randomUUID().toString()
        var runId: String? = null
        var failures = 0
        while (runId == null) {
            try {
                runId = client.startRun(a.threadId, text, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HermesException.RunsUnsupported) {
                runsUnsupported = true
                streamSession(a, text)
                return
            } catch (e: Exception) {
                if (e !is HermesException.Unreachable && e !is IOException) throw e
                failures++
                if (failures >= START_ATTEMPTS) throw e
                // Same idempotency key every time: a POST that did arrive is not run twice.
                delay(1_000L shl (failures - 1))
            }
        }
        a.runId = runId
        savePending(a.threadId, Pending(runId, a.assistant.id, a.assistant.createdAt, -1L))
        followRun(a, runId)
    }

    /** Follows the run's event stream, reconnecting (with replay) until a terminal event arrives. */
    private suspend fun followRun(a: Active, runId: String) {
        val acc = a.acc
        var backoff = 1_000L
        var lastContact = System.currentTimeMillis()
        while (!acc.finished) {
            val tick0 = network.availableTick.value
            var gotEvent = false
            var terminal = false
            var cleanEnd = false
            try {
                client.runEvents(runId, a.lastSeq)
                    .takeWhile { sse ->
                        gotEvent = true
                        lastContact = System.currentTimeMillis()
                        val seq = sse.id?.trim()?.toLongOrNull() ?: parseJsonObject(sse.data)?.num("seq")?.toLong()
                        if (seq != null && seq > a.lastSeq) a.lastSeq = seq
                        for (ev in EventMapper.mapSession(sse)) {
                            applyEvent(a, ev)
                            if (isTerminal(ev)) terminal = true
                        }
                        !terminal
                    }
                    .collect { }
                cleanEnd = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: HermesException.Http) {
                // 404: the run's stream is retired; its status says how it ended.
                if (e.code != 404 && e.code < 500) throw e
                if (e.code == 404 && checkStatus(a, runId)) return
            } catch (e: HermesException.Unreachable) {
                // Dead or unreachable socket: reconnect below.
            } catch (e: IOException) {
                // Same.
            }
            if (acc.finished) return
            if (cleanEnd) {
                lastContact = System.currentTimeMillis()
                if (checkStatus(a, runId)) return
            }
            if (gotEvent) backoff = 1_000L
            if (System.currentTimeMillis() - lastContact > GIVE_UP_MS) {
                if (!checkStatus(a, runId)) {
                    applyEvent(
                        a,
                        StreamEvent.Failed("Perdí el contacto con Hermes. La respuesta seguirá en el servidor: recarga el chat más tarde."),
                    )
                }
                return
            }
            val woke = waitForRetry(tick0, backoff)
            backoff = if (woke) 1_000L else minOf(backoff * 2, 15_000L)
        }
    }

    /** Sleeps [ms], or less when the network (re)appears. Returns true when woken by the network. */
    private suspend fun waitForRetry(tick0: Long, ms: Long): Boolean =
        withTimeoutOrNull(ms) {
            network.availableTick.first { it != tick0 }
            true
        } ?: false

    /** Asks the server how the run ended. True when the turn was finished by the answer. */
    private suspend fun checkStatus(a: Active, runId: String): Boolean {
        val body = try {
            client.runStatus(runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesException.Http) {
            if (e.code == 404) {
                val ev = if (a.acc.parts.isNotEmpty()) {
                    StreamEvent.Completed
                } else {
                    StreamEvent.Failed("Hermes ya no tiene esta respuesta; recarga el chat.")
                }
                applyEvent(a, ev)
                return true
            }
            if (e.code >= 500) return false
            throw e
        } catch (e: HermesException.Unreachable) {
            return false
        } catch (e: IOException) {
            return false
        }
        val obj = parseJsonObject(body) ?: return false
        when (obj.str("status")?.lowercase()) {
            "completed" -> {
                obj.str("output")?.let { applyEvent(a, StreamEvent.FinalText(it)) }
                applyEvent(a, StreamEvent.Completed)
            }
            "failed" -> applyEvent(a, StreamEvent.Failed(obj.str("error") ?: "La ejecución falló en Hermes"))
            "interrupted" -> applyEvent(a, StreamEvent.Failed(obj.str("error") ?: "Hermes se reinició y cortó la respuesta"))
            "cancelled", "canceled" -> applyEvent(a, StreamEvent.Cancelled)
            else -> return false // queued, running, waiting_for_approval, stopping
        }
        return true
    }

    // ---------- pending runs (survive process death) ----------

    private fun savePending(threadId: String, p: Pending) {
        try {
            prefs.edit().putString(threadId, "${p.runId}|${p.assistantId}|${p.createdAt}|${p.lastSeq}").apply()
        } catch (_: Exception) {
        }
    }

    private fun readPending(threadId: String): Pending? {
        val raw = try {
            prefs.getString(threadId, null)
        } catch (_: Exception) {
            null
        } ?: return null
        val f = raw.split("|")
        if (f.size < 4) return null
        val created = f[2].toLongOrNull() ?: return null
        return Pending(f[0], f[1], created, f[3].toLongOrNull() ?: -1L)
    }

    private fun clearPending(threadId: String) {
        try {
            prefs.edit().remove(threadId).apply()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val PENDING_FILE = "yumemi_pending_runs"
        const val START_ATTEMPTS = 4
        const val GIVE_UP_MS = 15 * 60 * 1_000L
    }
}
