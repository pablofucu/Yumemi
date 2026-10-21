// Chat screen state (port of ChatViewModel.kt): merges the server transcript, the local cache and
// the live turn, and derives Yumemi's mood.
import { useEffect, useMemo } from 'react'
import { toConnectionState } from '../api/errors'
import { mergeTranscripts } from '../chat/transcript'
import type { ChatMessage, ConnectionState, YumemiMood } from '../domain'
import { repo, runner } from '../services'
import type { LiveTurn } from '../chat/turnRunner'
import { isConfigured } from '../settings/settingsStore'
import { Store, useStore } from './store'
import { withDisplayTitle } from './threadList'

export type ChatUi = {
  threadId: string
  title: string
  messages: ChatMessage[]
  mood: YumemiMood
  isSending: boolean
  connection: ConnectionState
  error: string | null
}

type Internal = {
  isSending: boolean
  connection: ConnectionState
  error: string | null
  transient: YumemiMood | null
}

function computeMood(i: Internal, live: ChatMessage | null): YumemiMood {
  if (i.error !== null) return 'error'
  if (i.transient) return i.transient
  if (i.isSending) {
    const parts = live?.parts ?? []
    // Waiting for the user's permission: she needs attention, not "working".
    if (parts.some((p) => p.type === 'approval' && p.status === 'pending')) return 'surprised'
    const working = parts.some((p) => (p.type === 'tool' || p.type === 'subagent') && p.status === 'running')
    if (working) return 'working'
    return parts[parts.length - 1]?.type === 'text' ? 'idle' : 'thinking'
  }
  if (i.connection.kind === 'not_configured' || i.connection.kind === 'unreachable') return 'sleeping'
  return 'idle'
}

export class ChatController {
  readonly state: Store<ChatUi>
  private internal: Internal
  /** Server transcript (null until loaded / while offline). */
  private server: ChatMessage[] | null = null
  /** The assistant message of the current (or just finished) turn, overriding the stored copy. */
  private inFlight: ChatMessage | null = null
  private transientTimer: ReturnType<typeof setTimeout> | undefined
  /** finishedAt of a finished turn whose error card the user dismissed (or that predates this screen). */
  private dismissedAt: number | null = null
  private unsubs: (() => void)[] = []
  private disposed = true
  private loadToken = 0
  private firstLive = true
  private wasRunning = false
  private lastTools = 0

  constructor(readonly threadId: string) {
    this.internal = {
      isSending: false,
      connection: isConfigured() ? { kind: 'unknown' } : { kind: 'not_configured' },
      error: null,
      transient: null,
    }
    this.state = new Store<ChatUi>(this.compute())
  }

  private compute(): ChatUi {
    const local = repo.messages(this.threadId).get()
    const live = this.inFlight
    const localWithLive = !live
      ? local
      : local.some((m) => m.id === live.id)
        ? local.map((m) => (m.id === live.id ? live : m))
        : [...local, live]
    const thread = repo.thread(this.threadId)
    return {
      threadId: this.threadId,
      title: thread ? withDisplayTitle(thread).title : '',
      messages: mergeTranscripts(this.server, localWithLive),
      mood: computeMood(this.internal, live),
      isSending: this.internal.isSending,
      connection: this.internal.connection,
      error: this.internal.error,
    }
  }

  private emit(): void {
    if (!this.disposed) this.state.set(this.compute())
  }

  private patch(p: Partial<Internal>): void {
    this.internal = { ...this.internal, ...p }
    this.emit()
  }

  start(): void {
    if (!this.disposed) return
    this.disposed = false
    this.firstLive = true
    this.wasRunning = false
    this.unsubs = [
      repo.messages(this.threadId).subscribe(() => this.emit()),
      repo.threads.subscribe(() => this.emit()),
      runner.observe(this.threadId).subscribe(() => this.onLive(runner.observe(this.threadId).get())),
    ]
    const onOnline = () => {
      // The VPN often comes up after the radio: reload as soon as a network (re)appears.
      if (this.internal.connection.kind !== 'connected') void this.loadTranscript()
    }
    window.addEventListener('online', onOnline)
    this.unsubs.push(() => window.removeEventListener('online', onOnline))
    this.onLive(runner.observe(this.threadId).get())
    void this.loadTranscript()
    // Re-attach to a run that was in flight when the page was closed.
    runner.resumePending(this.threadId)
    this.emit()
  }

  dispose(): void {
    this.disposed = true
    this.unsubs.forEach((u) => u())
    this.unsubs = []
    clearTimeout(this.transientTimer)
    this.loadToken++
  }

