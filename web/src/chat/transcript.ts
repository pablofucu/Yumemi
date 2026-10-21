// Server transcript parsing, session list parsing, transcript merging and part persistence
// (port of TranscriptParser.kt, TranscriptMerger.kt, PartCodec.kt).
import type { ChatMessage, MessagePart, RemoteSession, ToolCallPart, ToolStatus } from '../domain'
import {
  arr,
  contentText,
  isObject,
  parseJson,
  parseJsonObject,
  str,
  time,
  truncated,
  type JsonObject,
} from '../util'

// ---------- transcript ----------

function messageArray(root: unknown): unknown[] | null {
  if (Array.isArray(root)) return root
  if (isObject(root)) return arr(root, 'messages', 'data', 'items', 'results') ?? []
  return []
}

function toolCalls(e: unknown): ToolCallPart[] {
  let list: unknown[] | null = null
  if (Array.isArray(e)) list = e
  else if (typeof e === 'string') {
    const parsed = parseJson(e)
    if (Array.isArray(parsed)) list = parsed
  }
  if (!list) return []
  const out: ToolCallPart[] = []
  list.forEach((item, n) => {
    if (!isObject(item)) return
    const fn = isObject(item['function']) ? item['function'] : item
    const name = str(fn, 'name', 'tool')
    if (!name) return
    const argsEl = fn['arguments'] ?? fn['args']
    let args: string
    if (argsEl === undefined || argsEl === null) args = ''
    else if (typeof argsEl === 'string') args = argsEl
    else if (typeof argsEl === 'number' || typeof argsEl === 'boolean') args = String(argsEl)
    else args = JSON.stringify(argsEl)
    out.push({
      type: 'tool',
      id: str(item, 'id', 'call_id') ?? `srv-call-${n}-${name}`,
      tool: name,
      argsPreview: truncated(args, 300),
      resultPreview: null,
      status: 'ok',
      durationSec: null,
    })
  })
  return out
}

/**
 * Parses GET /api/sessions/{id}/messages defensively. Consecutive assistant/tool messages
 * are grouped into a single assistant message, like the streamed turn looks.
 */
export function parseTranscript(body: string): ChatMessage[] {
  const root = parseJson(body)
  if (root === null) return []
  const array = messageArray(root) ?? []

  const result: ChatMessage[] = []
  let curParts: MessagePart[] | null = null
  let curId = ''
  let curTime = 0

  const flush = () => {
    if (curParts && curParts.length > 0) {
      result.push({ id: curId, role: 'assistant', parts: [...curParts], createdAt: curTime })
    }
    curParts = null
  }

  array.forEach((el, i) => {
    if (!isObject(el)) return
    const role = str(el, 'role')?.toLowerCase()
    if (!role) return
    const t = time(el, 'timestamp', 'created_at', 'createdAt', 'time', 'ts')
    const id = str(el, 'id', 'message_id') ?? `srv-${i}`
    switch (role) {
      case 'user':
      case 'human': {
        flush()
        const text = contentText(el['content']) ?? str(el, 'text')
        if (text && text.trim() !== '') {
          result.push({ id, role: 'user', parts: [{ type: 'text', text }], createdAt: t })
        }
        break
      }
      case 'assistant':
      case 'ai':
      case 'model': {
        if (curParts === null) {
          curParts = []
          curId = id
          curTime = t
        }
        const parts: MessagePart[] = curParts
        const reasoning = str(el, 'reasoning', 'reasoning_content', 'thinking')
        if (reasoning && reasoning.trim() !== '') parts.push({ type: 'reasoning', text: reasoning })
        const text = contentText(el['content'])
        if (text && text.trim() !== '') parts.push({ type: 'text', text })
        for (const call of toolCalls(el['tool_calls'])) parts.push(call)
        break
      }
      case 'tool':
      case 'function': {
        if (curParts === null) {
          curParts = []
          curId = id
          curTime = t
        }
        const parts: MessagePart[] = curParts
        const callId = str(el, 'tool_call_id', 'call_id')
        const name = str(el, 'name', 'tool_name', 'tool')
        const rawContent = contentText(el['content'])
        const content = rawContent === null ? null : truncated(rawContent, 600)
        let idx = callId ? parts.findIndex((p) => p.type === 'tool' && p.id === callId) : -1
        if (idx < 0 && name) {
          idx = parts.findIndex((p) => p.type === 'tool' && p.tool === name && p.resultPreview === null)
        }
        if (idx >= 0) {
          parts[idx] = { ...(parts[idx] as ToolCallPart), resultPreview: content }
        } else {
          parts.push({
            type: 'tool',
            id: callId ?? `srv-tool-${i}`,
            tool: name ?? 'herramienta',
            argsPreview: '',
            resultPreview: content,
            status: 'ok',
            durationSec: null,
          })
        }
        break
      }
      default:
        break // system etc.
    }
  })
  flush()
  return result
}

