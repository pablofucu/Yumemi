// Owns assistant turns at app scope, so leaving the chat view never cancels them
// (port of TurnRunner.kt). Preferred transport is the Runs API (a run survives client
// disconnects and can be replayed); older servers fall back to the session stream, which
// Hermes aborts whenever the socket drops.
import { HermesError, isAbort } from '../api/errors'
import type { HermesClient } from '../api/hermes'
import type { HermesRepository } from '../data/repository'
import type { ChatMessage, MessagePart } from '../domain'
import { settings, storageGet, storageRemove, storageSet, cacheKind } from '../settings/settingsStore'
import { Store } from '../state/store'
import { parseJsonObject, num, str, uuid } from '../util'
import { snippetOf } from './activity'
import { isTerminal, mapSession, type StreamEvent } from './streamEvent'
import { TurnAccumulator } from './turnAccumulator'
import { toUserMessage } from '../api/errors'

/** What the UI shows for a thread: the assistant message of the current (or just finished) turn. */
export type LiveTurn = {
  threadId: string
  message: ChatMessage
  running: boolean
  error: string | null
  succeeded: boolean
  finishedAt: number | null
  /** Increases each time a tool started after answer text had streamed. */
  toolAfterTextCount: number
}

class Active {
  runId: string | null = null
  toolAfterText = 0
  lastPersist = 0
  lastSeq = -1
  readonly abort = new AbortController()
  constructor(
    readonly threadId: string,
    readonly acc: TurnAccumulator,
    public assistant: ChatMessage,
  ) {}
}

type Pending = { runId: string; assistantId: string; createdAt: number; lastSeq: number }

const PENDING_PREFIX = 'yumemi.pending.'
const START_ATTEMPTS = 4
const GIVE_UP_MS = 15 * 60 * 1000

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new DOMException('Aborted', 'AbortError'))
    const t = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      clearTimeout(t)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

const isUnreachable = (e: unknown): boolean => e instanceof HermesError && e.kind === 'unreachable'
const httpCode = (e: unknown): number | null => (e instanceof HermesError && e.kind === 'http' ? (e.code ?? null) : null)

export class TurnRunner {
  private readonly states = new Map<string, Store<LiveTurn | null>>()
  private readonly active = new Map<string, Active>()
  /** Turns currently running in this app, by thread. */
  readonly running = new Store<Record<string, LiveTurn>>({})
  /** Set once a server answers 404/405 to POST /v1/runs; later turns skip straight to the session stream. */
  private runsUnsupported = false

  constructor(
    private readonly repo: HermesRepository,
    private readonly client: HermesClient,
  ) {}

  private stateFor(threadId: string): Store<LiveTurn | null> {
    let s = this.states.get(threadId)
    if (!s) {
      s = new Store<LiveTurn | null>(null)
      this.states.set(threadId, s)
    }
    return s
  }

  observe(threadId: string): Store<LiveTurn | null> {
    return this.stateFor(threadId)
  }

  private setState(threadId: string, turn: LiveTurn | null): void {
    this.stateFor(threadId).set(turn)
    this.running.set((m) => {
      const next = { ...m }
      if (turn?.running) next[threadId] = turn
      else delete next[threadId]
      return next
    })
  }

  isRunning(threadId: string): boolean {
    return this.stateFor(threadId).get()?.running === true
  }

  /** Forgets a finished turn (e.g. before retrying, so the old message is not shown again). */
  clear(threadId: string): void {
    if (!this.isRunning(threadId)) this.setState(threadId, null)
  }

  // ---------- public actions ----------

  start(threadId: string, text: string): void {
    if (this.isRunning(threadId)) return
    const now = Date.now()
    const assistant: ChatMessage = {
      id: uuid(),
      role: 'assistant',
      parts: [],
      createdAt: now + 1,
      isStreaming: true,
    }
    const a = new Active(threadId, new TurnAccumulator(settings.get().useChatCompletions), assistant)
    this.launch(a, () => this.execute(a, text, null))
  }

  /** Re-attaches to a run that was in flight when the page was closed or reloaded. */
  resumePending(threadId: string): void {
    if (this.isRunning(threadId)) return
    const p = this.readPending(threadId)
    if (!p) return
    const assistant: ChatMessage = {
      id: p.assistantId,
      role: 'assistant',
      parts: [],
      createdAt: p.createdAt,
      isStreaming: true,
    }
    const a = new Active(threadId, new TurnAccumulator(), assistant)
    a.runId = p.runId
    this.launch(a, () => this.execute(a, null, p))
  }

