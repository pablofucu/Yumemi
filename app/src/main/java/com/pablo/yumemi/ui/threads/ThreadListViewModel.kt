package com.pablo.yumemi.ui.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pablo.yumemi.data.HermesRepository
import com.pablo.yumemi.data.chat.ThreadActivityRules
import com.pablo.yumemi.data.chat.TurnRunner
import com.pablo.yumemi.data.remote.HermesException
import com.pablo.yumemi.data.remote.NetworkMonitor
import com.pablo.yumemi.data.remote.toConnectionState
import com.pablo.yumemi.data.remote.toUserMessage
import com.pablo.yumemi.data.settings.SettingsStore
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.ChatThread
import com.pablo.yumemi.domain.ThreadActivity
import com.pablo.yumemi.domain.ConnectionState
import com.pablo.yumemi.domain.ThreadListUiState
import com.pablo.yumemi.domain.YumemiMood
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Placeholder shown for threads without a title. */
internal const val UNTITLED = "Conversación nueva"

/** How often the list checks the server for turns other clients are running. */
private const val POLL_MS = 10_000L
/** Threads whose latest message is fetched after each refresh. */
private const val BACKFILL_LIMIT = 6
/** Between full listings (all pages, cron runs included) a refresh only reads the first page. */
private const val FULL_SYNC_MS = 5 * 60_000L

internal fun ChatThread.withDisplayTitle(): ChatThread =
    if (title.isNotBlank()) this else copy(title = preview.trim().take(40).ifBlank { UNTITLED })

