package com.pablo.yumemi.ui.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pablo.yumemi.data.HermesRepository
import com.pablo.yumemi.data.remote.HermesException
import com.pablo.yumemi.data.remote.NetworkMonitor
import com.pablo.yumemi.data.remote.toConnectionState
import com.pablo.yumemi.data.remote.toUserMessage
import com.pablo.yumemi.data.settings.SettingsStore
import com.pablo.yumemi.domain.ChatThread
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

internal fun ChatThread.withDisplayTitle(): ChatThread =
    if (title.isNotBlank()) this else copy(title = preview.trim().take(40).ifBlank { UNTITLED })

class ThreadListViewModel(
    private val repo: HermesRepository,
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

    val uiState: StateFlow<ThreadListUiState> =
        combine(repo.observeThreads(), internal) { threads, i ->
            val sleeping = i.connection is ConnectionState.NotConfigured || i.connection is ConnectionState.Unreachable
            ThreadListUiState(
                threads = threads.map { it.withDisplayTitle() },
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

    fun refresh() = startRefresh(keepError = false)

    private fun startRefresh(keepError: Boolean, retryOnUnreachable: Boolean = false) {
        if (!settings.isConfigured) {
            internal.update { it.copy(connection = ConnectionState.NotConfigured, isRefreshing = false) }
            return
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            internal.update { it.copy(isRefreshing = true, error = if (keepError) it.error else null) }
            val failure = refreshOnce()
            if (failure is HermesException.Unreachable && retryOnUnreachable) {
                // Startup over a VPN that is still waking up: one more try shortly after.
                delay(3_000)
                internal.update { it.copy(isRefreshing = true) }
                refreshOnce()
            }
        }
    }

    /** One refresh attempt; updates the state and returns the failure, if any. */
    private suspend fun refreshOnce(): Exception? =
        try {
            repo.refreshThreads()
            internal.update { it.copy(connection = ConnectionState.Connected, isRefreshing = false) }
            flashHappy()
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

    private fun flashHappy() {
        happyJob?.cancel()
        happyJob = viewModelScope.launch {
            internal.update { it.copy(happy = true) }
            delay(2_500)
            internal.update { it.copy(happy = false) }
        }
    }
}