  // ---------- transcript ----------

  async loadTranscript(): Promise<void> {
    if (!isConfigured()) {
      this.patch({ connection: { kind: 'not_configured' } })
      return
    }
    const token = ++this.loadToken
    try {
      const transcript = await repo.fetchTranscript(this.threadId)
      if (token !== this.loadToken) return
      this.server = transcript
      // Mid-turn the server copy lags behind: the turn records its own messages.
      if (!runner.isRunning(this.threadId)) repo.recordTranscript(this.threadId, transcript)
      this.patch({ connection: { kind: 'connected' } })
    } catch (e) {
      if (token !== this.loadToken) return
      const c = toConnectionState(e)
      if (c) this.patch({ connection: c })
    }
  }

  // ---------- actions ----------

  send(text: string): void {
    const t = text.trim()
    if (t === '' || runner.isRunning(this.threadId)) return
    this.patch({ isSending: true, error: null, transient: null })
    runner.start(this.threadId, t)
  }

  stop(): void {
    runner.stop(this.threadId)
  }

  clearError(): void {
    this.dismissedAt = runner.observe(this.threadId).get()?.finishedAt ?? null
    this.patch({ error: null })
  }

  /** Sends the user's answer to a permission question Hermes is waiting on. */
  answerApproval(requestId: string, choice: string): void {
    runner.answerApproval(this.threadId, requestId, choice)
  }

  retryLast(): void {
    if (runner.isRunning(this.threadId)) return
    const msgs = this.state.get().messages
    let userIdx = -1
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === 'user') {
        userIdx = i
        break
      }
    }
    if (userIdx < 0) return
    const user = msgs[userIdx]
    const text = user.parts
      .filter((p): p is Extract<typeof p, { type: 'text' }> => p.type === 'text')
      .map((p) => p.text)
      .join('\n')
      .trim()
    if (text === '') return
    const next = msgs[userIdx + 1]
    const after = next && next.role === 'assistant' ? next : null
    const failed = !after || !!after.error || after.parts.length === 0
    this.patch({ isSending: true, error: null, transient: null })
    if (failed) {
      runner.clear(this.threadId)
      this.inFlight = null
      if (after) repo.deleteMessage(this.threadId, after.id)
      repo.deleteMessage(this.threadId, user.id)
      // The failed question may also be in the server transcript; the merge keeps one copy.
    }
    runner.start(this.threadId, text)
  }

  // ---------- live turn ----------

  /** Mirrors the app-level turn into UI state; the turn itself keeps running without this screen. */
  private onLive(live: LiveTurn | null): void {
    if (this.disposed) return
    if (this.firstLive) {
      this.firstLive = false
      // A turn that finished before this screen existed is already stored: don't replay it.
      if (live && !live.running) this.dismissedAt = live.finishedAt
      this.lastTools = live?.toolAfterTextCount ?? 0
    }
    if (!live) {
      this.inFlight = null
      this.wasRunning = false
      this.emit()
      return
    }
    const stale = !live.running && live.finishedAt !== null && live.finishedAt === this.dismissedAt && !this.wasRunning
    const shown = live.running || live.message.parts.length > 0 || !!live.message.error
    this.inFlight = shown && !stale ? live.message : null
    const error = !live.running && live.finishedAt !== this.dismissedAt ? live.error : null
    this.internal = { ...this.internal, isSending: live.running, error }

    if (live.toolAfterTextCount < this.lastTools) this.lastTools = live.toolAfterTextCount
    if (live.toolAfterTextCount > this.lastTools) {
      this.lastTools = live.toolAfterTextCount
      this.flash('surprised', 1_500)
    }
    if (live.running) {
      this.wasRunning = true
    } else if (this.wasRunning) {
      this.wasRunning = false
      if (live.succeeded) {
        this.internal = { ...this.internal, connection: { kind: 'connected' } }
        this.flash('happy', 6_000)
      }
      // Even after a failure the server may still hold the answer.
      void this.loadTranscript()
    }
    this.emit()
  }

  private flash(mood: YumemiMood, ms: number): void {
    clearTimeout(this.transientTimer)
    this.internal = { ...this.internal, transient: mood }
    this.transientTimer = setTimeout(() => this.patch({ transient: null }), ms)
  }
}

export function useChat(threadId: string): { ui: ChatUi; ctl: ChatController } {
  const ctl = useMemo(() => new ChatController(threadId), [threadId])
  useEffect(() => {
    ctl.start()
    return () => ctl.dispose()
  }, [ctl])
  const ui = useStore(ctl.state)
  return { ui, ctl }
}
