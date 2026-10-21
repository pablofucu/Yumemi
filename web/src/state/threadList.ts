// Thread list state: refresh, activity polling, create / rename / delete
// (port of ThreadListViewModel.kt).
import { toConnectionState, toUserMessage, HermesError } from '../api/errors'
import { fromLiveTurn, fromTranscript, TOOL_WAIT_MS } from '../chat/activity'
import type { ChatMessage, ChatThread, ConnectionState, ThreadActivity, YumemiMood } from '../domain'
import { repo, runner } from '../services'
import { isConfigured, settings } from '../settings/settingsStore'
import { Store, useStore } from './store'
import { useMemo } from 'react'

/** Placeholder shown for threads without a title. */
export const UNTITLED = 'Conversación nueva'

/** How often the list checks the server for turns other clients are running. */
const POLL_MS = 10_000
/** Threads whose latest message is fetched after each refresh. */
const BACKFILL_LIMIT = 6
/** Between full listings (all pages, cron runs included) a refresh only reads the first page. */
const FULL_SYNC_MS = 5 * 60_000

export function withDisplayTitle(t: ChatThread): ChatThread {
  if (t.title.trim() !== '') return t
  const fromPreview = t.preview.trim().slice(0, 40)
  return { ...t, title: fromPreview === '' ? UNTITLED : fromPreview }
}

type Internal = {
  connection: ConnectionState
  isRefreshing: boolean
  error: string | null
  happy: boolean
}

class ThreadListController {
  readonly internal = new Store<Internal>({
    connection: isConfigured() ? { kind: 'unknown' } : { kind: 'not_configured' },
    isRefreshing: false,
    error: null,
    happy: false,
  })
  /** Turns other clients seem to be running, from the last poll. */
  readonly external = new Store<Record<string, ThreadActivity>>({})
  /** Latest server message seen per thread, with the updatedAt it was read at. */
  private inspected = new Map<string, { at: number; msg: ChatMessage | null }>()
  private refreshToken = 0
  private refreshing = false
  private lastFullSync = 0
  private backfilling = false
  private happyTimer: ReturnType<typeof setTimeout> | undefined
  private lastCfg = ''

  private patch(p: Partial<Internal>) {
    this.internal.set((i) => ({ ...i, ...p }))
  }

  /** Call when URL / key change in Connection (and at start). */
  configChanged(): void {
    const s = settings.get()
    const sig = `${s.baseUrl}\n${s.apiKey}`
    if (sig === this.lastCfg) return
    this.lastCfg = sig
    this.inspected.clear()
    this.external.set({})
    this.lastFullSync = 0
    void this.startRefresh({ forceFull: true, retryOnUnreachable: true })
  }

  refresh(): void {
    void this.startRefresh({ forceFull: true })
  }

  private async startRefresh(o: { keepError?: boolean; retryOnUnreachable?: boolean; forceFull?: boolean }): Promise<void> {
    if (!isConfigured()) {
      this.patch({ connection: { kind: 'not_configured' }, isRefreshing: false })
      return
    }
    const token = ++this.refreshToken
    this.refreshing = true
    this.patch({ isRefreshing: true, error: o.keepError ? this.internal.get().error : null })
    const failure = await this.refreshOnce(!!o.forceFull, token)
    if (token === this.refreshToken && failure instanceof HermesError && failure.kind === 'unreachable' && o.retryOnUnreachable) {
      // Startup over a VPN that is still waking up: one more try shortly after.
      await new Promise((r) => setTimeout(r, 3_000))
      if (token === this.refreshToken) {
        this.patch({ isRefreshing: true })
        await this.refreshOnce(!!o.forceFull, token)
      }
    }
    if (token === this.refreshToken) this.refreshing = false
  }

  /** One refresh attempt; updates the state and returns the failure, if any. */
  private async refreshOnce(forceFull: boolean, token: number): Promise<unknown> {
    try {
      const full = forceFull || Date.now() - this.lastFullSync > FULL_SYNC_MS
      if (full) {
        // Connected (and no spinner) as soon as the first page is in; the rest keeps syncing quietly.
        await repo.refreshThreads(() => {
          if (token !== this.refreshToken) return
          this.patch({ connection: { kind: 'connected' }, isRefreshing: false, error: null })
          this.flashHappy()
        })
        this.lastFullSync = Date.now()
      } else {
        await repo.refreshRecent()
      }
      if (token === this.refreshToken) this.patch({ connection: { kind: 'connected' }, isRefreshing: false })
      void this.backfillLastMessages()
      return null
    } catch (e) {
      if (token === this.refreshToken) {
        this.patch({
          connection: toConnectionState(e) ?? this.internal.get().connection,
          isRefreshing: false,
          error: toUserMessage(e),
        })
      }
      return e
    }
  }

