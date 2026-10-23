package com.pablo.yumemi.ui.feeds

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pablo.yumemi.data.cron.CronJob
import com.pablo.yumemi.data.cron.CronRepository
import com.pablo.yumemi.data.feeds.FeedEnvelope
import com.pablo.yumemi.data.feeds.FeedException
import com.pablo.yumemi.data.feeds.FeedInfo
import com.pablo.yumemi.data.feeds.FeedRepository
import com.pablo.yumemi.data.remote.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One card of the overview: a feed, its last report and the cron job behind it. */
data class FeedCardState(
    val feed: FeedInfo,
    val latest: FeedEnvelope?,
    val job: CronJob?,
)

data class FeedsUiState(
    val cards: List<FeedCardState> = emptyList(),
    val isRefreshing: Boolean = false,
    /** False until the cache (or a first refresh) has been read. */
    val loaded: Boolean = false,
    val error: String? = null,
)

data class FeedDetailUiState(
    val feed: FeedInfo? = null,
    val latest: FeedEnvelope? = null,
    /** Earlier runs, newest first, without the one in [latest]. */
    val history: List<FeedEnvelope> = emptyList(),
    val job: CronJob? = null,
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

private data class LoadState(
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

private fun jobFor(jobId: String?, jobs: List<CronJob>?): CronJob? =
    jobId?.let { id -> jobs?.firstOrNull { it.id == id } }

/**
 * Runs [block] as the single refresh of a screen, reporting progress and a Spanish error in [load].
 * Afterwards the cron job list is reloaded for the "última · próxima" lines; its failure is not shown.
 */
private fun CoroutineScope.launchRefresh(
    load: MutableStateFlow<LoadState>,
    cronRepo: CronRepository,
    manual: Boolean,
    block: suspend () -> Unit,
): Job = launch {
    if (manual) load.update { it.copy(isRefreshing = true) }
    try {
        block()
        load.update { it.copy(isRefreshing = false, loaded = true, error = null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val text = if (e is FeedException) (e.message ?: "Error en los informes") else e.toUserMessage()
        load.update { it.copy(isRefreshing = false, loaded = true, error = text) }
    }
    try {
        if (manual || cronRepo.jobs.value == null) cronRepo.list()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

/** The Informes tab: one card per automation feed. */
class FeedsViewModel(
    private val repo: FeedRepository,
    private val cronRepo: CronRepository,
) : ViewModel() {

    private val load = MutableStateFlow(LoadState())

    val uiState: StateFlow<FeedsUiState> =
        combine(repo.feeds, repo.latest, cronRepo.jobs, load) { feeds, latest, jobs, l ->
            FeedsUiState(
                cards = feeds.map { f ->
                    val env = latest[f.tag]
                    FeedCardState(f, env, jobFor(f.jobId ?: env?.jobId, jobs))
                },
                isRefreshing = l.isRefreshing,
                loaded = l.loaded,
                error = l.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedsUiState())

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            repo.loadCached()
            load.update { it.copy(loaded = it.loaded || repo.feeds.value.isNotEmpty()) }
        }
    }

    fun refresh(manual: Boolean = true) {
        if (!manual && refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launchRefresh(load, cronRepo, manual) { repo.refreshAll() }
    }

    fun dismissError() = load.update { it.copy(error = null) }
}

/** Full report of one feed (route argument "tag"), with its earlier runs. */
class FeedDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repo: FeedRepository,
    private val cronRepo: CronRepository,
) : ViewModel() {

    private val tag: String = checkNotNull(savedStateHandle["tag"])
    private val load = MutableStateFlow(LoadState())

    val uiState: StateFlow<FeedDetailUiState> =
        combine(repo.feeds, repo.latest, repo.history, cronRepo.jobs, load) { feeds, latest, history, jobs, l ->
            val env = latest[tag]
            val feed = feeds.firstOrNull { it.tag == tag }
                ?: env?.let { FeedInfo(tag, tag.replaceFirstChar { c -> c.uppercase() }, "", it.jobId) }
            FeedDetailUiState(
                feed = feed,
                latest = env,
                history = history[tag].orEmpty()
                    .filter { env == null || it.runAt != env.runAt }
                    .take(MAX_HISTORY_SHOWN),
                job = jobFor(feed?.jobId ?: env?.jobId, jobs),
                isRefreshing = l.isRefreshing,
                loaded = l.loaded || env != null,
                error = l.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedDetailUiState())

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch { repo.loadCached() }
    }

    fun refresh(manual: Boolean = true) {
        if (!manual && refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launchRefresh(load, cronRepo, manual) { repo.refresh(tag) }
    }

    fun dismissError() = load.update { it.copy(error = null) }

    private companion object {
        const val MAX_HISTORY_SHOWN = 14
    }
}
