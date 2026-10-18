package com.pablo.yumemi.data.cron

import com.pablo.yumemi.data.remote.arr
import com.pablo.yumemi.data.remote.bool
import com.pablo.yumemi.data.remote.num
import com.pablo.yumemi.data.remote.parseJson
import com.pablo.yumemi.data.remote.str
import com.pablo.yumemi.data.remote.time
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Coarse state shown to the user, derived from the several state-ish fields of a job. */
enum class CronStatus { ACTIVE, PAUSED, RUNNING, FAILED, COMPLETED }

data class CronJob(
    val id: String,
    val name: String?,
    val prompt: String,
    val scheduleDisplay: String,
    val enabled: Boolean,
    val state: String?,
    val pausedReason: String?,
    val createdAt: Long,
    val nextRunAt: Long,
    val lastRunAt: Long,
    val lastStatus: String?,
    val lastError: String?,
    val failureStreak: Int,
    val repeatTimes: Int?,
    val repeatCompleted: Int,
    val deliver: String?,
    val execStatus: String?,
    val execError: String?,
    /** Text the last run produced (the "daily message"), when Hermes exposes it. */
    val lastOutput: String? = null,
) {
    /** Name, or the first ~40 characters of the prompt when the job has none. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: prompt.trim().replace(Regex("\\s+"), " ").take(40).ifBlank { id }

    val status: CronStatus
        get() {
            val s = state?.lowercase()
            val x = execStatus?.lowercase()
            val last = lastStatus?.lowercase()
            return when {
                s == "running" || x == "running" || x == "claimed" -> CronStatus.RUNNING
                s == "completed" -> CronStatus.COMPLETED
                !enabled || s == "paused" -> CronStatus.PAUSED
                x == "failed" || last == "error" || last == "failed" || last == "failure" -> CronStatus.FAILED
                else -> CronStatus.ACTIVE
            }
        }

    val errorText: String?
        get() = (lastError ?: execError)?.takeIf { it.isNotBlank() }
}

/** One message produced by a cron job run. */
data class CronResult(
    val id: String,
    val jobId: String,
    val jobName: String,
    val runAt: Long,
    val text: String,
)

/** Defensive parser for the Hermes cron REST API. Never throws on odd bodies. */
object CronParser {

    fun parseList(body: String): List<CronJob> {
        val root = parseJson(body)
        val arr: JsonArray? = when (root) {
            is JsonObject -> root.arr("jobs")
            is JsonArray -> root
            else -> null
        }
        return arr?.mapNotNull { (it as? JsonObject)?.let { o -> parseJob(o) } }.orEmpty()
    }

    fun parseOne(body: String): CronJob? {
        val root = parseJson(body) as? JsonObject ?: return null
        val inner = root["job"] as? JsonObject ?: root
        return parseJob(inner)
    }

    /** Hermes does not document where a run's message lives, so try the likely names. */
    private val OUTPUT_KEYS = arrayOf(
        "output", "result", "response", "final_response", "last_output", "last_response", "last_result",
    )

    private fun parseJob(o: JsonObject): CronJob? {
        val id = o.str("id") ?: return null
        val schedule = o["schedule"]
        val scheduleObj = schedule as? JsonObject
        val scheduleText = o.str("schedule_display")
            ?: (schedule as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            ?: scheduleObj?.str("display", "expr", "cron", "run_at")
            ?: ""
        val repeat = o["repeat"] as? JsonObject
        val exec = o["latest_execution"] as? JsonObject
        return CronJob(
            id = id,
            name = o.str("name"),
            prompt = o.str("prompt") ?: "",
            scheduleDisplay = scheduleText,
            enabled = o.bool("enabled") ?: true,
            state = o.str("state"),
            pausedReason = o.str("paused_reason"),
            createdAt = o.time("created_at"),
            nextRunAt = o.time("next_run_at"),
            lastRunAt = o.time("last_run_at"),
            lastStatus = o.str("last_status"),
            lastError = o.str("last_error"),
            failureStreak = o.num("failure_streak")?.toInt() ?: 0,
            repeatTimes = repeat?.num("times")?.toInt(),
            repeatCompleted = repeat?.num("completed")?.toInt() ?: 0,
            deliver = o.str("deliver"),
            execStatus = exec?.str("status"),
            execError = exec?.str("error"),
            lastOutput = exec?.str(*OUTPUT_KEYS) ?: o.str(*OUTPUT_KEYS),
        )
    }
}
