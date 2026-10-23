package com.pablo.yumemi.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pablo.yumemi.AppContainer
import com.pablo.yumemi.YumemiApplication
import com.pablo.yumemi.data.chat.ThreadActivityRules
import com.pablo.yumemi.data.feeds.FeedStatus
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.ui.Routes
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Checks the server every ~15 min (the Android minimum) and notifies about what is new: chats that
 * got an answer, cron results and feed reports. The first run, and runs while the app is on screen,
 * only record what exists so nothing old is announced later.
 */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as YumemiApplication).container
        if (!c.settings.isConfigured) return Result.success()
        val seen = SeenStore(applicationContext)
        val first = !seen.initialized
        val quiet = first || AppVisibility.visible

        step { checkChats(c, seen, quiet) }
        step { checkCron(c, seen, quiet) }
        step { checkFeeds(c, seen, quiet) }
        if (first) seen.initialized = true
        return Result.success()
    }

    /** Each source fails on its own (cron or feeds may not be set up, or the VPN is off). */
    private suspend fun step(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun checkChats(c: AppContainer, seen: SeenStore, quiet: Boolean) {
        val now = System.currentTimeMillis()
        val baseline = seen.baseline(now)
        var sent = 0
        for (s in c.repository.refreshRecent()) {
            if (s.updatedAt <= 0 || s.updatedAt <= seen.chat(s.id, baseline)) continue
            if (c.turnRunner.isRunning(s.id)) continue
            val last = c.repository.inspectThread(s.id, s.updatedAt) ?: continue
            // Still working (the user spoke last, or a tool is running): look again next time.
            if (last.role != Role.ASSISTANT) continue
            if (ThreadActivityRules.fromTranscript(listOf(last), s.updatedAt, now) != null) continue
            seen.setChat(s.id, s.updatedAt)
            val text = ThreadActivityRules.snippetOf(last)
            if (quiet || text.isEmpty() || sent >= MAX_PER_KIND) continue
            val title = c.repository.rawTitle(s.id).orEmpty().ifBlank { s.title.ifBlank { s.preview.take(40) } }
            Notifier.chat(applicationContext, s.id, title, text, Routes.chat(s.id))
            sent++
        }
    }

    private suspend fun checkCron(c: AppContainer, seen: SeenStore, quiet: Boolean) {
        c.cronRepository.list() // also stores the results of new runs
        val known = seen.cronIds()
        val fresh = c.cronRepository.results.first().filter { it.id !in known }
        if (fresh.isEmpty()) return
        seen.addCronIds(fresh.map { it.id })
        if (quiet) return
        fresh.sortedByDescending { it.runAt }.take(MAX_PER_KIND).forEach {
            Notifier.report(applicationContext, "cron:${it.id}", it.jobName, plain(it.text), Routes.RESULTS)
        }
    }

    private suspend fun checkFeeds(c: AppContainer, seen: SeenStore, quiet: Boolean) {
        if (c.settings.feedsUrl.isBlank()) return
        c.feedRepository.refreshAll()
        val infos = c.feedRepository.feeds.value.associateBy { it.tag }
        var sent = 0
        for ((tag, env) in c.feedRepository.latest.value) {
            if (env.runAt <= seen.feed(tag, 0)) continue
            seen.setFeed(tag, env.runAt)
            if (quiet || sent >= MAX_PER_KIND) continue
            val info = infos[tag]
            val title = listOfNotNull(info?.emoji?.takeIf { it.isNotBlank() }, info?.title ?: tag).joinToString(" ")
            val text = if (env.status == FeedStatus.ERROR) env.error ?: env.summary else env.summary
            Notifier.report(applicationContext, "feed:$tag", title, text, Routes.feed(tag))
            sent++
        }
    }

    private fun plain(md: String) = md.replace(Regex("[*#`>_]+"), "").replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val NAME = "yumemi-notify"
        private const val MAX_PER_KIND = 3

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NotifyWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // Best effort: scheduling must never take the app down (e.g. WorkManager missing in tests).
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