  stop(threadId: string): void {
    const a = this.active.get(threadId)
    if (!a) return
    const runId = a.runId
    if (runId) {
      // Best effort: the turn is stopped locally either way.
      this.client.stopRun(runId).catch(() => {})
    }
    a.abort.abort()
  }

  /** Sends the user's answer to a permission question Hermes is waiting on. */
  answerApproval(threadId: string, requestId: string, choice: string): void {
    const a = this.active.get(threadId)
    if (!a) return
    const acc = a.acc
    const part = acc.parts.find((p): p is Extract<MessagePart, { type: 'approval' }> => p.type === 'approval' && p.requestId === requestId)
    if (!part || part.status !== 'pending') return
    acc.setApproval(requestId, 'sending', choice)
    this.publishParts(a)
    void (async () => {
      try {
        await this.repo.answerApproval(part.runId, requestId, choice)
        acc.setApproval(requestId, 'answered', choice)
      } catch (e) {
        // Shown on the card itself: the chat's error card would offer to resend the message.
        const gone = httpCode(e) === 409 || acc.finished
        if (gone) acc.setApproval(requestId, 'expired', null)
        else acc.setApproval(requestId, 'pending', choice) // pending with an answer = last attempt did not get through
      }
      this.publishParts(a)
      if (this.active.get(threadId) !== a) {
        // The turn already ended and was stored: store the card's final state too.
        const m: ChatMessage = { ...a.assistant, parts: acc.parts, isStreaming: false }
        a.assistant = m
        try {
          this.repo.saveMessage(threadId, m)
        } catch {
          /* best effort */
        }
      }
    })()
  }

  // ---------- turn lifecycle ----------

  private launch(a: Active, body: () => Promise<void>): void {
    this.active.set(a.threadId, a)
    this.publish(a)
    void body()
  }

  private publish(
    a: Active,
    o: { running?: boolean; error?: string | null; succeeded?: boolean; finishedAt?: number | null; message?: ChatMessage } = {},
  ): void {
    this.setState(a.threadId, {
      threadId: a.threadId,
      message: o.message ?? a.assistant,
      running: o.running ?? true,
      error: o.error ?? null,
      succeeded: o.succeeded ?? false,
      finishedAt: o.finishedAt ?? null,
      toolAfterTextCount: a.toolAfterText,
    })
  }

  /** Refreshes the parts of the shown message without changing anything else (approval cards). */
  private publishParts(a: Active): void {
    a.assistant = { ...a.assistant, parts: a.acc.parts }
    const store = this.stateFor(a.threadId)
    const cur = store.get()
    if (cur && cur.message.id === a.assistant.id) {
      this.setState(a.threadId, { ...cur, message: { ...cur.message, parts: a.acc.parts } })
    }
  }

  private async execute(a: Active, text: string | null, resume: Pending | null): Promise<void> {
    const { threadId, acc } = a
    let succeeded = false
    let stored: ChatMessage | undefined
    try {
      if (text !== null) {
        const userAt = a.assistant.createdAt - 1
        const userMsg: ChatMessage = { id: uuid(), role: 'user', parts: [{ type: 'text', text }], createdAt: userAt }
        this.repo.saveMessage(threadId, userMsg)
        this.repo.touchThread(threadId, userMsg)
        // Separate task: the title is still set if the user stops the turn.
        void this.repo.autoTitleIfBlank(threadId, text)
      }
      if (resume) {
        stored = this.repo.getMessage(threadId, resume.assistantId)
        if (stored) {
          a.assistant = { ...a.assistant, parts: stored.parts }
          this.publish(a)
        }
      }
      if (resume) await this.followRun(a, resume.runId)
      else if (text !== null) {
        if (settings.get().useChatCompletions || this.runsUnsupported) await this.streamSession(a, text)
        else await this.runViaRuns(a, text)
      }
      if (!acc.finished) {
        // Stream closed without a terminal event: treat as completed if anything arrived.
        if (acc.parts.length === 0) acc.fail('Hermes no devolvió ninguna respuesta')
        else acc.apply({ type: 'completed' })
      }
      succeeded = acc.error === null && !acc.stopped
    } catch (e) {
      if (a.abort.signal.aborted || isAbort(e)) acc.stop()
      else acc.fail(toUserMessage(e))
    } finally {
      // A resumed run Hermes no longer knows: keep the copy stored before the page closed.
      const keepStored = !!resume && !!stored && stored.parts.length > 0 && acc.parts.length === 0 && !succeeded
      const finalMsg: ChatMessage =
        keepStored && stored
          ? { ...a.assistant, parts: stored.parts, error: stored.error ?? null, isStreaming: false }
          : { ...a.assistant, parts: acc.parts, error: acc.error, isStreaming: false }
      a.assistant = finalMsg
      if (!keepStored && (finalMsg.parts.length > 0 || finalMsg.error)) {
        try {
          this.repo.saveMessage(threadId, finalMsg)
          this.repo.touchThread(threadId, { ...finalMsg, createdAt: Date.now() })
        } catch {
          /* persisting is best effort */
        }
      }
      this.clearPending(threadId)
      if (this.active.get(threadId) === a) this.active.delete(threadId)
      this.publish(a, {
        running: false,
        error: keepStored ? null : acc.error,
        succeeded,
        finishedAt: Date.now(),
        message: finalMsg,
      })
      if (succeeded && typeof document !== 'undefined' && document.hidden) {
        const t = snippetOf(finalMsg)
        if (t !== '') document.title = '● Yumemi'
      }
    }
  }

