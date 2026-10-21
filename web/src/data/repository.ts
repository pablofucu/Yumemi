// Thread list + message cache + Hermes calls (port of HermesRepository.kt).
// Room is replaced by a small cache in web storage (see settingsStore.cacheKind).
import { HermesClient } from '../api/hermes'
import { HermesError } from '../api/errors'
import type { SseEvent } from '../api/sse'
import { snippetOf } from '../chat/activity'
import { mapChatCompletions, mapSession, type StreamEvent } from '../chat/streamEvent'
import { decodeParts, encodeParts, isCronRun, parseTranscript } from '../chat/transcript'
import type { ChatMessage, ChatThread, RemoteSession } from '../domain'
import { cacheKind, settings, storageGet, storageRemove, storageSet } from '../settings/settingsStore'
import { Store } from '../state/store'
import { parseJson, isObject, str } from '../util'

const K_THREADS = 'yumemi.threads'
const K_PINS = 'yumemi.pins'
const K_MSGS = 'yumemi.msgs.'
const MAX_LOCAL_MESSAGES = 80

type StoredMessage = { id: string; role: 'user' | 'assistant'; parts: string; createdAt: number; error?: string | null }

function readPins(): Set<string> {
  const v = parseJson(storageGet('local', K_PINS) ?? '')
  return new Set(Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [])
}

export class HermesRepository {
  /** Threads without activity; the UI adds it from the runner and the activity poll. */
  readonly threads: Store<ChatThread[]>
  private readonly messageStores = new Map<string, Store<ChatMessage[]>>()
  private pins: Set<string>

  constructor(readonly client: HermesClient) {
    this.pins = readPins()
    this.threads = new Store<ChatThread[]>(this.loadThreads())
  }

  // ---------- cache ----------

  private loadThreads(): ChatThread[] {
    const v = parseJson(storageGet(cacheKind(), K_THREADS) ?? '')
    if (!Array.isArray(v)) return []
    const out: ChatThread[] = []
    for (const o of v) {
      if (!isObject(o)) continue
      const id = str(o, 'id')
      if (!id) continue
      out.push({
        id,
        title: str(o, 'title') ?? '',
        updatedAt: typeof o.updatedAt === 'number' ? o.updatedAt : 0,
        preview: str(o, 'preview') ?? '',
        lastMessage: str(o, 'lastMessage') ?? '',
        lastFromUser: o.lastFromUser === true,
        lastAt: typeof o.lastAt === 'number' ? o.lastAt : 0,
        source: str(o, 'source'),
        pinned: this.pins.has(id),
        activity: null,
      })
    }
    return out
  }

  private persistThreads(): void {
    const rows = this.threads.get().map((t) => ({ ...t, activity: undefined, pinned: undefined }))
    storageSet(cacheKind(), K_THREADS, JSON.stringify(rows))
  }

  private setThreads(list: ChatThread[]): void {
    this.threads.set(list)
    this.persistThreads()
  }

  /** Reloads caches after the storage kind changed (remember on/off) or everything was forgotten. */
  reset(): void {
    this.messageStores.clear()
    this.pins = readPins()
    this.threads.set(this.loadThreads())
  }

  // ---------- threads ----------

  thread(id: string): ChatThread | undefined {
    return this.threads.get().find((t) => t.id === id)
  }

  /**
   * Syncs the thread list with the server. Each page is stored as it arrives ([onFirstPage] runs
   * after the first one); threads gone from the server are removed only once the listing is complete.
   */
  async refreshThreads(onFirstPage: () => void = () => {}): Promise<void> {
    let first = true
    // Cron runs are sessions too; they are not chats.
    const remote = (
      await this.client.listSessions((page) => {
        this.upsertThreads(page.filter((s) => !isCronRun(s)))
        if (first) {
          first = false
          onFirstPage()
        }
      })
    ).filter((s) => !isCronRun(s))
    const ids = new Set(remote.map((s) => s.id))
    const gone = this.threads.get().filter((t) => !ids.has(t.id))
    if (gone.length > 0) {
      this.setThreads(this.threads.get().filter((t) => ids.has(t.id)))
      for (const t of gone) this.dropMessages(t.id)
    }
  }

