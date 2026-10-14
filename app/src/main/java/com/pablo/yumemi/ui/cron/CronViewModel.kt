package com.pablo.yumemi.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pablo.yumemi.data.cron.CronException
import com.pablo.yumemi.data.cron.CronJob
import com.pablo.yumemi.data.cron.CronRepository
import com.pablo.yumemi.data.cron.CronStatus
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

data class CronItem(val job: CronJob, val hidden: Boolean)

data class CronUiState(
    /** Jobs to render: the visible ones, plus the hidden ones (last) when [showHidden]. */
    val jobs: List<CronItem> = emptyList(),
    val totalCount: Int = 0,
    val hiddenCount: Int = 0,
    val showHidden: Boolean = false,
    val isRefreshing: Boolean = false,
    /** False until a job list has been loaded once. */
    val loaded: Boolean = false,
    val error: String? = null,
    val busyIds: Set<String> = emptySet(),
)

class CronViewModel(private val repo: CronRepository) : ViewModel() {

    private data class Internal(
        val showHidden: Boolean = false,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val busyIds: Set<String> = emptySet(),
    )

    private val internal = MutableStateFlow(Internal())

    val uiState: StateFlow<CronUiState> =
        combine(repo.jobs, repo.hiddenIds, internal) { jobs, hidden, i ->
            val all = jobs.orEmpty()
            val (hiddenJobs, visibleJobs) = all.partition { it.id in hidden }
            val items = visibleJobs.map { CronItem(it, false) } +
                if (i.showHidden) hiddenJobs.map { CronItem(it, true) } else emptyList()
            CronUiState(
                jobs = items,
                totalCount = all.size,
                hiddenCount = hiddenJobs.size,
                // Nothing left to reveal -> behave as if the toggle were off.
                showHidden = i.showHidden && hiddenJobs.isNotEmpty(),
                isRefreshing = i.isRefreshing,
                loaded = jobs != null,
                error = i.error,
                busyIds = i.busyIds,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CronUiState())

    private var refreshJob: Job? = null

    /** [manual] refreshes show the pull-to-refresh spinner; silent ones skip if one is already running. */
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
                internal.update { it.copy(isRefreshing = false, error = e.userText()) }
            }
        }
    }

    fun toggleShowHidden() = internal.update { it.copy(showHidden = !it.showHidden) }

    fun dismissError() = internal.update { it.copy(error = null) }

    fun setHidden(id: String, hidden: Boolean) = repo.setHidden(id, hidden)

    fun togglePause(job: CronJob) = act(job.id) {
        if (job.status == CronStatus.PAUSED) repo.resume(job.id) else repo.pause(job.id)
    }

    fun runNow(job: CronJob) = act(job.id) { repo.runNow(job.id) }

    private fun act(id: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            internal.update { it.copy(busyIds = it.busyIds + id) }
            try {
                block()
                internal.update { it.copy(error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                internal.update { it.copy(error = e.userText()) }
            } finally {
                internal.update { it.copy(busyIds = it.busyIds - id) }
            }
            refresh(manual = false)
        }
    }

    private fun Throwable.userText(): String =
        if (this is CronException) (message ?: "Error de cronjobs") else toUserMessage()
}