  /** Applies [ev], publishes the new message and persists it about once a second. */
  private applyEvent(a: Active, ev: StreamEvent): void {
    if (a.acc.apply(ev)) a.toolAfterText++
    a.assistant = { ...a.assistant, parts: a.acc.parts, error: a.acc.error, isStreaming: !a.acc.finished }
    this.publish(a)
    const t = Date.now()
    if (t - a.lastPersist > 1_000) {
      a.lastPersist = t
      this.repo.saveMessage(a.threadId, { ...a.assistant, isStreaming: false })
      if (a.runId) {
        this.savePending(a.threadId, { runId: a.runId, assistantId: a.assistant.id, createdAt: a.assistant.createdAt, lastSeq: a.lastSeq })
      }
    }
  }

  // ---------- session stream / chat completions ----------

  private async streamSession(a: Active, text: string): Promise<void> {
    try {
      for await (const ev of this.repo.streamTurn(a.threadId, text, a.abort.signal)) {
        this.applyEvent(a, ev)
        if (isTerminal(ev)) break
      }
    } catch (e) {
      if (a.abort.signal.aborted) throw e
      if (isUnreachable(e) && a.acc.parts.length > 0) {
        // Hermes aborts a turn on this transport as soon as the socket drops.
        this.applyEvent(a, { type: 'failed', message: 'Se cortó la conexión y Hermes interrumpió la respuesta' })
      } else {
        throw e
      }
    }
  }

  // ---------- Runs API ----------

  private async runViaRuns(a: Active, text: string): Promise<void> {
    const key = uuid()
    let runId: string | null = null
    let failures = 0
    while (runId === null) {
      try {
        runId = await this.client.startRun(a.threadId, text, key, a.abort.signal)
      } catch (e) {
        if (a.abort.signal.aborted) throw e
        if (e instanceof HermesError && e.kind === 'runs_unsupported') {
          this.runsUnsupported = true
          await this.streamSession(a, text)
          return
        }
        if (!isUnreachable(e)) throw e
        failures++
        if (failures >= START_ATTEMPTS) throw e
        // Same idempotency key every time: a POST that did arrive is not run twice.
        await sleep(1_000 * 2 ** (failures - 1), a.abort.signal)
      }
    }
    a.runId = runId
    this.savePending(a.threadId, { runId, assistantId: a.assistant.id, createdAt: a.assistant.createdAt, lastSeq: -1 })
    await this.followRun(a, runId)
  }