  private upsertThreads(remote: RemoteSession[]): void {
    if (remote.length === 0) return
    const now = Date.now()
    const byId = new Map(this.threads.get().map((t) => [t.id, t]))
    let changed = false
    for (const r of remote) {
      const old = byId.get(r.id)
      const row: ChatThread = {
        id: r.id,
        title: r.title.trim() !== '' ? r.title : (old?.title ?? ''),
        updatedAt: r.updatedAt > 0 ? r.updatedAt : (old?.updatedAt ?? now),
        preview: r.preview.trim() !== '' ? r.preview : (old?.preview ?? ''),
        lastMessage: old?.lastMessage ?? '',
        lastFromUser: old?.lastFromUser ?? false,
        lastAt: old?.lastAt ?? 0,
        source: r.source ?? old?.source ?? null,
        pinned: this.pins.has(r.id),
        activity: null,
      }
      // Unchanged rows are not rewritten: each write re-renders the whole list.
      if (!old || JSON.stringify(old) !== JSON.stringify(row)) {
        byId.set(r.id, row)
        changed = true
      }
    }
    if (!changed) return
    // Keep server order for new rows by sorting on recency; the UI sorts again by section.
    this.setThreads([...byId.values()].sort((a, b) => b.updatedAt - a.updatedAt))
  }

  async createThread(): Promise<string> {
    const s = await this.client.createSession()
    const row: ChatThread = {
      id: s.id,
      title: s.title,
      updatedAt: s.updatedAt > 0 ? s.updatedAt : Date.now(),
      preview: '',
      lastMessage: '',
      lastFromUser: false,
      lastAt: 0,
      source: null,
      pinned: false,
      activity: null,
    }
    this.setThreads([row, ...this.threads.get().filter((t) => t.id !== s.id)])
    return s.id
  }

  private patchThread(id: string, patch: Partial<ChatThread>): void {
    const list = this.threads.get()
    if (!list.some((t) => t.id === id)) return
    this.setThreads(list.map((t) => (t.id === id ? { ...t, ...patch } : t)))
  }

  /** Optimistic local rename, then the API. On failure the exception propagates (caller refreshes). */
  async renameThread(id: string, title: string): Promise<void> {
    this.patchThread(id, { title })
    await this.client.renameSession(id, title)
  }

  async deleteThread(id: string): Promise<void> {
    this.setThreads(this.threads.get().filter((t) => t.id !== id))
    this.dropMessages(id)
    if (this.pins.delete(id)) this.persistPins()
    await this.client.deleteSession(id)
  }

  /** Raw stored title ("" when none). */
  rawTitle(id: string): string {
    return this.thread(id)?.title ?? ''
  }

  /** Records [message] as the thread's latest message. */
  touchThread(id: string, message: ChatMessage): void {
    const snippet = snippetOf(message)
    if (snippet === '') return
    const old = this.thread(id)
    if (!old) return
    this.patchThread(id, {
      lastMessage: snippet,
      lastFromUser: message.role === 'user',
      lastAt: message.createdAt,
      updatedAt: Math.max(old.updatedAt, message.createdAt),
    })
  }

  private persistPins(): void {
    storageSet('local', K_PINS, JSON.stringify([...this.pins]))
  }

  setPinned(id: string, pinned: boolean): void {
    if (pinned) this.pins.add(id)
    else this.pins.delete(id)
    this.persistPins()
    this.patchThread(id, { pinned })
  }

  /** Stores the latest message of a server [transcript]; [serverUpdatedAt] is what it is valid for. */
  recordTranscript(id: string, transcript: ChatMessage[], serverUpdatedAt = 0): void {
    const old = this.thread(id)
    if (!old) return
    const at = Math.max(serverUpdatedAt, old.updatedAt)
    const last = [...transcript].reverse().find((m) => snippetOf(m) !== '')
    if (!last) {
      // Nothing to show yet; remember it was checked so it is not fetched again until it changes.
      this.patchThread(id, { lastAt: at })
    } else {
      this.patchThread(id, { lastMessage: snippetOf(last), lastFromUser: last.role === 'user', lastAt: at })
    }
  }

  /** The most recently active sessions (one small page), stored like a regular refresh. */
  async refreshRecent(): Promise<RemoteSession[]> {
    const page = (await this.client.listRecentSessions()).filter((s) => !isCronRun(s))
    this.upsertThreads(page)
    return page
  }

