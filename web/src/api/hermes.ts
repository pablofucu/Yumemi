// HTTP client for the Hermes Agent API server (port of HermesClient.kt).
// The browser talks to Hermes directly. The API key only ever goes in the Authorization header.
import type { ConnectionState, RemoteSession } from '../domain'
import { parseSessionList, parseCreatedSession } from '../chat/transcript'
import { parseJsonObject, str } from '../util'
import { HermesError, isAbort, pageProtocol } from './errors'
import { readSse, type SseEvent } from './sse'

/** Per-request ephemeral system prompt telling Hermes that this app renders Markdown. */
export const RENDER_HINT =
  'Client note from Yumemi, the web app the user is talking to you through (it runs in a browser). This replaces the earlier API-server note about plain text: this app renders GitHub-flavored Markdown (headings, bold, italic, strikethrough, bullet and numbered lists, tables, fenced code blocks with syntax highlighting, block quotes, links and horizontal rules). Use Markdown whenever it makes an answer clearer or easier to scan: a table for comparisons or key/value data, a fenced code block with its language for commands, code, logs and file contents, short ## headings and lists for long or multi-step answers. Keep casual, short replies as plain conversational text without headings. Images, raw HTML, math and MEDIA: tags are not rendered; give file paths and URLs as plain text or links.'

export type HermesConfig = { baseUrl: string; apiKey: string }

const IDEMPOTENT_METHODS = new Set(['GET', 'HEAD', 'PATCH', 'PUT', 'DELETE'])
const RETRY_DELAYS_MS = [800, 2_000]
const FIRST_PAGE_SIZE = 25
const PAGE_SIZE = 100
const MAX_PAGES = 11
const REQUEST_TIMEOUT_MS = 30_000
/** Keepalive comments arrive every ~10 s, so 45 s of silence means a dead socket. */
const STREAM_IDLE_MS = 45_000

type RequestOpts = {
  method?: string
  query?: Record<string, string>
  body?: unknown
  headers?: Record<string, string>
  auth?: boolean
  signal?: AbortSignal
  timeoutMs?: number
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new DOMException('Aborted', 'AbortError'))
    const t = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      clearTimeout(t)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

/** An AbortSignal that fires when any of the given ones does. */
function anySignal(signals: (AbortSignal | undefined)[]): { signal: AbortSignal; dispose: () => void } {
  const ctrl = new AbortController()
  const handlers: [AbortSignal, () => void][] = []
  for (const s of signals) {
    if (!s) continue
    if (s.aborted) {
      ctrl.abort(s.reason)
      break
    }
    const h = () => ctrl.abort(s.reason)
    s.addEventListener('abort', h, { once: true })
    handlers.push([s, h])
  }
  return { signal: ctrl.signal, dispose: () => handlers.forEach(([s, h]) => s.removeEventListener('abort', h)) }
}

export class HermesClient {
  constructor(private readonly config: () => HermesConfig) {}

  get isConfigured(): boolean {
    return this.config().baseUrl.trim() !== ''
  }

  // ---------- plumbing ----------

  private urlFor(base: string, segments: string[], query?: Record<string, string>): string {
    if (!base.trim()) throw HermesError.notConfigured()
    let u: URL
    try {
      u = new URL(base)
    } catch {
      throw HermesError.notConfigured()
    }
    const path = u.pathname.replace(/\/+$/, '') + '/' + segments.map(encodeURIComponent).join('/')
    u.pathname = path
    u.search = ''
    u.hash = ''
    if (query) for (const [k, v] of Object.entries(query)) u.searchParams.set(k, v)
    return u.toString()
  }