  /** Follows the run's event stream, reconnecting (with replay) until a terminal event arrives. */
  private async followRun(a: Active, runId: string): Promise<void> {
    const acc = a.acc
    let backoff = 1_000
    let lastContact = Date.now()
    while (!acc.finished) {
      let gotEvent = false
      let terminal = false
      let cleanEnd = false
      try {
        for await (const sse of this.client.runEvents(runId, a.lastSeq, a.abort.signal)) {
          gotEvent = true
          lastContact = Date.now()
          const idNum = sse.id !== null && sse.id.trim() !== '' ? Number(sse.id.trim()) : NaN
          const seq = Number.isFinite(idNum) ? idNum : num(parseJsonObject(sse.data), 'seq')
          if (seq !== null && seq > a.lastSeq) a.lastSeq = Math.trunc(seq)
          for (const ev of mapSession(sse)) {
            this.applyEvent(a, ev)
            if (isTerminal(ev)) terminal = true
          }
          if (terminal) break
        }
        cleanEnd = true
      } catch (e) {
        if (a.abort.signal.aborted) throw e
        const code = httpCode(e)
        if (code !== null) {
          // 404: the run's stream is retired; its status says how it ended.
          if (code !== 404 && code < 500) throw e
          if (code === 404 && (await this.checkStatus(a, runId))) return
        } else if (!isUnreachable(e)) {
          throw e
        }
        // Dead or unreachable socket: reconnect below.
      }
      if (acc.finished) return
      if (cleanEnd) {
        lastContact = Date.now()
        if (await this.checkStatus(a, runId)) return
      }
      if (gotEvent) backoff = 1_000
      if (Date.now() - lastContact > GIVE_UP_MS) {
        if (!(await this.checkStatus(a, runId))) {
          this.applyEvent(a, {
            type: 'failed',
            message: 'Perdí el contacto con Hermes. La respuesta seguirá en el servidor: recarga el chat más tarde.',
          })
        }
        return
      }
      const woke = await this.waitForRetry(backoff, a.abort.signal)
      backoff = woke ? 1_000 : Math.min(backoff * 2, 15_000)
    }
  }

  /** Sleeps [ms], or less when the network (re)appears. Returns true when woken by the network. */
  private waitForRetry(ms: number, signal: AbortSignal): Promise<boolean> {
    return new Promise((resolve, reject) => {
      if (signal.aborted) return reject(new DOMException('Aborted', 'AbortError'))
      const done = (v: boolean) => {
        clearTimeout(t)
        window.removeEventListener('online', onOnline)
        signal.removeEventListener('abort', onAbort)
        resolve(v)
      }
      const onOnline = () => done(true)
      const onAbort = () => {
        clearTimeout(t)
        window.removeEventListener('online', onOnline)
        reject(new DOMException('Aborted', 'AbortError'))
      }
      const t = setTimeout(() => done(false), ms)
      window.addEventListener('online', onOnline, { once: true })
      signal.addEventListener('abort', onAbort, { once: true })
    })
  }

  /** Asks the server how the run ended. True when the turn was finished by the answer. */
  private async checkStatus(a: Active, runId: string): Promise<boolean> {
    let body: string
    try {
      body = await this.client.runStatus(runId)
    } catch (e) {
      if (a.abort.signal.aborted) throw e
      const code = httpCode(e)
      if (code === 404) {
        this.applyEvent(
          a,
          a.acc.parts.length > 0
            ? { type: 'completed' }
            : { type: 'failed', message: 'Hermes ya no tiene esta respuesta; recarga el chat.' },
        )
        return true
      }
      if (code !== null && code >= 500) return false
      if (isUnreachable(e)) return false
      throw e
    }
    const obj = parseJsonObject(body)
    if (!obj) return false
    switch (str(obj, 'status')?.toLowerCase()) {
      case 'completed': {
        const out = str(obj, 'output')
        if (out) this.applyEvent(a, { type: 'finalText', text: out })
        this.applyEvent(a, { type: 'completed' })
        return true
      }
      case 'failed':
        this.applyEvent(a, { type: 'failed', message: str(obj, 'error') ?? 'La ejecución falló en Hermes' })
        return true
      case 'interrupted':
        this.applyEvent(a, { type: 'failed', message: str(obj, 'error') ?? 'Hermes se reinició y cortó la respuesta' })
        return true
      case 'cancelled':
      case 'canceled':
        this.applyEvent(a, { type: 'cancelled' })
        return true
      default:
        return false // queued, running, waiting_for_approval, stopping
    }
  }

  // ---------- pending runs (survive a page reload) ----------

  private savePending(threadId: string, p: Pending): void {
    storageSet(cacheKind(), PENDING_PREFIX + threadId, `${p.runId}|${p.assistantId}|${p.createdAt}|${p.lastSeq}`)
  }

  private readPending(threadId: string): Pending | null {
    const raw = storageGet(cacheKind(), PENDING_PREFIX + threadId)
    if (!raw) return null
    const f = raw.split('|')
    if (f.length < 4) return null
    const created = Number(f[2])
    if (!Number.isFinite(created)) return null
    const seq = Number(f[3])
    return { runId: f[0], assistantId: f[1], createdAt: created, lastSeq: Number.isFinite(seq) ? seq : -1 }
  }

  private clearPending(threadId: string): void {
    storageRemove(cacheKind(), PENDING_PREFIX + threadId)
  }
}
