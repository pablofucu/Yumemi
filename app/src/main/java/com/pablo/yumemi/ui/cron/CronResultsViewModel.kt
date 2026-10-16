package com.pablo.yumemi.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pablo.yumemi.data.cron.CronException
import com.pablo.yumemi.data.cron.CronRepository
import com.pablo.yumemi.data.cron.CronResult
import com.pablo.yumemi.data.remote.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CronResultsUiState(
    /** Newest first. */
    val results: List<CronResult> = emptyList(),
    val isRefreshing: Boolean = false,
    /** False until the stored results have been read once. */
    val loaded: Boolean = false,
    val error: String? = null,
)

class CronResultsViewModel(private val repo: CronRepository) : ViewModel() {

    private data class Internal(val isRefreshing: Boolean = false, val error: String? = null)

    private val internal = MutableStateFlow(Internal())

    val uiState: StateFlow<CronResultsUiState> =
        combine(repo.results, internal) { results, i ->
            CronResultsUiState(
                results = results,
                isRefreshing = i.isRefreshing,
                loaded = true,
                error = i.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CronResultsUiState())

    private var refreshJob: Job? = null

    /** Reloading the jobs is what captures their latest message into the stored results. */
    fun refresh(manual: Boolean = true) {
        if (!manual && refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (manual) internal.update { it.copy(isRefreshing = true) }
            try {
                repo.list()
                internal.update { it.copy(isRefreshing = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = if (e is CronException) (e.message ?: "Error de cronjobs") else e.toUserMessage()
                internal.update { it.copy(isRefreshing = false, error = text) }
            }
        }
    }

    fun dismissError() = internal.update { it.copy(error = null) }

    fun delete(id: String) {
        viewModelScope.launch { repo.deleteResult(id) }
    }
}
