package com.pablo.yumemi.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pablo.yumemi.data.HermesRepository
import com.pablo.yumemi.data.chat.TranscriptMerger
import com.pablo.yumemi.data.chat.TurnRunner
import com.pablo.yumemi.data.remote.NetworkMonitor
import com.pablo.yumemi.data.remote.toConnectionState
import com.pablo.yumemi.data.settings.SettingsStore
import com.pablo.yumemi.domain.ApprovalStatus
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.ChatUiState
import com.pablo.yumemi.domain.ConnectionState
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.domain.ToolStatus
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.threads.withDisplayTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    savedStateHandle: SavedStateHandle,
    private val repo: HermesRepository,
    private val runner: TurnRunner,
    private val settings: SettingsStore,
    network: NetworkMonitor,
) : ViewModel() {

    private val threadId: String = savedStateHandle.get<String>("threadId").orEmpty()

    private data class Internal(
        val isSending: Boolean = false,
        val connection: ConnectionState = ConnectionState.Unknown,
        val error: String? = null,
        val transient: YumemiMood? = null,
    )

    private val internal = MutableStateFlow(
        Internal(connection = if (settings.isConfigured) ConnectionState.Unknown else ConnectionState.NotConfigured),
    )
    /** Server transcript (null until loaded / while offline). */
    private val server = MutableStateFlow<List<ChatMessage>?>(null)
    /** The assistant message of the current (or just finished) turn, overriding the stored copy. */
    private val inFlight = MutableStateFlow<ChatMessage?>(null)

    val uiState: StateFlow<ChatUiState> = combine(
        repo.observeLocalMessages(threadId),
        server,
        inFlight,
        repo.observeThread(threadId),
        internal,
    ) { local, srv, live, thread, i ->
        val localWithLive = when {
            live == null -> local
            local.any { it.id == live.id } -> local.map { if (it.id == live.id) live else it }
            else -> local + live
        }
        ChatUiState(
            threadId = threadId,
            title = thread?.withDisplayTitle()?.title.orEmpty(),
            messages = TranscriptMerger.merge(srv, localWithLive),
            mood = computeMood(i, live),
            isSending = i.isSending,
            connection = i.connection,
            error = i.error,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChatUiState(threadId = threadId, connection = internal.value.connection),
    )

    private var transientJob: Job? = null
    /** finishedAt of a finished turn whose error card the user dismissed (or that predates this screen). */
    private var dismissedAt: Long? = null

    init {
        observeLiveTurn()
        loadTranscript()
        // Re-attach to a run that was in flight when the process died.
        runner.resumePending(threadId)
        // The VPN often comes up after the radio: reload as soon as a network (re)appears.
        viewModelScope.launch {
            network.availableTick.drop(1).collect {
                if (internal.value.connection !is ConnectionState.Connected) loadTranscript()
            }
        }
    }

    private fun computeMood(i: Internal, live: ChatMessage?): YumemiMood {
        if (i.error != null) return YumemiMood.ERROR
        i.transient?.let { return it }
        if (i.isSending) {
            val parts = live?.parts.orEmpty()
            // Waiting for the user's permission: she needs attention, not "working".
            if (parts.any { it is MessagePart.Approval && it.status == ApprovalStatus.PENDING }) {
                return YumemiMood.SURPRISED
            }
            val working = parts.any {
                (it is MessagePart.ToolCall && it.status == ToolStatus.RUNNING) ||
                    (it is MessagePart.Subagent && it.status == ToolStatus.RUNNING)
            }
            if (working) return YumemiMood.WORKING
            return if (parts.lastOrNull() is MessagePart.Text) YumemiMood.IDLE else YumemiMood.THINKING
        }
        if (i.connection is ConnectionState.NotConfigured || i.connection is ConnectionState.Unreachable) {
            return YumemiMood.SLEEPING
        }
        return YumemiMood.IDLE
    }

    // ---------- transcript ----------

    private fun loadTranscript() {
        viewModelScope.launch {
            if (!settings.isConfigured) {
                internal.update { it.copy(connection = ConnectionState.NotConfigured) }
                return@launch
            }
            try {
                server.value = repo.fetchTranscript(threadId)
                internal.update { it.copy(connection = ConnectionState.Connected) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.toConnectionState()?.let { c -> internal.update { it.copy(connection = c) } }
            }
        }
    }

    // ---------- actions ----------

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || runner.isRunning(threadId)) return
        startTurn(t)
    }

    fun stop() {
        runner.stop(threadId)
    }

    fun clearError() {
        dismissedAt = runner.observe(threadId).value?.finishedAt
        internal.update { it.copy(error = null) }
    }

    /** Sends the user's answer to a permission question Hermes is waiting on. */
    fun answerApproval(requestId: String, choice: String) {
        runner.answerApproval(threadId, requestId, choice)
    }

    fun retryLast() {
        if (runner.isRunning(threadId)) return
        val msgs = uiState.value.messages
        val userIdx = msgs.indexOfLast { it.role == Role.USER }
        if (userIdx < 0) return
        val user = msgs[userIdx]
        val text = user.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }.trim()
        if (text.isEmpty()) return
        val after = msgs.getOrNull(userIdx + 1)?.takeIf { it.role == Role.ASSISTANT }
        val failed = after == null || after.error != null || after.parts.isEmpty()
        internal.update { it.copy(isSending = true, error = null, transient = null) }
        viewModelScope.launch {
            if (failed) {
                runner.clear(threadId)
                inFlight.value = null
                if (after != null) repo.deleteMessage(after.id)
                repo.deleteMessage(user.id)
            }
            runner.start(threadId, text)
        }
    }

    private fun startTurn(text: String) {
        internal.update { it.copy(isSending = true, error = null, transient = null) }
        runner.start(threadId, text)
    }

    /** Mirrors the app-level turn into UI state; the turn itself keeps running without this screen. */
    private fun observeLiveTurn() {
        viewModelScope.launch {
            var first = true
            var wasRunning = false
            var lastTools = 0
            runner.observe(threadId).collect { live ->
                if (first) {
                    first = false
                    // A turn that finished before this screen existed is already stored: don't replay it.
                    if (live != null && !live.running) dismissedAt = live.finishedAt
                    lastTools = live?.toolAfterTextCount ?: 0
                }
                if (live == null) {
                    inFlight.value = null
                    wasRunning = false
                    return@collect
                }
                val stale = !live.running && live.finishedAt != null && live.finishedAt == dismissedAt && !wasRunning
                val shown = live.running || live.message.parts.isNotEmpty() || live.message.error != null
                inFlight.value = if (shown && !stale) live.message else null
                val error = if (!live.running && live.finishedAt != dismissedAt) live.error else null
                internal.update { it.copy(isSending = live.running, error = error) }

                if (live.toolAfterTextCount < lastTools) lastTools = live.toolAfterTextCount
                if (live.toolAfterTextCount > lastTools) {
                    lastTools = live.toolAfterTextCount
                    flash(YumemiMood.SURPRISED, 1_500)
                }
                if (live.running) {
                    wasRunning = true
                } else if (wasRunning) {
                    wasRunning = false
                    if (live.succeeded) {
                        internal.update { it.copy(connection = ConnectionState.Connected) }
                        flash(YumemiMood.HAPPY, 3_000)
                    }
                    // Even after a failure the server may still hold the answer.
                    loadTranscript()
                }
            }
        }
    }

    private fun flash(mood: YumemiMood, millis: Long) {
        transientJob?.cancel()
        transientJob = viewModelScope.launch {
            internal.update { it.copy(transient = mood) }
            delay(millis)
            internal.update { it.copy(transient = null) }
        }
    }
}
