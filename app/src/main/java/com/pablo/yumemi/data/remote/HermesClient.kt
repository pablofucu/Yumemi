package com.pablo.yumemi.data.remote

import com.pablo.yumemi.data.chat.RemoteSession
import com.pablo.yumemi.data.chat.SessionParser
import com.pablo.yumemi.data.settings.SettingsStore
import com.pablo.yumemi.domain.ConnectionState
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
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

/** HTTP client for the Hermes Agent API server. */
class HermesClient(private val settings: SettingsStore) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Streams never time out while reading (keepalive comments arrive every ~10 s). */
    private val streamHttp: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
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

    private fun current(segments: List<String>, query: Map<String, String> = emptyMap()): Request.Builder =
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

    /** Runs the request and returns the body of a 2xx response, mapping failures to [HermesException]. */
    private suspend fun fetch(request: Request): String {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw HermesException.Unreachable(e)
        }
        return response.use { r ->
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
    private suspend fun statusOf(request: Request): Int {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw HermesException.Unreachable(e)
        }
        return response.use { it.code }
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
            ConnectionState.Unreachable("No puedo conectar con Hermes")
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

    // ---------- streaming ----------

    fun streamSessionChat(threadId: String, text: String): Flow<SseEvent> {
        val body = buildJsonObject { put("input", text) }.toString().toRequestBody(jsonMedia)
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
}
