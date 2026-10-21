// Transport-independent events of one assistant turn, and the mapping from raw SSE events
// (port of StreamEvent.kt).
import type { SseEvent } from '../api/sse'
import { arr, bool, isObject, num, parseJsonObject, str, truncated, type JsonObject } from '../util'

export type StreamEvent =
  | { type: 'text'; text: string }
  | { type: 'reasoning'; text: string }
  | { type: 'commentary'; text: string; alreadyStreamed: boolean }
  | { type: 'toolStarted'; id: string | null; tool: string; preview: string }
  | {
      type: 'toolFinished'
      id: string | null
      tool: string
      ok: boolean
      durationSec: number | null
      preview: string | null
    }
  | { type: 'subagentStarted'; id: string | null; summary: string }
  | { type: 'subagentFinished'; id: string | null; summary: string | null; ok: boolean; durationSec: number | null }
  /** The whole answer as sent at the end of the turn; used when no answer text streamed. */
  | { type: 'finalText'; text: string }
  | {
      type: 'approval'
      runId: string
      requestId: string
      command: string
      description: string
      choices: string[]
    }
  | { type: 'completed' }
  | { type: 'failed'; message: string }
  | { type: 'cancelled' }
  | { type: 'unknown'; eventType: string }

const PREVIEW_MAX = 600
const DEFAULT_APPROVAL_CHOICES = ['once', 'session', 'always', 'deny']

function errorText(obj: JsonObject | null): string | null {
  if (!obj) return null
  const e = obj['error']
  if (isObject(e)) return str(e, 'message', 'detail', 'error', 'code')
  return str(obj, 'error', 'message', 'detail')
}

function isError(obj: JsonObject): boolean {
  const e = obj['error']
  if (e !== undefined && e !== null) {
    if (typeof e === 'string' || typeof e === 'number' || typeof e === 'boolean') {
      const b = String(e).toLowerCase()
      if (b === 'false' || b === '' || b === 'null' || b === '0') return false
    }
    return true
  }
  const status = str(obj, 'status')?.toLowerCase()
  if (status === 'failed' || status === 'error') return true
  if (bool(obj, 'failed', 'is_error') === true) return true
  return false
}

/** `approval.request`, emitted by both transports with the same payload. */
function approval(obj: JsonObject | null): StreamEvent[] {
  if (!obj) return []
  const runId = str(obj, 'run_id')
  if (!runId) return []
  const list = arr(obj, 'choices')
    ?.filter((c): c is string => typeof c === 'string')
    .map((c) => c.toLowerCase())
    .filter((c) => c.trim() !== '')
  const choices = list && list.length > 0 ? list : DEFAULT_APPROVAL_CHOICES
  return [
    {
      type: 'approval',
      runId,
      requestId: str(obj, 'request_id') ?? '',
      command: str(obj, 'command') ?? '',
      description: str(obj, 'description') ?? '',
      choices,
    },
  ]
}

