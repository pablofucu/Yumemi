package com.pablo.yumemi.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pablo.yumemi.data.HermesRepository
import com.pablo.yumemi.data.chat.StreamEvent
import com.pablo.yumemi.data.chat.TranscriptMerger
import com.pablo.yumemi.data.chat.TurnAccumulator
import com.pablo.yumemi.data.remote.HermesClient
import com.pablo.yumemi.data.remote.toConnectionState
import com.pablo.yumemi.data.remote.toUserMessage
import com.pablo.yumemi.data.settings.SettingsStore
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.ChatUiState
import com.pablo.yumemi.domain.ConnectionState
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.domain.ToolStatus
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.threads.withDisplayTitle
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    savedStateHandle: SavedStateHandle,
    private val repo: HermesRepository,
    private val client: HermesClient,
    private val settings: SettingsStore,
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

    private var streamJob: Job? = null
    private var transientJob: Job? = null

    init {
        loadTranscript()
    }

    private fun computeMood(i: Internal, live: ChatMessage?): YumemiMood {
        if (i.error != null) return YumemiMood.ERROR
        i.transient?.let { return it }
        if (i.isSending) {
            val parts = live?.parts.orEmpty()
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
        if (t.isEmpty() || internal.value.isSending) return
        startTurn(t)
    }

    fun stop() {
        streamJob?.cancel()
    }

    fun clearError() {
        internal.update { it.copy(error = null) }
    }

    fun retryLast() {
        if (internal.value.isSending) return
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
                inFlight.value = null
                if (after != null) repo.deleteMessage(after.id)
                repo.deleteMessage(user.id)
            }
            streamJob = launch { runTurn(text) }
        }
    }

    private fun startTurn(text: String) {
        internal.update { it.copy(isSending = true, error = null, transient = null) }
        streamJob = viewModelScope.launch { runTurn(text) }
    }

    private fun flash(mood: YumemiMood, millis: Long) {
        transientJob?.cancel()
        transientJob = viewModelScope.launch {
            internal.update { it.copy(transient = mood) }
            delay(millis)
            internal.update { it.copy(transient = null) }
        }
    }

    private fun isTerminal(ev: StreamEvent): Boolean =
        ev is StreamEvent.Completed || ev is StreamEvent.Failed || ev is StreamEvent.Cancelled

    private suspend fun runTurn(text: String) {
        transientJob?.cancel()
        val now = System.currentTimeMillis()
        val acc = TurnAccumulator(autoCompleteTools = settings.useChatCompletions.value)
        var assistant = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = Role.ASSISTANT,
            parts = emptyList(),
            createdAt = now + 1,
            isStreaming = true,
        )
        var lastPersist = System.currentTimeMillis()
        var succeeded = false

        try {
            repo.saveMessage(
                threadId,
                ChatMessage(UUID.randomUUID().toString(), Role.USER, listOf(MessagePart.Text(text)), now),
            )
            repo.touchThread(threadId, now, text)
            inFlight.value = assistant
            // Separate job: the title is still set if the user stops the turn.
            viewModelScope.launch { repo.autoTitleIfBlank(threadId, text) }

            repo.streamTurn(threadId, text)
                .onEach { ev ->
                    val toolAfterText = acc.apply(ev)
                    assistant = assistant.copy(parts = acc.parts, error = acc.error, isStreaming = !acc.finished)
                    inFlight.value = assistant
                    if (toolAfterText) flash(YumemiMood.SURPRISED, 1_500)
                    val t = System.currentTimeMillis()
                    if (t - lastPersist > 1_000) {
                        lastPersist = t
                        repo.saveMessage(threadId, assistant.copy(isStreaming = false))
                    }
                }
                .takeWhile { !isTerminal(it) }
                .collect { }

            if (!acc.finished) {
                // Stream closed without a terminal event: treat as completed if anything arrived.
                if (acc.parts.isEmpty()) {
                    acc.fail("Hermes no devolvió ninguna respuesta")
                } else {
                    acc.apply(StreamEvent.Completed)
                }
            }
            succeeded = acc.error == null && !acc.stopped
            if (succeeded) internal.update { it.copy(connection = ConnectionState.Connected) }
        } catch (e: CancellationException) {
            acc.stop()
            throw e
        } catch (e: Exception) {
            acc.fail(e.toUserMessage())
            e.toConnectionState()?.let { c -> internal.update { it.copy(connection = c) } }
        } finally {
            withContext(NonCancellable) {
                val finalMsg = assistant.copy(parts = acc.parts, error = acc.error, isStreaming = false)
                if (finalMsg.parts.isNotEmpty() || finalMsg.error != null) {
                    try {
                        repo.saveMessage(threadId, finalMsg)
                        val snippet = finalMsg.parts.filterIsInstance<MessagePart.Text>()
                            .joinToString(" ") { it.text }
                        if (snippet.isNotBlank()) repo.touchThread(threadId, System.currentTimeMillis(), snippet)
                    } catch (e: Exception) {
                        // Persisting is best effort.
                    }
                    inFlight.value = finalMsg
                } else {
                    inFlight.value = null
                }
                internal.update { it.copy(isSending = false, error = acc.error) }
            }
        }

        if (succeeded) {
            flash(YumemiMood.HAPPY, 3_000)
            loadTranscript()
        }
    }
}
