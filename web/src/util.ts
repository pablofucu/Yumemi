// Defensive JSON helpers: Hermes field names are not fully documented (port of JsonUtil.kt).

export type JsonObject = Record<string, unknown>

export function isObject(v: unknown): v is JsonObject {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

export function parseJson(text: string): unknown {
  if (!text || !text.trim()) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

export function parseJsonObject(text: string): JsonObject | null {
  const v = parseJson(text)
  return isObject(v) ? v : null
}

function primitiveText(e: unknown): string | null {
  if (typeof e === 'string') return e
  if (typeof e === 'number' || typeof e === 'boolean') return String(e)
  return null
}

/** First non-empty primitive string among keys. */
export function str(o: JsonObject | null | undefined, ...keys: string[]): string | null {
  if (!o) return null
  for (const k of keys) {
    const c = primitiveText(o[k])
    if (c) return c
  }
  return null
}

export function num(o: JsonObject | null | undefined, ...keys: string[]): number | null {
  if (!o) return null
  for (const k of keys) {
    const e = o[k]
    if (typeof e === 'number' && Number.isFinite(e)) return e
    if (typeof e === 'string' && e.trim() !== '' && Number.isFinite(Number(e))) return Number(e)
  }
  return null
}

export function bool(o: JsonObject | null | undefined, ...keys: string[]): boolean | null {
  if (!o) return null
  for (const k of keys) {
    const e = o[k]
    if (typeof e === 'boolean') return e
    if (e === 'true') return true
    if (e === 'false') return false
  }
  return null
}

export function arr(o: JsonObject | null | undefined, ...keys: string[]): unknown[] | null {
  if (!o) return null
  for (const k of keys) {
    const e = o[k]
    if (Array.isArray(e)) return e
  }
  return null
}

/** Message content may be a string, an array of {type,text} parts, or an object with "text". */
export function contentText(e: unknown): string | null {
  if (e === null || e === undefined) return null
  if (typeof e === 'string') return e
  if (typeof e === 'number' || typeof e === 'boolean') return String(e)
  if (Array.isArray(e)) {
    const out: string[] = []
    for (const item of e) {
      const t = isObject(item) ? str(item, 'text', 'content') : primitiveText(item)
      if (t) out.push(t)
    }
    const joined = out.join('\n')
    return joined === '' ? null : joined
  }
  if (isObject(e)) return str(e, 'text', 'content')
  return null
}

/** Epoch seconds, epoch millis, numeric strings or ISO-8601 strings -> epoch millis (0 if unknown). */
export function parseTime(e: unknown): number {
  let raw: string
  if (typeof e === 'number') raw = String(e)
  else if (typeof e === 'string') raw = e.trim()
  else return 0
  if (!raw) return 0
  const d = Number(raw)
  if (Number.isFinite(d)) return d < 100_000_000_000 ? Math.trunc(d * 1000) : Math.trunc(d)
  const t = Date.parse(raw)
  return Number.isNaN(t) ? 0 : t
}

export function time(o: JsonObject | null | undefined, ...keys: string[]): number {
  if (!o) return 0
  for (const k of keys) {
    const t = parseTime(o[k])
    if (t > 0) return t
  }
  return 0
}

export function truncated(s: string, max: number): string {
  return s.length <= max ? s : s.slice(0, max)
}

export function uuid(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  } catch {
    /* fall through */
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}-${Math.random().toString(36).slice(2, 10)}`
}
