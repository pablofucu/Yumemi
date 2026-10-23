package com.pablo.yumemi.data.feeds

import android.content.Context
import com.pablo.yumemi.data.remote.HermesClient
import com.pablo.yumemi.data.remote.HermesException
import com.pablo.yumemi.data.settings.SettingsStore
import java.io.File
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** Failure with a ready-to-show Spanish message. */
class FeedException(message: String) : Exception(message)

/**
 * Reads the automation feeds (index.json, <tag>/latest.json, <tag>/history.jsonl) and keeps the
 * last good copy of each file on the phone, so the tabs open offline with the previous report.
 */
class FeedRepository(
    private val client: HermesClient,
    private val settings: SettingsStore,
    context: Context,
) {
    private val cacheDir = File(context.applicationContext.filesDir, "feeds")

    private val _feeds = MutableStateFlow<List<FeedInfo>>(emptyList())
    val feeds: StateFlow<List<FeedInfo>> = _feeds.asStateFlow()

    private val _latest = MutableStateFlow<Map<String, FeedEnvelope>>(emptyMap())
    val latest: StateFlow<Map<String, FeedEnvelope>> = _latest.asStateFlow()

    private val _history = MutableStateFlow<Map<String, List<FeedEnvelope>>>(emptyMap())
    /** Newest first; may include the run shown in [latest]. */
    val history: StateFlow<Map<String, List<FeedEnvelope>>> = _history.asStateFlow()

    private var cacheLoaded = false

    /** Fills the flows from the files saved by earlier refreshes. Cheap after the first call. */
    suspend fun loadCached() = withContext(Dispatchers.IO) {
        if (cacheLoaded) return@withContext
        cacheLoaded = true
        val feeds = readCache(INDEX)?.let(FeedParser::parseIndex).orEmpty()
        val latest = feeds.mapNotNull { f ->
            readCache("${f.tag}/$LATEST")?.let(FeedParser::parseEnvelope)?.let { f.tag to it }
        }.toMap()
        val history = feeds.mapNotNull { f ->
            readCache("${f.tag}/$HISTORY")?.let(FeedParser::parseHistory)?.let { f.tag to it }
        }.toMap()
        // A refresh that finished meanwhile wins over the cache.
        if (_feeds.value.isEmpty()) _feeds.value = feeds
        _latest.update { latest + it }
        _history.update { history + it }
    }

    suspend fun refreshIndex(): List<FeedInfo> {
        val body = get(INDEX) ?: throw FeedException("No hay index.json en los feeds de Hermes")
        val feeds = FeedParser.parseIndex(body)
        writeCache(INDEX, body)
        _feeds.value = feeds
        return feeds
    }

    /** Index plus the latest report of every feed, for the overview. */
    suspend fun refreshAll() {
        val feeds = refreshIndex()
        coroutineScope {
            feeds.map { f -> async { refreshLatest(f.tag) } }.awaitAll()
        }
    }

    /** Latest report of [tag] and its history, for the detail screen. */
    suspend fun refresh(tag: String) {
        refreshLatest(tag)
        refreshHistory(tag)
    }

    private suspend fun refreshLatest(tag: String) {
        if (!FeedParser.isValidTag(tag)) return
        val body = get("$tag/$LATEST")
        if (body == null) {
            _latest.update { it - tag }
            return
        }
        val env = FeedParser.parseEnvelope(body)
            ?: throw FeedException("El informe de «$tag» no tiene un formato válido")
        writeCache("$tag/$LATEST", body)
        _latest.update { it + (tag to env) }
    }

    /** A missing history.jsonl is fine: the feed just has no earlier runs to show. */
    private suspend fun refreshHistory(tag: String) {
        if (!FeedParser.isValidTag(tag)) return
        val history = get("$tag/$HISTORY") ?: return
        val trimmed = history.lineSequence().filter { it.isNotBlank() }.toList().takeLast(MAX_HISTORY)
            .joinToString("\n")
        writeCache("$tag/$HISTORY", trimmed)
        _history.update { it + (tag to FeedParser.parseHistory(trimmed)) }
    }

    /** Body of a feed file, or null when it does not exist (404). */
    private suspend fun get(path: String): String? {
        val base = settings.feedsUrl
        if (base.isBlank()) throw HermesException.NotConfigured()
        val url = "${base.trimEnd('/')}/$path".toHttpUrlOrNull()
            ?: throw FeedException("La URL de los informes no es válida")
        val rb = Request.Builder().url(url).header("Accept", "application/json").get()
        // The Hermes key only goes to the Hermes server itself (same scheme, host and port).
        val key = settings.apiKey.value
        if (key.isNotBlank() && sameOrigin(url, settings.baseUrl.value)) rb.header("Authorization", "Bearer $key")
        val request = rb.build()
        return try {
            client.fetch(request)
        } catch (e: HermesException.Http) {
            if (e.code == 404) null else throw e
        } catch (e: HermesException.Unreachable) {
            // Without Tailscale the *.ts.net name resolves to the public Funnel relays, which only
            // carry Hermes' funneled port: /feeds (tailnet only) then fails the TLS handshake.
            if (e.cause is SSLException && url.host.endsWith(".ts.net") && url.port == 443) {
                throw FeedException(
                    "Los informes solo se ven dentro de tu tailnet: activa Tailscale en el móvil " +
                        "(con «Use Tailscale DNS») y vuelve a intentarlo.",
                )
            }
            throw e
        }
    }

    private fun sameOrigin(url: HttpUrl, base: String): Boolean {
        val b = base.toHttpUrlOrNull() ?: return false
        return url.scheme == b.scheme && url.host == b.host && url.port == b.port
    }

    private fun readCache(path: String): String? =
        runCatching { File(cacheDir, path).takeIf { it.isFile }?.readText() }.getOrNull()

    private suspend fun writeCache(path: String, body: String) = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(cacheDir, path)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        const val INDEX = "index.json"
        const val LATEST = "latest.json"
        const val HISTORY = "history.jsonl"
        const val MAX_HISTORY = 120
    }
}