/**
 * The final answer of a session: the text of the last assistant message that does not call
 * tools. This is what a cron run delivers (e.g. to Telegram).
 */
export function finalAssistantText(body: string): string | null {
  const root = parseJson(body)
  if (root === null) return null
  const array = Array.isArray(root) ? root : isObject(root) ? arr(root, 'messages', 'data', 'items', 'results') : null
  if (!array) return null
  for (let i = array.length - 1; i >= 0; i--) {
    const o = array[i]
    if (!isObject(o)) continue
    const role = str(o, 'role')?.toLowerCase()
    if (!role || !['assistant', 'ai', 'model'].includes(role)) continue
    if (toolCalls(o['tool_calls']).length > 0) continue
    const text = contentText(o['content'])?.trim()
    if (text) return text
  }
  return null
}

// ---------- session list ----------

/** Hermes names cron run sessions `cron_<jobId>_<yyyyMMdd_HHmmss>` and tags them with source "cron". */
export function isCronRun(s: Pick<RemoteSession, 'id' | 'source'>): boolean {
  return (s.source ?? '').toLowerCase() === 'cron' || s.id.startsWith('cron_')
}

export function parseSession(o: JsonObject): RemoteSession | null {
  const id = str(o, 'id', 'session_id', 'sessionId')
  if (!id) return null
  return {
    id,
    title: str(o, 'title', 'name') ?? '',
    updatedAt: time(o, 'updated_at', 'last_active', 'last_activity', 'last_message_at', 'ended_at', 'started_at', 'created_at'),
    preview: truncated(str(o, 'preview', 'last_message', 'summary', 'snippet') ?? '', 200),
    source: str(o, 'source', 'platform'),
    startedAt: time(o, 'started_at', 'created_at'),
    endedAt: time(o, 'ended_at'),
  }
}

export function parseSessionList(body: string): RemoteSession[] {
  const root = parseJson(body)
  if (root === null) return []
  const array = Array.isArray(root) ? root : isObject(root) ? (arr(root, 'sessions', 'data', 'items', 'results') ?? []) : []
  const out: RemoteSession[] = []
  for (const el of array) {
    if (!isObject(el)) continue
    const s = parseSession(el)
    if (s) out.push(s)
  }
  return out
}

/** Create/get responses: the session object, possibly wrapped in "session"/"data". */
export function parseCreatedSession(body: string): RemoteSession | null {
  const root = parseJsonObject(body)
  if (!root) return null
  const direct = parseSession(root)
  if (direct) return direct
  for (const key of ['session', 'data']) {
    const inner = root[key]
    if (isObject(inner)) {
      const s = parseSession(inner)
      if (s) return s
    }
  }
  return null
}

// ---------- merger ----------

type Turn = { user: ChatMessage | null; assistant: ChatMessage | null }

function turnsOf(list: ChatMessage[]): Turn[] {
  const out: Turn[] = []
  let i = 0
  while (i < list.length) {
    const m = list[i]
    if (m.role === 'user') {
      const next = list[i + 1]
      if (next && next.role === 'assistant') {
        out.push({ user: m, assistant: next })
        i += 2
      } else {
        out.push({ user: m, assistant: null })
        i += 1
      }
    } else {
      out.push({ user: null, assistant: m })
      i += 1
    }
  }
  return out
}

function textOf(m: ChatMessage): string {
  return m.parts
    .filter((p): p is Extract<MessagePart, { type: 'text' }> => p.type === 'text')
    .map((p) => p.text)
    .join('\n')
}

const norm = (m: ChatMessage): string => textOf(m).trim().replace(/\s+/g, ' ')
const hasDetail = (m: ChatMessage): boolean => m.parts.some((p) => p.type !== 'text')

function pickAssistant(server: ChatMessage | null, local: ChatMessage | null): ChatMessage | null {
  if (!local) return server
  if (!server) return local
  if (!local.isStreaming && textOf(local).trim() === '' && textOf(server).trim() !== '' && hasDetail(local)) {
    // Detail survived locally but the answer never reached the stream: keep both.
    return { ...local, parts: [...local.parts, { type: 'text', text: textOf(server) }] }
  }
  const useLocal = !!local.isStreaming || hasDetail(local) || textOf(local).length >= textOf(server).length
  return useLocal ? local : { ...server, id: local.id }
}