  async createThread(): Promise<string | null> {
    try {
      const id = await repo.createThread()
      this.patch({ connection: { kind: 'connected' }, error: null })
      return id
    } catch (e) {
      this.patch({ connection: toConnectionState(e) ?? this.internal.get().connection, error: toUserMessage(e) })
      return null
    }
  }

  async renameThread(id: string, title: string): Promise<void> {
    const t = title.trim()
    if (t === '') return
    try {
      await repo.renameThread(id, t)
    } catch (e) {
      this.patch({ error: toUserMessage(e) })
      void this.startRefresh({ keepError: true, forceFull: true })
    }
  }

  async deleteThread(id: string): Promise<void> {
    try {
      await repo.deleteThread(id)
    } catch (e) {
      this.patch({ error: toUserMessage(e) })
      void this.startRefresh({ keepError: true, forceFull: true })
    }
  }

  dismissError(): void {
    this.patch({ error: null })
  }

  /**
   * Keeps the activity of recently moved threads up to date while the list is on screen.
   * Resolves when [signal] aborts.
   */
  async watchActivity(signal: AbortSignal): Promise<void> {
    while (!signal.aborted) {
      if (isConfigured() && this.internal.get().connection.kind === 'connected' && !this.refreshing) {
        await this.pollActivity(signal)
      }
      await new Promise<void>((resolve) => {
        const t = setTimeout(resolve, POLL_MS)
        signal.addEventListener('abort', () => { clearTimeout(t); resolve() }, { once: true })
      })
    }
  }

  private async pollActivity(signal: AbortSignal): Promise<void> {
    let page
    try {
      page = await repo.refreshRecent()
    } catch {
      return // the next full refresh reports problems
    }
    const now = Date.now()
    const localRunning = new Set(Object.keys(runner.running.get()))
    const next: Record<string, ThreadActivity> = {}
    for (const s of page) {
      if (signal.aborted) return
      if (localRunning.has(s.id) || s.endedAt > 0) continue
      if (now - s.updatedAt >= TOOL_WAIT_MS) continue
      const cached = this.inspected.get(s.id)
      let last: ChatMessage | null
      if (cached && cached.at === s.updatedAt) {
        last = cached.msg
      } else {
        try {
          last = await repo.inspectThread(s.id, s.updatedAt)
          this.inspected.set(s.id, { at: s.updatedAt, msg: last })
        } catch {
          continue
        }
      }
      const a = fromTranscript(last ? [last] : [], s.updatedAt, now)
      if (a) next[s.id] = a
    }
    this.external.set(next)
  }

  /** Fetches the latest message of recent threads whose stored one is missing or stale. */
  private async backfillLastMessages(): Promise<void> {
    if (this.backfilling) return
    this.backfilling = true
    try {
      for (const [id, updatedAt] of repo.threadsWithStaleLast(BACKFILL_LIMIT)) {
        if (runner.isRunning(id)) continue
        try {
          const last = await repo.inspectThread(id, updatedAt)
          this.inspected.set(id, { at: updatedAt, msg: last })
        } catch {
          // Best effort: tried again on the next refresh.
        }
      }
    } finally {
      this.backfilling = false
    }
  }

  private flashHappy(): void {
    clearTimeout(this.happyTimer)
    this.patch({ happy: true })
    this.happyTimer = setTimeout(() => this.patch({ happy: false }), 2_500)
  }
}

export const threadList = new ThreadListController()

export type ThreadListUi = {
  threads: ChatThread[]
  connection: ConnectionState
  isRefreshing: boolean
  mood: YumemiMood
  error: string | null
}

export function useThreadList(): ThreadListUi {
  const threads = useStore(repo.threads)
  const internal = useStore(threadList.internal)
  const live = useStore(runner.running)
  const external = useStore(threadList.external)
  return useMemo(() => {
    const sleeping = internal.connection.kind === 'not_configured' || internal.connection.kind === 'unreachable'
    return {
      threads: threads.map((t) => {
        const l = live[t.id]
        const activity = l ? fromLiveTurn(l.message) : (external[t.id] ?? null)
        return { ...withDisplayTitle(t), activity }
      }),
      connection: internal.connection,
      isRefreshing: internal.isRefreshing,
      mood: sleeping ? 'sleeping' : internal.happy ? 'happy' : 'idle',
      error: internal.error,
    } satisfies ThreadListUi
  }, [threads, internal, live, external])
}

