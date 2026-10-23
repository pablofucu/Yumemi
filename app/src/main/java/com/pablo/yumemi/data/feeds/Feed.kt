package com.pablo.yumemi.data.feeds

import com.pablo.yumemi.data.remote.arr
import com.pablo.yumemi.data.remote.num
import com.pablo.yumemi.data.remote.parseJson
import com.pablo.yumemi.data.remote.str
import com.pablo.yumemi.data.remote.time
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * "Feeds para la app Yumemi": every Hermes automation publishes ~/.hermes/feeds/<tag>/latest.json
 * (and history.jsonl) with a common envelope; index.json lists the feeds. Served by
 * `tailscale serve` under /feeds, without auth (tailnet only).
 */

/** One entry of index.json: a feed is one tab in the app. */
data class FeedInfo(
    val tag: String,
    val title: String,
    val emoji: String,
    val jobId: String?,
)

enum class FeedStatus { OK, ERROR, EMPTY }

/** The common envelope of latest.json and of each history.jsonl line. */
data class FeedEnvelope(
    val tag: String,
    val jobId: String?,
    val runAt: Long,
    val status: FeedStatus,
    val summary: String,
    val view: List<FeedBlock>,
    val error: String?,
)

/** Generic blocks the app draws for any tag, so a new automation shows up without app changes. */
sealed interface FeedBlock {
    data class Stat(val label: String, val value: String, val delta: String?) : FeedBlock
    data class KeyValue(val items: List<Pair<String, String>>) : FeedBlock
    data class Table(val columns: List<String>, val rows: List<List<String>>) : FeedBlock
    data class Series(val label: String, val unit: String, val points: List<Pair<String, Double>>) : FeedBlock
    data class Text(val text: String) : FeedBlock
    data class Link(val label: String, val url: String) : FeedBlock
}

/** Defensive parser for the feed files. Never throws; unknown blocks are skipped. */
object FeedParser {

    /** Tags become directory names in the local cache, so only a safe slug is accepted. */
    private val TAG = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

    fun isValidTag(tag: String): Boolean = TAG.matches(tag)

    fun parseIndex(body: String): List<FeedInfo> {
        val root = parseJson(body) as? JsonObject ?: return emptyList()
        return root.arr("feeds").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val tag = o.str("tag")?.takeIf(::isValidTag) ?: return@mapNotNull null
            FeedInfo(
                tag = tag,
                title = o.str("title") ?: tag.replaceFirstChar { it.uppercase() },
                emoji = o.str("emoji").orEmpty(),
                jobId = o.str("job_id"),
            )
        }.distinctBy { it.tag }
    }

    fun parseEnvelope(body: String): FeedEnvelope? =
        (parseJson(body) as? JsonObject)?.let(::envelope)

    /** history.jsonl: one envelope per line, oldest first in the file; returned newest first. */
    fun parseHistory(body: String): List<FeedEnvelope> =
        body.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parseEnvelope(it) }
            .sortedByDescending { it.runAt }
            .toList()

    private fun envelope(o: JsonObject): FeedEnvelope? {
        val tag = o.str("tag") ?: return null
        val status = when (o.str("status")?.lowercase()) {
            "error", "failed", "failure" -> FeedStatus.ERROR
            "empty" -> FeedStatus.EMPTY
            else -> FeedStatus.OK
        }
        return FeedEnvelope(
            tag = tag,
            jobId = o.str("job_id"),
            runAt = o.time("run_at"),
            status = status,
            summary = o.str("summary").orEmpty(),
            view = o.arr("view").orEmpty().mapNotNull { (it as? JsonObject)?.let(::block) },
            error = o.str("error"),
        )
    }

    private fun block(o: JsonObject): FeedBlock? = when (o.str("type")?.lowercase()) {
        "stat" -> o.str("value")?.let { FeedBlock.Stat(o.str("label").orEmpty(), it, o.str("delta")) }
        "kv" -> o.arr("items").orEmpty()
            .mapNotNull { pair(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { FeedBlock.KeyValue(it) }
        "table" -> {
            val columns = o.arr("columns").orEmpty().map { cell(it) }
            val rows = o.arr("rows").orEmpty().mapNotNull { r -> (r as? JsonArray)?.map { cell(it) } }
            if (columns.isEmpty() && rows.isEmpty()) null else FeedBlock.Table(columns, rows)
        }
        "series" -> o.arr("points").orEmpty()
            .mapIndexedNotNull { i, p -> point(i, p) }
            .takeIf { it.isNotEmpty() }
            ?.let { FeedBlock.Series(o.str("label").orEmpty(), o.str("unit").orEmpty(), it) }
        "link" -> o.str("url")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.let { FeedBlock.Link(o.str("label") ?: it, it) }
        // "text" and any future type that carries text degrade to a paragraph.
        else -> o.str("text")?.let { FeedBlock.Text(it) }
    }

    private fun cell(e: JsonElement): String = when (e) {
        is JsonNull -> ""
        is JsonPrimitive -> e.contentOrNull.orEmpty()
        else -> e.toString()
    }

    private fun pair(e: JsonElement): Pair<String, String>? = when (e) {
        is JsonArray -> if (e.size >= 2) cell(e[0]) to cell(e[1]) else null
        is JsonObject -> e.str("label", "key")?.let { it to (e.str("value") ?: "") }
        else -> null
    }

    /** [label, value], {x|label|date, y|value}, or a bare number (labelled by its position). */
    private fun point(index: Int, e: JsonElement): Pair<String, Double>? = when (e) {
        is JsonArray -> if (e.size >= 2) (e[1] as? JsonPrimitive)?.doubleOrNull?.let { cell(e[0]) to it } else null
        is JsonObject -> e.num("y", "value")?.let { (e.str("x", "label", "date") ?: "${index + 1}") to it }
        is JsonPrimitive -> e.doubleOrNull?.let { "${index + 1}" to it }
    }
}
