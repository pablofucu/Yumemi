package com.pablo.yumemi.data.cron

import android.content.Context
import com.pablo.yumemi.data.chat.RemoteSession
import com.pablo.yumemi.data.chat.TranscriptParser
import com.pablo.yumemi.data.local.CronResultDao
import com.pablo.yumemi.data.local.CronResultEntity
import com.pablo.yumemi.data.remote.HermesClient
import com.pablo.yumemi.data.remote.HermesException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Failure with a ready-to-show Spanish message. */
class CronException(message: String) : Exception(message)

class CronRepository(
    private val client: HermesClient,
    context: Context,
    private val resultDao: CronResultDao,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences("yumemi_cron", Context.MODE_PRIVATE)

    private val _hiddenIds = MutableStateFlow(readHidden())

    /** Ids of jobs the user hid on this phone. */
    val hiddenIds: StateFlow<Set<String>> = _hiddenIds.asStateFlow()

    private val _jobs = MutableStateFlow<List<CronJob>?>(null)

    /** Last successful job list (all jobs, hidden included); null until the first load. */
    val jobs: StateFlow<List<CronJob>?> = _jobs.asStateFlow()

    /** Messages produced by cron runs seen on this phone, newest first. */
    val results: Flow<List<CronResult>> = resultDao.observeAll().map { rows ->
        rows.map { CronResult(it.id, it.jobId, it.jobName, it.runAt, it.text) }
    }

    /** Deleted results are remembered so the next sync does not bring them back. */
    suspend fun deleteResult(id: String) {
        val next = (prefs.getStringSet(KEY_DELETED, null).orEmpty() + id).toHashSet()
        prefs.edit().putStringSet(KEY_DELETED, next).apply()
        resultDao.delete(id)
    }

    private fun readHidden(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()

    fun setHidden(id: String, hidden: Boolean) {
        val next = _hiddenIds.value.toMutableSet().apply { if (hidden) add(id) else remove(id) }
        _hiddenIds.value = next
        prefs.edit().putStringSet(KEY_HIDDEN, HashSet(next)).apply()
    }

    suspend fun list(): List<CronJob> {
        val body = call(isList = true) {
            client.fetch(
                client.current(listOf("api", "jobs"), mapOf("include_disabled" to "true")).get().build(),
            )
        }
        val parsed = CronParser.parseList(body)
        _jobs.value = parsed
        try {
            syncResults(parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The job list is fine; missing results are picked up on the next refresh.
        }
        return parsed
    }

    /**
     * Each cron run is a Hermes session; its final answer is the message the bot delivers
     * (Telegram...). New finished runs are stored once, so they stay after Hermes prunes them.
     */
    private suspend fun syncResults(jobs: List<CronJob>) {
        val sessions = try {
            client.listCronSessions()
        } catch (e: HermesException.Http) {
            if (e.code == 404) return else throw e
        }
        val known = resultDao.ids().toHashSet() + prefs.getStringSet(KEY_DELETED, null).orEmpty()
        val names = jobs.associate { it.id to it.displayName }
        val now = System.currentTimeMillis()
        val found = sessions
            .filter { it.id !in known && it.isFinished(now) }
            .mapNotNull { s ->
                val text = TranscriptParser.finalAssistantText(client.messagesBody(s.id))
                    ?.takeUnless { it == SILENT_MARKER }
                    ?.let(::formatFailure)
                    ?: return@mapNotNull null
                val jobId = CRON_SESSION_ID.matchEntire(s.id)?.groupValues?.get(1).orEmpty()
                CronResultEntity(
                    id = s.id,
                    jobId = jobId,
                    jobName = names[jobId] ?: s.title.ifBlank { "Cronjob" },
                    runAt = s.startedAt.takeIf { it > 0 } ?: s.updatedAt.takeIf { it > 0 } ?: now,
                    text = text,
                )
            }
        if (found.isNotEmpty()) resultDao.upsertAll(found)
    }

    /** A run that reports its own failure starts with [CRON_FAILURE] on its own line. */
    private fun formatFailure(text: String): String =
        if (text.startsWith(FAILURE_MARKER)) {
            "**Fallo del cronjob**\n\n" + text.removePrefix(FAILURE_MARKER).trim()
        } else {
            text
        }

    /** Runs in progress are skipped; without an end time, a run quiet for a while counts as done. */
    private fun RemoteSession.isFinished(now: Long): Boolean =
        endedAt > 0 || (updatedAt > 0 && now - updatedAt > UNFINISHED_GRACE_MS)

    suspend fun pause(id: String) = action(id, "pause")

    suspend fun resume(id: String) = action(id, "resume")

    suspend fun runNow(id: String) = action(id, "run")

    private suspend fun action(id: String, verb: String) {
        val body = call(isList = false) {
            client.fetch(
                client.current(listOf("api", "jobs", id, verb))
                    .post("{}".toRequestBody(JSON))
                    .build(),
            )
        }
        val updated = CronParser.parseOne(body) ?: return
        _jobs.update { list -> list?.map { if (it.id == updated.id) updated else it } }
    }

    private suspend fun <T> call(isList: Boolean, block: suspend () -> T): T =
        try {
            block()
        } catch (e: HermesException.Http) {
            throw when {
                e.code == 501 -> CronException("Hermes no tiene los cronjobs activados")
                e.code == 404 && isList ->
                    CronException("Tu versión de Hermes no tiene la API de cronjobs")
                e.code == 404 -> CronException("Ese cronjob ya no existe en Hermes")
                else -> e
            }
        }

    private companion object {
        const val KEY_HIDDEN = "hidden_ids"
        const val KEY_DELETED = "deleted_result_ids"
        /** Hermes marks a run that chose not to deliver anything with this. */
        const val SILENT_MARKER = "[SILENT]"
        const val FAILURE_MARKER = "[CRON_FAILURE]"
        const val UNFINISHED_GRACE_MS = 10 * 60_000L
        val CRON_SESSION_ID = Regex("^cron_(.+)_\\d{8}_\\d{6}$")
        val JSON = "application/json".toMediaType()
    }
}
