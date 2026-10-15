package com.pablo.yumemi.data.remote

import com.pablo.yumemi.data.chat.RemoteSession
import com.pablo.yumemi.data.chat.SessionParser
import com.pablo.yumemi.data.settings.SettingsStore
import com.pablo.yumemi.domain.ConnectionState
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Per-request ephemeral system prompt telling Hermes that this app renders Markdown. */
private const val RENDER_HINT =
    "Client note from Yumemi, the Android chat app the user is talking to you through. This replaces the earlier API-server note about plain text: this app renders GitHub-flavored Markdown (headings, bold, italic, strikethrough, bullet and numbered lists, tables, fenced code blocks with syntax highlighting, block quotes, links and horizontal rules). Use Markdown whenever it makes an answer clearer or easier to scan: a table for comparisons or key/value data, a fenced code block with its language for commands, code, logs and file contents, short ## headings and lists for long or multi-step answers. Keep casual, short replies as plain conversational text without headings. Images, raw HTML, math and MEDIA: tags are not rendered; give file paths and URLs as plain text or links."

/** HTTP client for the Hermes Agent API server. */
class HermesClient(private val settings: SettingsStore) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Keepalive comments arrive every ~10 s, so 45 s of silence means a dead socket. */
    private val streamHttp: OkHttpClient = http.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---------- plumbing ----------

    private fun urlFor(base: String, segments: List<String>, query: Map<String, String> = emptyMap()): HttpUrl {
        if (base.isBlank()) throw HermesException.NotConfigured()
        val b = base.toHttpUrlOrNull()?.newBuilder() ?: throw HermesException.NotConfigured()
        for (s in segments) b.addPathSegment(s)
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build()
    }

    private fun builder(base: String, key: String, segments: List<String>, query: Map<String, String> = emptyMap(), auth: Boolean = true): Request.Builder {
        val rb = Request.Builder().url(urlFor(base, segments, query)).header("Accept", "application/json")
        if (auth && key.isNotBlank()) rb.header("Authorization", "Bearer $key")
        return rb
    }

    internal fun current(segments: List<String>, query: Map<String, String> = emptyMap()): Request.Builder =
        builder(settings.baseUrl.value, settings.apiKey.value, segments, query)

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
            }
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    /** Whether [e] may be retried: always for idempotent requests, else only when nothing was sent yet. */
    private fun isRetryable(request: Request, e: IOException): Boolean {
        if (request.method in IDEMPOTENT_METHODS || request.header("Idempotency-Key") != null) return true
        // Handshake failures (e.g. tailscale serve fetching its cert lazily) happen before the request is sent.
        return e is SSLHandshakeException ||
            e is ConnectException ||
            e is UnknownHostException ||
            e is NoRouteToHostException ||
            (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
    }

    /** Runs [block] up to 3 times (delays 800 ms, 2 s) while it fails with a retryable network error. */
    private suspend fun <T> withRetry(request: Request, block: suspend () -> T): T {
        var attempt = 0
        var handshakeRetried = false
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HermesException.Unreachable) {
                val cause = e.cause as? IOException ?: throw e
                if (attempt >= RETRY_DELAYS_MS.size || !isRetryable(request, cause)) throw e
                if (cause is SSLHandshakeException) {
                    if (handshakeRetried) throw e
                    handshakeRetried = true
                }
                delay(RETRY_DELAYS_MS[attempt++])
            }
        }
    }

    /** Runs the request and returns the body of a 2xx response, mapping failures to [HermesException]. */
    internal suspend fun fetch(request: Request): String = withRetry(request) {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw HermesException.Unreachable(e)
        }
        response.use { r ->
            val text = try {
                withContext(Dispatchers.IO) { r.body?.string().orEmpty() }
            } catch (e: IOException) {
                throw HermesException.Unreachable(e)
            }
            when {
                r.code == 401 || r.code == 403 -> throw HermesException.Unauthorized()
                !r.isSuccessful -> throw HermesException.Http(r.code, text.take(300))
            }
            text
        }
    }

    /** Only the HTTP status code; IO failures become [HermesException.Unreachable]. */
    private suspend fun statusOf(request: Request): Int = withRetry(request) {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw HermesException.Unreachable(e)
        }
        response.use { it.code }
    }

    // ---------- connection test ----------

    /** /health for reachability, /v1/capabilities (Bearer) for auth. Uses the given values, not the saved ones. */
    suspend fun testConnection(baseUrl: String, apiKey: String): ConnectionState {
        if (baseUrl.isBlank()) return ConnectionState.NotConfigured
        return try {
            val health = statusOf(builder(baseUrl, apiKey, listOf("health"), auth = false).get().build())
            if (health !in 200..299) {
                return ConnectionState.Unreachable("Hermes respondió con un error ($health)")
            }
            val caps = statusOf(builder(baseUrl, apiKey, listOf("v1", "capabilities")).get().build())
            when {
                caps == 401 || caps == 403 -> ConnectionState.Unauthorized
                caps in 200..299 -> ConnectionState.Connected
                caps == 404 || caps == 405 -> {
                    // Older servers without /v1/capabilities: verify the key against the sessions API.
                    val s = statusOf(builder(baseUrl, apiKey, listOf("api", "sessions"), mapOf("limit" to "1")).get().build())
                    when {
                        s == 401 || s == 403 -> ConnectionState.Unauthorized
                        s in 200..299 -> ConnectionState.Connected
                        else -> ConnectionState.Unreachable("Hermes respondió con un error ($s)")
                    }
                }
                else -> ConnectionState.Unreachable("Hermes respondió con un error ($caps)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesException) {
            e.toConnectionState() ?: ConnectionState.Unreachable(e.message ?: "No puedo conectar con Hermes")
        } catch (e: Exception) {
            e.toConnectionState() ?: ConnectionState.Unreachable("No puedo conectar con Hermes")
        }
    }

    // ---------- sessions ----------

    /** Lists all sessions, paging until the server runs out. */
    suspend fun listSessions(): List<RemoteSession> {
        val all = LinkedHashMap<String, RemoteSession>()
        val pageSize = 100
        var offset = 0
        repeat(10) {
            val body = fetch(
                current(
                    listOf("api", "sessions"),
                    mapOf("limit" to pageSize.toString(), "offset" to offset.toString()),
                ).get().build(),
            )
            val page = SessionParser.parseList(body)
            val before = all.size
            for (s in page) all.putIfAbsent(s.id, s)
            if (page.size < pageSize || all.size == before) return all.values.toList()
            offset += pageSize
        }
        return all.values.toList()
    }

    suspend fun createSession(title: String? = null): RemoteSession {
        val body = buildJsonObject { if (!title.isNullOrBlank()) put("title", title) }.toString()
        val text = fetch(current(listOf("api", "sessions")).post(body.toRequestBody(jsonMedia)).build())
        return SessionParser.parseCreated(text) ?: throw HermesException.Protocol("sesión sin id")
    }

    suspend fun renameSession(id: String, title: String) {
        val body = buildJsonObject { put("title", title) }.toString()
        fetch(current(listOf("api", "sessions", id)).patch(body.toRequestBody(jsonMedia)).build())
    }

    suspend fun deleteSession(id: String) {
        try {
            fetch(current(listOf("api", "sessions", id)).delete().build())
        } catch (e: HermesException.Http) {
            if (e.code != 404) throw e
        }
    }

    suspend fun messagesBody(id: String): String =
        fetch(current(listOf("api", "sessions", id, "messages"), mapOf("inline_images" to "false")).get().build())

    // ---------- approvals ----------

    /** Answers a pending `approval.request` (choice: once, session, always or deny). */
    suspend fun answerApproval(runId: String, requestId: String, choice: String) {
        val body = buildJsonObject {
            put("choice", choice)
            if (requestId.isNotBlank()) put("request_id", requestId)
        }.toString()
        fetch(current(listOf("v1", "runs", runId, "approval")).post(body.toRequestBody(jsonMedia)).build())
    }

    // ---------- runs ----------

    /**
     * Starts a run on the session and returns its run id. Re-posting with the same [idempotencyKey]
     * returns the same run, so callers may retry on network errors. 404/405 -> [HermesException.RunsUnsupported].
     */
    suspend fun startRun(threadId: String, text: String, idempotencyKey: String): String {
        val body = buildJsonObject {
            put("input", text)
            put("session_id", threadId)
            put("instructions", RENDER_HINT)
        }.toString()
        val request = current(listOf("v1", "runs"))
            .header("Idempotency-Key", idempotencyKey)
            .post(body.toRequestBody(jsonMedia))
            .build()
        val resp = try {
            fetch(request)
        } catch (e: HermesException.Http) {
            if (e.code == 404 || e.code == 405) throw HermesException.RunsUnsupported()
            throw e
        }
        return parseJsonObject(resp)?.str("run_id") ?: throw HermesException.Protocol("ejecución sin id")
    }

    /** SSE of a run; [lastSeq] >= 0 resumes after that sequence number. HTTP 404 = the run is retired. */
    fun runEvents(runId: String, lastSeq: Long): Flow<SseEvent> {
        val rb = current(listOf("v1", "runs", runId, "events")).header("Accept", "text/event-stream")
        if (lastSeq >= 0) rb.header("Last-Event-ID", lastSeq.toString())
        return sse(rb.get().build())
    }

    /** Raw JSON body of GET /v1/runs/{id}. */
    suspend fun runStatus(runId: String): String =
        fetch(current(listOf("v1", "runs", runId)).get().build())

    /** Best-effort stop; "not active" (409) and "unknown" (404) are fine. */
    suspend fun stopRun(runId: String) {
        try {
            fetch(current(listOf("v1", "runs", runId, "stop")).post("".toRequestBody(jsonMedia)).build())
        } catch (e: HermesException.Http) {
            if (e.code != 409 && e.code != 404) throw e
        }
    }

    // ---------- streaming ----------

    fun streamSessionChat(threadId: String, text: String): Flow<SseEvent> {
        val body = buildJsonObject {
            put("input", text)
            put("system_message", RENDER_HINT)
        }.toString().toRequestBody(jsonMedia)
        val request = current(listOf("api", "sessions", threadId, "chat", "stream"))
            .header("Accept", "text/event-stream")
            .post(body)
            .build()
        return sse(request)
    }

    fun streamChatCompletions(threadId: String, text: String): Flow<SseEvent> {
        val body = buildJsonObject {
            put("model", "hermes-agent")
            put("stream", true)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", RENDER_HINT)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", text)
                        },
                    )
                },
            )
        }.toString().toRequestBody(jsonMedia)
        val request = current(listOf("v1", "chat", "completions"))
            .header("Accept", "text/event-stream")
            .header("X-Hermes-Session-Id", threadId)
            .post(body)
            .build()
        return sse(request)
    }

    /** Cold flow; cancelling the collector cancels the HTTP call. */
    private fun sse(request: Request): Flow<SseEvent> = callbackFlow {
        val call = streamHttp.newCall(request)
        val reader = launch(Dispatchers.IO) {
            var failure: Throwable? = null
            try {
                call.execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) throw HermesException.Unauthorized()
                    if (!resp.isSuccessful) {
                        throw HermesException.Http(resp.code, resp.body?.string()?.take(300))
                    }
                    val source = resp.body?.source() ?: throw HermesException.Protocol("sin cuerpo")
                    SseParser.read(source) { ev -> send(ev) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failure = HermesException.Unreachable(e)
            } catch (e: Throwable) {
                failure = e
            }
            close(failure)
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }

    private companion object {
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "PATCH", "PUT", "DELETE")
        val RETRY_DELAYS_MS = longArrayOf(800, 2_000)
    }
}