  private buildInit(key: string, o: RequestOpts, accept: string): RequestInit {
    const headers: Record<string, string> = { Accept: accept, ...(o.headers ?? {}) }
    if ((o.auth ?? true) && key.trim()) headers['Authorization'] = `Bearer ${key.trim()}`
    let body: string | undefined
    if (o.body !== undefined) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(o.body)
    }
    return {
      method: o.method ?? 'GET',
      headers,
      body,
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
    }
  }

  private unreachable(cause: unknown, base: string, timeout = false): HermesError {
    return HermesError.unreachable(cause, { baseUrl: base, pageProtocol: pageProtocol() }, timeout)
  }

  /** Whether a failed request may be retried: idempotent methods, or POSTs carrying an Idempotency-Key. */
  private isRetryable(o: RequestOpts): boolean {
    const method = (o.method ?? 'GET').toUpperCase()
    return IDEMPOTENT_METHODS.has(method) || !!o.headers?.['Idempotency-Key']
  }

  /** One HTTP call against explicit values, with timeout and retries on network errors. */
  private async send(base: string, key: string, segments: string[], o: RequestOpts = {}): Promise<Response> {
    const url = this.urlFor(base, segments, o.query)
    const init = this.buildInit(key, o, 'application/json')
    const retryable = this.isRetryable(o)
    let attempt = 0
    for (;;) {
      const timer = new AbortController()
      let timedOut = false
      const t = setTimeout(() => {
        timedOut = true
        timer.abort()
      }, o.timeoutMs ?? REQUEST_TIMEOUT_MS)
      const { signal, dispose } = anySignal([o.signal, timer.signal])
      try {
        return await fetch(url, { ...init, signal })
      } catch (e) {
        if (o.signal?.aborted) throw e
        const err = this.unreachable(e, base, timedOut)
        if (!retryable || attempt >= RETRY_DELAYS_MS.length) throw err
        await sleep(RETRY_DELAYS_MS[attempt++], o.signal)
      } finally {
        clearTimeout(t)
        dispose()
      }
    }
  }

  /** Runs the request and returns the body of a 2xx response, mapping failures to [HermesError]. */
  private async fetchText(segments: string[], o: RequestOpts = {}): Promise<string> {
    const { baseUrl, apiKey } = this.config()
    const resp = await this.send(baseUrl, apiKey, segments, o)
    let text: string
    try {
      text = await resp.text()
    } catch (e) {
      throw this.unreachable(e, baseUrl)
    }
    if (resp.status === 401 || resp.status === 403) throw HermesError.unauthorized()
    if (!resp.ok) throw HermesError.http(resp.status, text.slice(0, 300))
    return text
  }

  /** Only the HTTP status code; network failures become [HermesError] 'unreachable'. */
  private async statusOf(base: string, key: string, segments: string[], o: RequestOpts = {}): Promise<number> {
    const resp = await this.send(base, key, segments, o)
    try {
      await resp.body?.cancel()
    } catch {
      /* ignore */
    }
    return resp.status
  }

  /** Opens DNS/TCP/TLS ahead of the first real request; failures are irrelevant. */
  async prewarm(): Promise<void> {
    if (!this.isConfigured) return
    try {
      const { baseUrl, apiKey } = this.config()
      await this.statusOf(baseUrl, apiKey, ['health'], { auth: false, timeoutMs: 10_000 })
    } catch {
      /* ignore */
    }
  }

  // ---------- connection test ----------

  /** /health for reachability, /v1/capabilities (Bearer) for auth. Uses the given values, not the saved ones. */
  async testConnection(baseUrl: string, apiKey: string): Promise<ConnectionState> {
    if (!baseUrl.trim()) return { kind: 'not_configured' }
    try {
      const health = await this.statusOf(baseUrl, apiKey, ['health'], { auth: false })
      if (health < 200 || health > 299) {
        return { kind: 'unreachable', message: `Hermes respondió con un error (${health})` }
      }
      const caps = await this.statusOf(baseUrl, apiKey, ['v1', 'capabilities'])
      if (caps === 401 || caps === 403) return { kind: 'unauthorized' }
      if (caps >= 200 && caps <= 299) return { kind: 'connected' }
      if (caps === 404 || caps === 405) {
        // Older servers without /v1/capabilities: verify the key against the sessions API.
        const s = await this.statusOf(baseUrl, apiKey, ['api', 'sessions'], { query: { limit: '1' } })
        if (s === 401 || s === 403) return { kind: 'unauthorized' }
        if (s >= 200 && s <= 299) return { kind: 'connected' }
        return { kind: 'unreachable', message: `Hermes respondió con un error (${s})` }
      }
      return { kind: 'unreachable', message: `Hermes respondió con un error (${caps})` }
    } catch (e) {
      if (e instanceof HermesError) {
        if (e.kind === 'not_configured') return { kind: 'not_configured' }
        if (e.kind === 'unauthorized') return { kind: 'unauthorized' }
        return { kind: 'unreachable', message: e.message || 'No puedo conectar con Hermes' }
      }
      return { kind: 'unreachable', message: 'No puedo conectar con Hermes' }
    }
  }

  // ---------- sessions ----------

  /**
   * Lists all sessions, paging until the server runs out. The first page is small so [onPage] can
   * show recent threads quickly; cron runs pile up and can make the full listing take many requests.
   */
  async listSessions(onPage: (page: RemoteSession[]) => void = () => {}): Promise<RemoteSession[]> {
    const all = new Map<string, RemoteSession>()
    let offset = 0
    let pageSize = FIRST_PAGE_SIZE
    for (let i = 0; i < MAX_PAGES; i++) {
      const body = await this.fetchText(['api', 'sessions'], {
        query: { limit: String(pageSize), offset: String(offset) },
      })
      const page = parseSessionList(body)
      const before = all.size
      for (const s of page) if (!all.has(s.id)) all.set(s.id, s)
      onPage(page)
      if (page.length < pageSize || all.size === before) return [...all.values()]
      offset += page.length
      pageSize = PAGE_SIZE
    }
    return [...all.values()]
  }

  /** The first page of sessions only: enough to watch the recently active ones. */
  async listRecentSessions(limit = FIRST_PAGE_SIZE): Promise<RemoteSession[]> {
    return parseSessionList(await this.fetchText(['api', 'sessions'], { query: { limit: String(limit) } }))
  }

  async createSession(title?: string): Promise<RemoteSession> {
    const body: Record<string, unknown> = {}
    if (title && title.trim()) body.title = title
    const text = await this.fetchText(['api', 'sessions'], { method: 'POST', body })
    const s = parseCreatedSession(text)
    if (!s) throw HermesError.protocol('sesión sin id')
    return s
  }

  async renameSession(id: string, title: string): Promise<void> {
    await this.fetchText(['api', 'sessions', id], { method: 'PATCH', body: { title } })
  }

  async deleteSession(id: string): Promise<void> {
    try {
      await this.fetchText(['api', 'sessions', id], { method: 'DELETE' })
    } catch (e) {
      if (!(e instanceof HermesError && e.kind === 'http' && e.code === 404)) throw e
    }
  }

  messagesBody(id: string): Promise<string> {
    return this.fetchText(['api', 'sessions', id, 'messages'], { query: { inline_images: 'false' } })
  }

  // ---------- approvals ----------

  /** Answers a pending `approval.request` (choice: once, session, always or deny). */
  async answerApproval(runId: string, requestId: string, choice: string): Promise<void> {
    const body: Record<string, unknown> = { choice }
    if (requestId.trim()) body.request_id = requestId
    await this.fetchText(['v1', 'runs', runId, 'approval'], { method: 'POST', body })
  }

  // ---------- runs ----------

  /**
   * Starts a run on the session and returns its run id. Re-posting with the same [idempotencyKey]
   * returns the same run, so callers may retry on network errors. 404/405 -> runs_unsupported.
   */
  async startRun(threadId: string, text: string, idempotencyKey: string, signal?: AbortSignal): Promise<string> {
    let resp: string
    try {
      resp = await this.fetchText(['v1', 'runs'], {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: { input: text, session_id: threadId, instructions: RENDER_HINT },
        signal,
      })
    } catch (e) {
      if (e instanceof HermesError && e.kind === 'http' && (e.code === 404 || e.code === 405)) {
        throw HermesError.runsUnsupported()
      }
      throw e
    }
    const id = str(parseJsonObject(resp), 'run_id')
    if (!id) throw HermesError.protocol('ejecución sin id')
    return id
  }

  /** SSE of a run; [lastSeq] >= 0 resumes after that sequence number. HTTP 404 = the run is retired. */
  runEvents(runId: string, lastSeq: number, signal?: AbortSignal): AsyncGenerator<SseEvent> {
    const headers: Record<string, string> = {}
    if (lastSeq >= 0) headers['Last-Event-ID'] = String(lastSeq)
    return this.sse(['v1', 'runs', runId, 'events'], { method: 'GET', headers, signal })
  }

  /** Raw JSON body of GET /v1/runs/{id}. */
  runStatus(runId: string): Promise<string> {
    return this.fetchText(['v1', 'runs', runId])
  }

  /** Best-effort stop; "not active" (409) and "unknown" (404) are fine. */
  async stopRun(runId: string): Promise<void> {
    try {
      await this.fetchText(['v1', 'runs', runId, 'stop'], { method: 'POST' })
    } catch (e) {
      if (!(e instanceof HermesError && e.kind === 'http' && (e.code === 409 || e.code === 404))) throw e
    }
  }

  // ---------- streaming ----------

  streamSessionChat(threadId: string, text: string, signal?: AbortSignal): AsyncGenerator<SseEvent> {
    return this.sse(['api', 'sessions', threadId, 'chat', 'stream'], {
      method: 'POST',
      body: { input: text, system_message: RENDER_HINT },
      signal,
    })
  }

  streamChatCompletions(threadId: string, text: string, signal?: AbortSignal): AsyncGenerator<SseEvent> {
    return this.sse(['v1', 'chat', 'completions'], {
      method: 'POST',
      headers: { 'X-Hermes-Session-Id': threadId },
      body: {
        model: 'hermes-agent',
        stream: true,
        messages: [
          { role: 'system', content: RENDER_HINT },
          { role: 'user', content: text },
        ],
      },
      signal,
    })
  }

  /**
   * SSE over fetch + ReadableStream (EventSource cannot send Authorization). Aborting [signal] or
   * leaving the loop cancels the HTTP call. Silence for 45 s counts as a dead socket.
   */
  private async *sse(segments: string[], o: RequestOpts): AsyncGenerator<SseEvent> {
    const { baseUrl, apiKey } = this.config()
    const url = this.urlFor(baseUrl, segments, o.query)
    const init = this.buildInit(apiKey, o, 'text/event-stream')
    const idle = new AbortController()
    let idleFired = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const arm = () => {
      clearTimeout(timer)
      timer = setTimeout(() => {
        idleFired = true
        idle.abort()
      }, STREAM_IDLE_MS)
    }
    const { signal, dispose } = anySignal([o.signal, idle.signal])
    try {
      arm()
      let resp: Response
      try {
        resp = await fetch(url, { ...init, signal })
      } catch (e) {
        if (o.signal?.aborted) throw e
        throw this.unreachable(e, baseUrl, idleFired)
      }
      if (resp.status === 401 || resp.status === 403) {
        void resp.body?.cancel().catch(() => {})
        throw HermesError.unauthorized()
      }
      if (!resp.ok) {
        let detail = ''
        try {
          detail = (await resp.text()).slice(0, 300)
        } catch {
          /* ignore */
        }
        throw HermesError.http(resp.status, detail)
      }
      if (!resp.body) throw HermesError.protocol('sin cuerpo')
      try {
        yield* readSse(resp.body, arm)
      } catch (e) {
        if (o.signal?.aborted || (isAbort(e) && !idleFired)) throw e
        if (e instanceof HermesError) throw e
        throw this.unreachable(e, baseUrl, idleFired)
      }
    } finally {
      clearTimeout(timer)
      dispose()
      idle.abort() // releases the connection if the consumer stopped early
    }
  }
}