/** Merges the server transcript with locally stored messages (which keep reasoning/tool detail). */
export function mergeTranscripts(server: ChatMessage[] | null, local: ChatMessage[]): ChatMessage[] {
  if (server === null) return local
  const st = turnsOf(server)
  const lt = turnsOf(local)
  const used = new Array<boolean>(lt.length).fill(false)
  let cursor = 0
  const out: ChatMessage[] = []

  for (const s of st) {
    let idx = -1
    if (s.user) {
      const key = norm(s.user)
      for (let j = cursor; j < lt.length; j++) {
        const lu = lt[j].user
        if (!used[j] && lu && norm(lu) === key) {
          idx = j
          break
        }
      }
    }
    if (idx >= 0) {
      used[idx] = true
      cursor = idx + 1
      const l = lt[idx]
      out.push((l.user ?? s.user)!)
      const a = pickAssistant(s.assistant, l.assistant)
      if (a) out.push(a)
    } else {
      if (s.user) out.push(s.user)
      if (s.assistant) out.push(s.assistant)
    }
  }
  lt.forEach((t, j) => {
    if (!used[j]) {
      if (t.user) out.push(t.user)
      if (t.assistant) out.push(t.assistant)
    }
  })
  return out
}

// ---------- part codec (persistence) ----------

type PartDto = {
  type: string
  text?: string
  id?: string
  tool?: string
  args?: string
  result?: string | null
  status?: string
  duration?: number | null
  run?: string
  choices?: string[]
  answer?: string | null
}

export function encodeParts(parts: MessagePart[]): string {
  const dtos: PartDto[] = parts.map((p): PartDto => {
    switch (p.type) {
      case 'text':
        return { type: 'text', text: p.text }
      case 'reasoning':
        return { type: 'reasoning', text: p.text }
      case 'commentary':
        return { type: 'commentary', text: p.text }
      case 'tool':
        return { type: 'tool', id: p.id, tool: p.tool, args: p.argsPreview, result: p.resultPreview, status: p.status, duration: p.durationSec }
      case 'subagent':
        return { type: 'subagent', id: p.id, text: p.summary, status: p.status, duration: p.durationSec }
      case 'approval':
        return {
          type: 'approval',
          id: p.requestId,
          run: p.runId,
          tool: p.command,
          text: p.description,
          choices: p.choices,
          status: p.status,
          answer: p.answer,
        }
    }
  })
  return JSON.stringify(dtos)
}

function toolStatus(s: string | undefined): ToolStatus {
  const v = (s ?? '').toLowerCase()
  // A persisted RUNNING call can never finish any more.
  if (v === 'running') return 'failed'
  if (v === 'failed') return 'failed'
  return 'ok'
}

export function decodeParts(json: string): MessagePart[] {
  const root = parseJson(json)
  if (!Array.isArray(root)) return []
  const out: MessagePart[] = []
  for (const el of root) {
    if (!isObject(el)) continue
    const d = el as PartDto
    switch (d.type) {
      case 'text':
        out.push({ type: 'text', text: d.text ?? '' })
        break
      case 'reasoning':
        out.push({ type: 'reasoning', text: d.text ?? '' })
        break
      case 'commentary':
        out.push({ type: 'commentary', text: d.text ?? '' })
        break
      case 'tool':
        out.push({
          type: 'tool',
          id: d.id ?? '',
          tool: d.tool ?? '',
          argsPreview: d.args ?? '',
          resultPreview: d.result ?? null,
          status: toolStatus(d.status),
          durationSec: d.duration ?? null,
        })
        break
      case 'subagent':
        out.push({
          type: 'subagent',
          id: d.id ?? '',
          summary: d.text ?? '',
          status: toolStatus(d.status),
          durationSec: d.duration ?? null,
        })
        break
      case 'approval':
        out.push({
          type: 'approval',
          requestId: d.id ?? '',
          runId: d.run ?? '',
          command: d.tool ?? '',
          description: d.text ?? '',
          choices: Array.isArray(d.choices) ? d.choices : [],
          // A stored question can no longer be answered: its turn is over.
          status: (d.status ?? '').toLowerCase() === 'answered' ? 'answered' : 'expired',
          answer: d.answer ?? null,
        })
        break
      default:
        break
    }
  }
  return out
}
