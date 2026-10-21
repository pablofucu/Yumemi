// Automation feeds ("Informes"): port of data/feeds/Feed.kt and FeedRepository.kt.
// Hermes automations publish <feeds>/index.json, <tag>/latest.json and <tag>/history.jsonl.
import { Store } from '../state/store'
import { arr, isObject, num, parseJson, str, time, type JsonObject } from '../util'
import { settings } from '../settings/settingsStore'
import { normalizeBaseUrl } from '../settings/hostRules'

export type FeedInfo = { tag: string; title: string; emoji: string; jobId: string | null }
export type FeedStatus = 'ok' | 'error' | 'empty'

export type FeedBlock =
  | { type: 'stat'; label: string; value: string; delta: string | null }
  | { type: 'kv'; items: [string, string][] }
  | { type: 'table'; columns: string[]; rows: string[][] }
  | { type: 'series'; label: string; unit: string; points: [string, number][] }
  | { type: 'text'; text: string }
  | { type: 'link'; label: string; url: string }

export type FeedEnvelope = {
  tag: string
  jobId: string | null
  runAt: number
  status: FeedStatus
  summary: string
  view: FeedBlock[]
  error: string | null
}

/** Tags end up in URLs, so only a safe slug is accepted. */
const TAG = /^[a-z0-9][a-z0-9_-]{0,63}$/
export const isValidTag = (tag: string): boolean => TAG.test(tag)

function cell(e: unknown): string {
  if (e === null || e === undefined) return ''
  if (typeof e === 'string') return e
  if (typeof e === 'number' || typeof e === 'boolean') return String(e)
  return JSON.stringify(e)
}

function pair(e: unknown): [string, string] | null {
  if (Array.isArray(e)) return e.length >= 2 ? [cell(e[0]), cell(e[1])] : null
  if (isObject(e)) {
    const k = str(e, 'label', 'key')
    return k ? [k, str(e, 'value') ?? ''] : null
  }
  return null
}

/** [label, value], {x|label|date, y|value}, or a bare number (labelled by its position). */
function point(index: number, e: unknown): [string, number] | null {
  if (Array.isArray(e)) {
    return e.length >= 2 && typeof e[1] === 'number' && Number.isFinite(e[1]) ? [cell(e[0]), e[1]] : null
  }
  if (isObject(e)) {
    const y = num(e, 'y', 'value')
    return y === null ? null : [str(e, 'x', 'label', 'date') ?? String(index + 1), y]
  }
  if (typeof e === 'number' && Number.isFinite(e)) return [String(index + 1), e]
  return null
}

function block(o: JsonObject): FeedBlock | null {
  switch (str(o, 'type')?.toLowerCase()) {
    case 'stat': {
      const value = str(o, 'value')
      return value === null ? null : { type: 'stat', label: str(o, 'label') ?? '', value, delta: str(o, 'delta') }
    }
    case 'kv': {
      const items = (arr(o, 'items') ?? []).map(pair).filter((p): p is [string, string] => p !== null)
      return items.length ? { type: 'kv', items } : null
    }
    case 'table': {
      const columns = (arr(o, 'columns') ?? []).map(cell)
      const rows = (arr(o, 'rows') ?? []).filter(Array.isArray).map((r) => (r as unknown[]).map(cell))
      return columns.length === 0 && rows.length === 0 ? null : { type: 'table', columns, rows }
    }
    case 'series': {
      const points = (arr(o, 'points') ?? []).map((p, i) => point(i, p)).filter((p): p is [string, number] => p !== null)
      return points.length ? { type: 'series', label: str(o, 'label') ?? '', unit: str(o, 'unit') ?? '', points } : null
    }
    case 'link': {
      const url = str(o, 'url')
      return url && /^https?:\/\//i.test(url) ? { type: 'link', label: str(o, 'label') ?? url, url } : null
    }
    default: {
      // "text" and any future type that carries text degrade to a paragraph.
      const text = str(o, 'text')
      return text ? { type: 'text', text } : null
    }
  }
}

function envelope(o: JsonObject): FeedEnvelope | null {
  const tag = str(o, 'tag')
  if (!tag) return null
  const s = str(o, 'status')?.toLowerCase()
  const status: FeedStatus = s === 'error' || s === 'failed' || s === 'failure' ? 'error' : s === 'empty' ? 'empty' : 'ok'
  return {
    tag,
    jobId: str(o, 'job_id'),
    runAt: time(o, 'run_at'),
    status,
    summary: str(o, 'summary') ?? '',
    view: (arr(o, 'view') ?? []).filter(isObject).map(block).filter((b): b is FeedBlock => b !== null),
    error: str(o, 'error'),
  }
}

export function parseIndex(body: string): FeedInfo[] {
  const root = parseJson(body)
  if (!isObject(root)) return []
  const seen = new Set<string>()
  const out: FeedInfo[] = []
  for (const e of arr(root, 'feeds') ?? []) {
    if (!isObject(e)) continue
    const tag = str(e, 'tag')
    if (!tag || !isValidTag(tag) || seen.has(tag)) continue
    seen.add(tag)
    out.push({ tag, title: str(e, 'title') ?? tag[0].toUpperCase() + tag.slice(1), emoji: str(e, 'emoji') ?? '', jobId: str(e, 'job_id') })
  }
  return out
}

