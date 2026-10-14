package com.pablo.yumemi.domain

/*
 * Shared contract between the data layer (ViewModels, repository, Hermes client)
 * and the UI layer (screens, theme, chibi). Both sides code against these types.
 * Change this file only if both sides are updated together.
 */

// ---------- Domain models ----------

data class ChatThread(
    val id: String,          // Hermes session id
    val title: String,
    val updatedAt: Long,     // epoch millis
    val preview: String,     // last message snippet, may be empty
)

enum class Role { USER, ASSISTANT }

enum class ToolStatus { RUNNING, OK, FAILED }

/** PENDING waits for the user; SENDING while the answer is in flight; EXPIRED when the turn ended unanswered. */
enum class ApprovalStatus { PENDING, SENDING, ANSWERED, EXPIRED }

/** One piece of an assistant (or user) message, in the order it happened. */
sealed interface MessagePart {
    data class Text(val text: String) : MessagePart
    /** Model reasoning ("thinking"). Collapsed by default in the UI. */
    data class Reasoning(val text: String) : MessagePart
    /** Mid-turn commentary the agent says while working. */
    data class Commentary(val text: String) : MessagePart
    data class ToolCall(
        val id: String,
        val tool: String,
        val argsPreview: String,
        val resultPreview: String?,
        val status: ToolStatus,
        val durationSec: Double?,
    ) : MessagePart
    data class Subagent(
        val id: String,
        val summary: String,
        val status: ToolStatus,
        val durationSec: Double?,
    ) : MessagePart
    /**
     * Hermes paused before a dangerous command and asks for permission
     * (`approval.request`). [choices] are wire values: once, session, always, deny.
     */
    data class Approval(
        val requestId: String,
        val runId: String,
        val command: String,
        val description: String,
        val choices: List<String>,
        val status: ApprovalStatus,
        val answer: String?,
    ) : MessagePart
}

data class ChatMessage(
    val id: String,
    val role: Role,
    val parts: List<MessagePart>,
    val createdAt: Long,
    val isStreaming: Boolean = false,
    val error: String? = null,
)

/** Drives the chibi's expression. */
enum class YumemiMood { IDLE, HAPPY, THINKING, WORKING, SURPRISED, ERROR, SLEEPING }

sealed interface ConnectionState {
    data object Unknown : ConnectionState
    data object Checking : ConnectionState
    data object Connected : ConnectionState
    data object Unauthorized : ConnectionState
    data object NotConfigured : ConnectionState
    data class Unreachable(val message: String) : ConnectionState
}

// ---------- UI state exposed by ViewModels (StateFlow<...>) ----------

data class ThreadListUiState(
    val threads: List<ChatThread> = emptyList(),
    val connection: ConnectionState = ConnectionState.Unknown,
    val isRefreshing: Boolean = false,
    val mood: YumemiMood = YumemiMood.IDLE,
    val error: String? = null,
)

data class ChatUiState(
    val threadId: String = "",
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val mood: YumemiMood = YumemiMood.IDLE,
    val isSending: Boolean = false,
    val connection: ConnectionState = ConnectionState.Unknown,
    val error: String? = null,
)

data class SettingsUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val testResult: ConnectionState = ConnectionState.Unknown,
    val insecureWarning: Boolean = false, // true for http:// URLs that are not on a private/Tailscale network
    val saved: Boolean = false,
)

/*
 * ViewModels (implemented by the data layer, package com.pablo.yumemi.ui.<screen>):
 *
 *  ThreadListViewModel  : val uiState: StateFlow<ThreadListUiState>
 *                         fun refresh()
 *                         fun createThread(onCreated: (threadId: String) -> Unit)
 *                         fun renameThread(id: String, title: String)
 *                         fun deleteThread(id: String)
 *
 *  ChatViewModel        : val uiState: StateFlow<ChatUiState>   (threadId from SavedStateHandle key "threadId")
 *                         fun send(text: String)
 *                         fun stop()
 *                         fun retryLast()
 *                         fun clearError()
 *                         fun answerApproval(requestId: String, choice: String)
 *
 *  SettingsViewModel    : val uiState: StateFlow<SettingsUiState>
 *                         fun onBaseUrlChange(v: String); fun onApiKeyChange(v: String)
 *                         fun testConnection(); fun save()
 *
 *  All are obtained in Compose with:
 *      viewModel<XViewModel>(factory = com.pablo.yumemi.AppViewModelProvider.Factory)
 *
 *  MainActivity (UI layer) calls com.pablo.yumemi.ui.YumemiApp() inside setContent.
 *  Navigation routes (UI layer): "threads", "chat/{threadId}", "settings".
 *  Start at "settings" when SettingsUiState.baseUrl is empty, otherwise "threads".
 */