/** Events of the session stream (`/api/sessions/{id}/chat/stream`) and of the Runs API. */
export function mapSession(sse: SseEvent): StreamEvent[] {
  const data = sse.data.trim()
  if (data === '[DONE]') return [{ type: 'completed' }]
  const obj = parseJsonObject(data)
  const named = sse.event && sse.event.trim() !== '' && sse.event !== 'message' ? sse.event : null
  const type = (named ?? str(obj, 'type', 'event') ?? '').toLowerCase()

  if (type.includes('reasoning') || type.includes('thinking')) {
    const t = str(obj, 'text', 'delta', 'reasoning', 'reasoning_content', 'content') ?? (obj === null ? data : null)
    return t ? [{ type: 'reasoning', text: t }] : []
  }

  switch (type) {
    case 'approval.request':
      return approval(obj)
    case 'tool.progress': {
      // Reasoning arrives as a pseudo tool named "_thinking".
      if (!obj) return []
      const name = str(obj, 'tool_name', 'tool', 'name') ?? ''
      if (!name.startsWith('_')) return []
      const t = str(obj, 'delta', 'text', 'preview')
      return t ? [{ type: 'reasoning', text: t }] : []
    }
    case 'assistant.completed':
    case 'message.completed': {
      const t = str(obj, 'content', 'text', 'final_response')
      return t && t.trim() !== '' ? [{ type: 'finalText', text: t }] : []
    }
    case 'error':
      return [{ type: 'failed', message: errorText(obj) ?? 'La ejecución falló en Hermes' }]
    case 'assistant.delta':
    case 'message.delta': {
      const t = str(obj, 'delta', 'text', 'content', 'token') ?? (obj === null ? sse.data : null)
      return t ? [{ type: 'text', text: t }] : []
    }
    case 'assistant.commentary':
    case 'message.interim': {
      if (!obj) return []
      const t = str(obj, 'text', 'content', 'message')
      if (!t) return []
      return [{ type: 'commentary', text: t, alreadyStreamed: bool(obj, 'already_streamed') ?? false }]
    }
    case 'tool.started':
    case 'tool.start': {
      if (!obj) return []
      return [
        {
          type: 'toolStarted',
          id: str(obj, 'tool_call_id', 'call_id', 'id'),
          tool: str(obj, 'tool', 'name', 'tool_name') ?? 'herramienta',
          preview: truncated(str(obj, 'preview', 'args', 'arguments', 'input') ?? '', PREVIEW_MAX),
        },
      ]
    }
    case 'tool.completed':
    case 'tool.complete':
    case 'tool.failed': {
      if (!obj) return []
      const failed = type === 'tool.failed' || isError(obj)
      const preview = str(obj, 'preview', 'result', 'output') ?? errorText(obj)
      return [
        {
          type: 'toolFinished',
          id: str(obj, 'tool_call_id', 'call_id', 'id'),
          tool: str(obj, 'tool', 'name', 'tool_name') ?? 'herramienta',
          ok: !failed,
          durationSec: num(obj, 'duration', 'duration_s', 'duration_sec', 'elapsed'),
          preview: preview === null ? null : truncated(preview, PREVIEW_MAX),
        },
      ]
    }
    case 'subagent.start':
    case 'subagent.started': {
      if (!obj) return []
      return [
        {
          type: 'subagentStarted',
          id: str(obj, 'id', 'subagent_id', 'task_id'),
          summary: str(obj, 'summary', 'goal', 'task', 'preview', 'name') ?? 'Subagente',
        },
      ]
    }
    case 'subagent.complete':
    case 'subagent.completed':
    case 'subagent.failed': {
      if (!obj) return []
      return [
        {
          type: 'subagentFinished',
          id: str(obj, 'id', 'subagent_id', 'task_id'),
          summary: str(obj, 'summary', 'goal', 'task', 'preview', 'name'),
          ok: type !== 'subagent.failed' && !isError(obj),
          durationSec: num(obj, 'duration', 'duration_s', 'duration_sec', 'elapsed'),
        },
      ]
    }
    case 'run.completed':
    case 'done': {
      // Runs API: the terminal event carries the whole answer. finalText is only applied
      // when no answer text streamed, so it is harmless for the session stream.
      const out = str(obj, 'output')
      return out && out.trim() !== ''
        ? [{ type: 'finalText', text: out }, { type: 'completed' }]
        : [{ type: 'completed' }]
    }
    case 'run.interrupted':
      return [{ type: 'failed', message: errorText(obj) ?? 'Hermes se reinició y cortó la respuesta' }]
    case 'replay.truncated':
      return [{ type: 'unknown', eventType: type }]
    case 'run.failed':
      return [{ type: 'failed', message: errorText(obj) ?? 'La ejecución falló en Hermes' }]
    case 'run.cancelled':
    case 'run.canceled':
      return [{ type: 'cancelled' }]
  }

  if (obj) {
    const r = str(obj, 'reasoning', 'reasoning_content')
    if (r) return [{ type: 'reasoning', text: r }]
  }
  return [{ type: 'unknown', eventType: type }]
}

/** Events of POST /v1/chat/completions (OpenAI-style chunks plus `hermes.tool.progress`). */
export function mapChatCompletions(sse: SseEvent): StreamEvent[] {
  const data = sse.data.trim()
  if (data === '[DONE]') return [{ type: 'completed' }]
  const eventName = (sse.event ?? '').toLowerCase()
  const obj = parseJsonObject(data)

  if (eventName === 'approval.request') return approval(obj)

  if (eventName.includes('tool')) {
    let tool: string
    let label: string
    if (obj) {
      tool = str(obj, 'tool', 'name', 'tool_name') ?? 'herramienta'
      label = truncated(str(obj, 'label', 'preview', 'message', 'text', 'args') ?? '', PREVIEW_MAX)
    } else {
      tool = 'herramienta'
      label = truncated(data, PREVIEW_MAX)
    }
    return [{ type: 'toolStarted', id: null, tool, preview: label }]
  }
  if (!obj) return [{ type: 'unknown', eventType: eventName }]
  const topError = obj['error']
  if (topError !== undefined && topError !== null) {
    return [{ type: 'failed', message: errorText(obj) ?? 'La ejecución falló en Hermes' }]
  }
  const out: StreamEvent[] = []
  const choice = arr(obj, 'choices')?.[0]
  const c = isObject(choice) ? choice : null
  const delta = c && isObject(c['delta']) ? c['delta'] : c && isObject(c['message']) ? c['message'] : null
  if (delta) {
    const r = str(delta, 'reasoning_content', 'reasoning')
    if (r) out.push({ type: 'reasoning', text: r })
    const content = str(delta, 'content')
    if (content) out.push({ type: 'text', text: content })
  }
  return out
}

export function isTerminal(ev: StreamEvent): boolean {
  return ev.type === 'completed' || ev.type === 'failed' || ev.type === 'cancelled'
}