export function parseEnvelope(body: string): FeedEnvelope | null {
  const o = parseJson(body)
  return isObject(o) ? envelope(o) : null
}

/** history.jsonl: one envelope per line; returned newest first. */
export function parseHistory(body: string): FeedEnvelope[] {
  return body
    .split('\n')
    .filter((l) => l.trim() !== '')
    .map(parseEnvelope)
    .filter((e): e is FeedEnvelope => e !== null)
    .sort((a, b) => b.runAt - a.runAt)
}

// ---------- repository ----------

export class FeedError extends Error {}

/** Base of the feeds: the saved override, or /feeds next to the Hermes API. */
export function feedsBase(): string {
  const s = settings.get()
  const own = s.feedsUrl.trim()
  if (own) return normalizeBaseUrl(own, { pageIsHttps: location.protocol === 'https:' })
  return s.baseUrl.trim() ? `${s.baseUrl.replace(/\/+$/, '')}/feeds` : ''
}

export type FeedsState = {
  feeds: FeedInfo[]
  latest: Record<string, FeedEnvelope>
  history: Record<string, FeedEnvelope[]>
  refreshing: boolean
  loaded: boolean
  error: string | null
}

const MAX_HISTORY = 120
const EMPTY: FeedsState = { feeds: [], latest: {}, history: {}, refreshing: false, loaded: false, error: null }

class FeedRepository {
  readonly state = new Store<FeedsState>(EMPTY)

  /** Body of a feed file, or null when it does not exist (404). The Hermes key is never sent: feeds are open inside the tailnet. */
  private async get(path: string): Promise<string | null> {
    const base = feedsBase()
    if (!base) throw new FeedError('Escribe primero la dirección de Hermes en Conexión')
    let resp: Response
    try {
      resp = await fetch(`${base.replace(/\/+$/, '')}/${path}`, {
        headers: { Accept: 'application/json' },
        credentials: 'omit',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
        signal: AbortSignal.timeout(20_000),
      })
    } catch {
      throw new FeedError(
        'No puedo leer los informes. Revisa que Tailscale esté activo y que /feeds permita peticiones desde este sitio (CORS).',
      )
    }
    if (resp.status === 404) return null
    if (!resp.ok) throw new FeedError(`Los informes respondieron con un error (${resp.status})`)
    return resp.text()
  }

  private async loadLatest(tag: string): Promise<void> {
    const body = await this.get(`${tag}/latest.json`)
    if (body === null) {
      this.state.set((s) => {
        const latest = { ...s.latest }
        delete latest[tag]
        return { ...s, latest }
      })
      return
    }
    const env = parseEnvelope(body)
    if (!env) throw new FeedError(`El informe de «${tag}» no tiene un formato válido`)
    this.state.set((s) => ({ ...s, latest: { ...s.latest, [tag]: env } }))
  }

  private async loadHistory(tag: string): Promise<void> {
    const body = await this.get(`${tag}/history.jsonl`)
    if (body === null) return
    const lines = body.split('\n').filter((l) => l.trim() !== '').slice(-MAX_HISTORY)
    this.state.set((s) => ({ ...s, history: { ...s.history, [tag]: parseHistory(lines.join('\n')) } }))
  }

  private async run(job: () => Promise<void>): Promise<void> {
    this.state.set((s) => ({ ...s, refreshing: true }))
    try {
      await job()
      this.state.set((s) => ({ ...s, refreshing: false, loaded: true, error: null }))
    } catch (e) {
      this.state.set((s) => ({ ...s, refreshing: false, loaded: true, error: e instanceof Error ? e.message : 'Error en los informes' }))
    }
  }

  /** Index plus the latest report of every feed, for the overview. */
  refreshAll(): Promise<void> {
    return this.run(async () => {
      const body = await this.get('index.json')
      if (body === null) throw new FeedError('No hay index.json en los feeds de Hermes')
      const feeds = parseIndex(body)
      this.state.set((s) => ({ ...s, feeds }))
      await Promise.all(feeds.map((f) => this.loadLatest(f.tag)))
    })
  }

  /** Latest report of a tag and its history, for the detail view. */
  refresh(tag: string): Promise<void> {
    if (!isValidTag(tag)) return Promise.resolve()
    return this.run(async () => {
      if (this.state.get().feeds.length === 0) {
        const idx = await this.get('index.json')
        if (idx !== null) this.state.set((s) => ({ ...s, feeds: parseIndex(idx) }))
      }
      await Promise.all([this.loadLatest(tag), this.loadHistory(tag)])
    })
  }

  dismissError(): void {
    this.state.set((s) => ({ ...s, error: null }))
  }

  /** Forgets everything (the connection changed). */
  reset(): void {
    this.state.set(EMPTY)
  }
}

export const feedRepo = new FeedRepository()
