package com.pablo.yumemi.data.cron

import android.content.Context
import com.pablo.yumemi.data.local.CronResultDao
import com.pablo.yumemi.data.local.CronResultEntity
import com.pablo.yumemi.data.remote.HermesClient
import com.pablo.yumemi.data.remote.HermesException
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

    suspend fun deleteResult(id: String) = resultDao.delete(id)

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
        // Keep each run's message: Hermes only reports the latest one, so it is saved as it is seen.
        val found = parsed.mapNotNull { it.toResult() }
        if (found.isNotEmpty()) {
            resultDao.upsertAll(found.map { CronResultEntity(it.id, it.jobId, it.jobName, it.runAt, it.text) })
        }
        return parsed
    }

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
        val JSON = "application/json".toMediaType()
    }
}