class ThreadListViewModel(
    private val repo: HermesRepository,
    private val runner: TurnRunner,
    private val settings: SettingsStore,
    network: NetworkMonitor,
) : ViewModel() {

    private data class Internal(
        val connection: ConnectionState,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val happy: Boolean = false,
    )

    private val internal = MutableStateFlow(
        Internal(connection = if (settings.isConfigured) ConnectionState.Unknown else ConnectionState.NotConfigured),
    )

    /** Turns other clients seem to be running, from the last poll. */
    private val external = MutableStateFlow<Map<String, ThreadActivity>>(emptyMap())

    /** Latest server message seen per thread, with the updatedAt it was read at. */
    private val inspected = HashMap<String, Pair<Long, ChatMessage?>>()

    val uiState: StateFlow<ThreadListUiState> =
        combine(repo.observeThreads(), internal, runner.running, external) { threads, i, live, ext ->
            val sleeping = i.connection is ConnectionState.NotConfigured || i.connection is ConnectionState.Unreachable
            ThreadListUiState(
                threads = threads.map { t ->
                    val activity = live[t.id]?.let { ThreadActivityRules.fromLiveTurn(it.message) } ?: ext[t.id]
                    t.withDisplayTitle().copy(activity = activity)
                },
                connection = i.connection,
                isRefreshing = i.isRefreshing,
                mood = when {
                    sleeping -> YumemiMood.SLEEPING
                    i.happy -> YumemiMood.HAPPY
                    else -> YumemiMood.IDLE
                },
                error = i.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadListUiState(connection = internal.value.connection))

    private var refreshJob: Job? = null
    private var lastFullSync = 0L
    private var backfillJob: Job? = null
    private var happyJob: Job? = null

    init {
        // Refresh at start and whenever URL / key change in Settings.
        viewModelScope.launch {
            combine(settings.baseUrl, settings.apiKey) { u, k -> u to k }
                .distinctUntilChanged()
                .collect { startRefresh(keepError = false, retryOnUnreachable = true) }
        }
        // The VPN often comes up after the radio: retry as soon as a network (re)appears.
        viewModelScope.launch {
            network.availableTick.drop(1).collect {
                if (internal.value.connection !is ConnectionState.Connected) refresh()
            }
        }
    }

    fun refresh() = startRefresh(keepError = false, forceFull = true)

    private fun startRefresh(keepError: Boolean, retryOnUnreachable: Boolean = false, forceFull: Boolean = false) {
        if (!settings.isConfigured) {
            internal.update { it.copy(connection = ConnectionState.NotConfigured, isRefreshing = false) }
            return
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            internal.update { it.copy(isRefreshing = true, error = if (keepError) it.error else null) }
            val failure = refreshOnce(forceFull)
            if (failure is HermesException.Unreachable && retryOnUnreachable) {
                // Startup over a VPN that is still waking up: one more try shortly after.
                delay(3_000)
                internal.update { it.copy(isRefreshing = true) }
                refreshOnce(forceFull)
            }
        }
    }

    /** One refresh attempt; updates the state and returns the failure, if any. */
    private suspend fun refreshOnce(forceFull: Boolean): Exception? =
        try {
            val full = forceFull || System.currentTimeMillis() - lastFullSync > FULL_SYNC_MS
            if (full) {
                // Connected (and no spinner) as soon as the first page is in; the rest keeps syncing quietly.
                repo.refreshThreads(onFirstPage = {
                    internal.update { it.copy(connection = ConnectionState.Connected, isRefreshing = false, error = null) }
                    flashHappy()
                })
                lastFullSync = System.currentTimeMillis()
            } else {
                repo.refreshRecent()
            }
            internal.update { it.copy(connection = ConnectionState.Connected, isRefreshing = false) }
            backfillLastMessages()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            internal.update {
                it.copy(
                    connection = e.toConnectionState() ?: it.connection,
                    isRefreshing = false,
                    error = e.toUserMessage(),
                )
            }
            e
        }

    fun createThread(onCreated: (threadId: String) -> Unit) {
        viewModelScope.launch {
            try {
                val id = repo.createThread()
                internal.update { it.copy(connection = ConnectionState.Connected, error = null) }
                onCreated(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                internal.update { it.copy(connection = e.toConnectionState() ?: it.connection, error = e.toUserMessage()) }
            }
        }
    }

    fun renameThread(id: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            try {
                repo.renameThread(id, t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                internal.update { it.copy(error = e.toUserMessage()) }
                startRefresh(keepError = true)
            }
        }
    }

    fun deleteThread(id: String) {
        viewModelScope.launch {
            try {
                repo.deleteThread(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                internal.update { it.copy(error = e.toUserMessage()) }
                startRefresh(keepError = true)
            }
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { repo.setPinned(id, pinned) }
    }

    /**
     * Keeps the activity of recently moved threads up to date while the list is on screen.
     * Call from a coroutine that lives as long as the screen is resumed.
     */
    suspend fun watchActivity() {
        while (true) {
            if (settings.isConfigured && internal.value.connection is ConnectionState.Connected && refreshJob?.isActive != true) pollActivity()
            delay(POLL_MS)
        }
    }

    private suspend fun pollActivity() {
        val page = try {
            repo.refreshRecent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // the next full refresh reports problems
        }
        val now = System.currentTimeMillis()
        val localRunning = runner.running.value.keys
        val next = HashMap<String, ThreadActivity>()
        for (s in page) {
            if (s.id in localRunning || s.endedAt > 0) continue
            if (now - s.updatedAt >= ThreadActivityRules.TOOL_WAIT_MS) continue
            val cached = inspected[s.id]
            val last = if (cached != null && cached.first == s.updatedAt) {
                cached.second
            } else {
                try {
                    repo.inspectThread(s.id, s.updatedAt).also { inspected[s.id] = s.updatedAt to it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
            }
            ThreadActivityRules.fromTranscript(listOfNotNull(last), s.updatedAt, now)?.let { next[s.id] = it }
        }
        external.value = next
    }

    /** Fetches the latest message of recent threads whose stored one is missing or stale. */
    private fun backfillLastMessages() {
        if (backfillJob?.isActive == true) return
        backfillJob = viewModelScope.launch {
            for ((id, updatedAt) in repo.threadsWithStaleLast(BACKFILL_LIMIT)) {
                if (runner.isRunning(id)) continue
                try {
                    val last = repo.inspectThread(id, updatedAt)
                    inspected[id] = updatedAt to last
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best effort: tried again on the next refresh.
                }
            }
        }
    }

    private fun flashHappy() {
        happyJob?.cancel()
        happyJob = viewModelScope.launch {
            internal.update { it.copy(happy = true) }
            delay(2_500)
            internal.update { it.copy(happy = false) }
        }
    }
}