  /** Threads whose latest message is unknown or older than their last activity, newest first. */
  threadsWithStaleLast(limit: number): [string, number][] {
    return this.threads
      .get()
      .filter((t) => t.lastAt < t.updatedAt)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, limit)
      .map((t) => [t.id, t.updatedAt])
  }

  /** Fetches a session's transcript, stores its latest message and returns that message. */
  async inspectThread(id: string, serverUpdatedAt: number): Promise<ChatMessage | null> {
    const transcript = await this.fetchTranscript(id)
    this.recordTranscript(id, transcript, serverUpdatedAt)
    return transcript[transcript.length - 1] ?? null
  }

  /** Sets the title from the first ~40 chars of [firstMessage] when the thread has none. */
  async autoTitleIfBlank(id: string, firstMessage: string): Promise<void> {
    if (this.rawTitle(id).trim() !== '') return
    const flat = firstMessage.replace(/\s+/g, ' ').trim()
    if (flat === '') return
    const title = flat.length > 40 ? flat.slice(0, 40).trimEnd() + '…' : flat
    this.patchThread(id, { title })
    try {
      await this.client.renameSession(id, title)
    } catch {
      // Local title stays; the server will be updated by a later rename.
    }
  }

  // ---------- messages ----------

  messages(threadId: string): Store<ChatMessage[]> {
    let s = this.messageStores.get(threadId)
    if (!s) {
      s = new Store<ChatMessage[]>(this.loadMessages(threadId))
      this.messageStores.set(threadId, s)
    }
    return s
  }

  private loadMessages(threadId: string): ChatMessage[] {
    const v = parseJson(storageGet(cacheKind(), K_MSGS + threadId) ?? '')
    if (!Array.isArray(v)) return []
    const out: ChatMessage[] = []
    for (const o of v as StoredMessage[]) {
      if (!isObject(o) || typeof o.id !== 'string') continue
      out.push({
        id: o.id,
        role: o.role === 'user' ? 'user' : 'assistant',
        parts: decodeParts(typeof o.parts === 'string' ? o.parts : '[]'),
        createdAt: typeof o.createdAt === 'number' ? o.createdAt : 0,
        isStreaming: false,
        error: o.error ?? null,
      })
    }
    return out.sort((a, b) => a.createdAt - b.createdAt)
  }

  private persistMessages(threadId: string, list: ChatMessage[]): void {
    const rows: StoredMessage[] = list.map((m) => ({
      id: m.id,
      role: m.role,
      parts: encodeParts(m.parts),
      createdAt: m.createdAt,
      error: m.error ?? null,
    }))
    const ok = storageSet(cacheKind(), K_MSGS + threadId, JSON.stringify(rows))
    if (!ok) {
      // Quota exceeded: keep only the newest half rather than losing the whole cache.
      storageSet(cacheKind(), K_MSGS + threadId, JSON.stringify(rows.slice(-Math.ceil(rows.length / 2))))
    }
  }

  saveMessage(threadId: string, m: ChatMessage): void {
    const store = this.messages(threadId)
    const list = store.get()
    const stored: ChatMessage = { ...m, isStreaming: false }
    const idx = list.findIndex((x) => x.id === m.id)
    let next = idx >= 0 ? list.map((x, i) => (i === idx ? stored : x)) : [...list, stored]
    next = next.sort((a, b) => a.createdAt - b.createdAt).slice(-MAX_LOCAL_MESSAGES)
    store.set(next)
    this.persistMessages(threadId, next)
  }

  deleteMessage(threadId: string, id: string): void {
    const store = this.messages(threadId)
    const next = store.get().filter((m) => m.id !== id)
    store.set(next)
    this.persistMessages(threadId, next)
  }

  getMessage(threadId: string, id: string): ChatMessage | undefined {
    return this.messages(threadId).get().find((m) => m.id === id)
  }

  private dropMessages(threadId: string): void {
    this.messageStores.get(threadId)?.set([])
    this.messageStores.delete(threadId)
    storageRemove(cacheKind(), K_MSGS + threadId)
  }

  /** Server transcript. A 404 (session unknown to the server) yields an empty list. */
  async fetchTranscript(threadId: string): Promise<ChatMessage[]> {
    try {
      return parseTranscript(await this.client.messagesBody(threadId))
    } catch (e) {
      if (e instanceof HermesError && e.kind === 'http' && e.code === 404) return []
      throw e
    }
  }

  answerApproval(runId: string, requestId: string, choice: string): Promise<void> {
    return this.client.answerApproval(runId, requestId, choice)
  }

  // ---------- streaming ----------

  async *streamTurn(threadId: string, text: string, signal?: AbortSignal): AsyncGenerator<StreamEvent> {
    const chatCompletions = settings.get().useChatCompletions
    const raw: AsyncGenerator<SseEvent> = chatCompletions
      ? this.client.streamChatCompletions(threadId, text, signal)
      : this.client.streamSessionChat(threadId, text, signal)
    for await (const sse of raw) {
      const events = chatCompletions ? mapChatCompletions(sse) : mapSession(sse)
      for (const e of events) yield e
    }
  }
}
