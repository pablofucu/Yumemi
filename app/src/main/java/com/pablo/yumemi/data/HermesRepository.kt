package com.pablo.yumemi.data

import androidx.room.withTransaction
import com.pablo.yumemi.data.chat.EventMapper
import com.pablo.yumemi.data.chat.PartCodec
import com.pablo.yumemi.data.chat.RemoteSession
import com.pablo.yumemi.data.chat.StreamEvent
import com.pablo.yumemi.data.chat.ThreadActivityRules
import com.pablo.yumemi.data.chat.TranscriptParser
import com.pablo.yumemi.data.chat.isCronRun
import com.pablo.yumemi.data.local.MessageEntity
import com.pablo.yumemi.data.local.ThreadEntity
import com.pablo.yumemi.data.local.YumemiDatabase
import com.pablo.yumemi.data.remote.HermesClient
import com.pablo.yumemi.data.remote.HermesException
import com.pablo.yumemi.data.settings.SettingsStore
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.ChatThread
import com.pablo.yumemi.domain.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

class HermesRepository(
    private val client: HermesClient,
    private val db: YumemiDatabase,
    private val settings: SettingsStore,
) {
    private val threadDao = db.threadDao()
    private val messageDao = db.messageDao()

    // ---------- threads ----------

    fun observeThreads(): Flow<List<ChatThread>> =
        threadDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeThread(id: String): Flow<ChatThread?> =
        threadDao.observe(id).map { it?.toDomain() }

    /**
     * Syncs the thread list with the server. Each page is stored as it arrives ([onFirstPage] runs
     * after the first one); threads gone from the server are removed only once the listing is complete.
     */
    suspend fun refreshThreads(onFirstPage: () -> Unit = {}) {
        var first = true
        // Cron runs are sessions too; they live in the Resultados tab, not among the chats.
        val remote = client.listSessions { page ->
            upsertThreads(page.filterNot { it.isCronRun() })
            if (first) {
                first = false
                onFirstPage()
            }
        }.filterNot { it.isCronRun() }
        db.withTransaction {
            threadDao.deleteNotIn(remote.map { it.id })
            messageDao.deleteOrphans()
        }
    }

    private suspend fun upsertThreads(remote: List<RemoteSession>) {
        if (remote.isEmpty()) return
        val now = System.currentTimeMillis()
        db.withTransaction {
            val rows = remote.mapNotNull { r ->
                val old = threadDao.get(r.id)
                val row = ThreadEntity(
                    id = r.id,
                    title = r.title.ifBlank { old?.title.orEmpty() },
                    updatedAt = if (r.updatedAt > 0) r.updatedAt else (old?.updatedAt ?: now),
                    preview = r.preview.ifBlank { old?.preview.orEmpty() },
                    lastMessage = old?.lastMessage.orEmpty(),
                    lastFromUser = old?.lastFromUser ?: false,
                    lastAt = old?.lastAt ?: 0,
                    source = r.source ?: old?.source,
                    pinned = old?.pinned ?: false,
                )
                // Unchanged rows are not rewritten: each write re-emits (and recomposes) the whole list.
                if (row == old) null else row
            }
            if (rows.isNotEmpty()) threadDao.upsertAll(rows)
        }
    }

    suspend fun createThread(): String {
        val s = client.createSession()
        threadDao.upsert(
            ThreadEntity(
                id = s.id,
                title = s.title,
                updatedAt = if (s.updatedAt > 0) s.updatedAt else System.currentTimeMillis(),
                preview = "",
            ),
        )
        return s.id
    }

    /** Optimistic local rename, then the API. On failure the exception propagates (caller refreshes). */
    suspend fun renameThread(id: String, title: String) {
        threadDao.setTitle(id, title)
        client.renameSession(id, title)
    }

    suspend fun deleteThread(id: String) {
        db.withTransaction {
            threadDao.delete(id)
            messageDao.deleteForThread(id)
        }
        client.deleteSession(id)
    }

    /** Raw stored title ("" when none). */
    suspend fun rawTitle(id: String): String? = threadDao.get(id)?.title

    /** Records [message] as the thread's latest message. */
    suspend fun touchThread(id: String, message: ChatMessage) {
        val snippet = ThreadActivityRules.snippetOf(message)
        if (snippet.isEmpty()) return
        threadDao.touch(id, message.createdAt, snippet, message.role == Role.USER)
    }

    suspend fun setPinned(id: String, pinned: Boolean) = threadDao.setPinned(id, pinned)

    /** Stores the latest message of a server [transcript]; [serverUpdatedAt] is what it is valid for. */
    suspend fun recordTranscript(id: String, transcript: List<ChatMessage>, serverUpdatedAt: Long = 0) {
        val old = threadDao.get(id) ?: return
        val at = maxOf(serverUpdatedAt, old.updatedAt)
        val last = transcript.lastOrNull { ThreadActivityRules.snippetOf(it).isNotEmpty() }
        if (last == null) {
            // Nothing to show yet; remember it was checked so it is not fetched again until it changes.
            threadDao.setLast(id, old.lastMessage, old.lastFromUser, at)
        } else {
            threadDao.setLast(id, ThreadActivityRules.snippetOf(last), last.role == Role.USER, at)
        }
    }

    /** The most recently active sessions (one small page), stored like a regular refresh. */
    suspend fun refreshRecent(): List<RemoteSession> {
        val page = client.listRecentSessions().filterNot { it.isCronRun() }
        upsertThreads(page)
        return page
    }

    /** Threads whose latest message is unknown or older than their last activity, newest first. */
    suspend fun threadsWithStaleLast(limit: Int): List<Pair<String, Long>> =
        threadDao.getAll()
            .filter { it.lastAt < it.updatedAt }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .map { it.id to it.updatedAt }

    /** Fetches a session's transcript, stores its latest message and returns that message. */
    suspend fun inspectThread(id: String, serverUpdatedAt: Long): ChatMessage? {
        val transcript = fetchTranscript(id)
        recordTranscript(id, transcript, serverUpdatedAt)
        return transcript.lastOrNull()
    }

    /** Sets the title from the first ~40 chars of [firstMessage] when the thread has none. */
    suspend fun autoTitleIfBlank(id: String, firstMessage: String) {
        val current = threadDao.get(id)?.title.orEmpty()
        if (current.isNotBlank()) return
        val flat = firstMessage.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return
        val title = if (flat.length > 40) flat.take(40).trimEnd() + "…" else flat
        threadDao.setTitle(id, title)
        try {
            client.renameSession(id, title)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Local title stays; the server will be updated by a later rename.
        }
    }

    // ---------- messages ----------

    fun observeLocalMessages(threadId: String): Flow<List<ChatMessage>> =
        messageDao.observe(threadId).map { rows -> rows.map { it.toDomain() } }

    suspend fun saveMessage(threadId: String, m: ChatMessage) {
        messageDao.upsert(
            MessageEntity(
                id = m.id,
                threadId = threadId,
                role = m.role.name,
                partsJson = PartCodec.encode(m.parts),
                createdAt = m.createdAt,
                error = m.error,
            ),
        )
    }

    suspend fun deleteMessage(id: String) = messageDao.delete(id)

    suspend fun getMessage(id: String): ChatMessage? = messageDao.get(id)?.toDomain()

    /** Server transcript. A 404 (session unknown to the server) yields an empty list. */
    suspend fun fetchTranscript(threadId: String): List<ChatMessage> =
        try {
            TranscriptParser.parse(client.messagesBody(threadId))
        } catch (e: HermesException.Http) {
            if (e.code == 404) emptyList() else throw e
        }

    suspend fun answerApproval(runId: String, requestId: String, choice: String) =
        client.answerApproval(runId, requestId, choice)

    // ---------- streaming ----------

    fun streamTurn(threadId: String, text: String): Flow<StreamEvent> {
        val chatCompletions = settings.useChatCompletions.value
        val raw = if (chatCompletions) {
            client.streamChatCompletions(threadId, text)
        } else {
            client.streamSessionChat(threadId, text)
        }
        return raw.transform { sse ->
            val events = if (chatCompletions) EventMapper.mapChatCompletions(sse) else EventMapper.mapSession(sse)
            for (e in events) emit(e)
        }
    }

    private fun ThreadEntity.toDomain() = ChatThread(
        id = id,
        title = title,
        updatedAt = updatedAt,
        preview = preview,
        lastMessage = lastMessage,
        lastFromUser = lastFromUser,
        source = source,
        pinned = pinned,
    )

    private fun MessageEntity.toDomain() = ChatMessage(
        id = id,
        role = if (role == Role.USER.name) Role.USER else Role.ASSISTANT,
        parts = PartCodec.decode(partsJson),
        createdAt = createdAt,
        isStreaming = false,
        error = error,
    )
}
