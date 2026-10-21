// Server-Sent-Events parsing (port of Sse.kt). EventSource cannot send an Authorization header,
// so streams are read with fetch + ReadableStream and parsed here.

export type SseEvent = { event: string | null; data: string; id: string | null }

/** Incremental SSE parser working line by line. */
export class SseLineParser {
  private event: string | null = null
  private id: string | null = null
  private data = ''
  private hasData = false

  /** Feed one line (without line terminator). Returns an event when a blank line dispatches one. */
  feed(line: string): SseEvent | null {
    if (line === '') return this.dispatch()
    if (line.startsWith(':')) return null // comment / keepalive
    const idx = line.indexOf(':')
    let field: string
    let value: string
    if (idx < 0) {
      field = line
      value = ''
    } else {
      field = line.slice(0, idx)
      value = line.slice(idx + 1)
      if (value.startsWith(' ')) value = value.slice(1)
    }
    switch (field) {
      case 'event':
        this.event = value
        break
      case 'id':
        this.id = value
        break
      case 'data':
        if (this.hasData) this.data += '\n'
        this.data += value
        this.hasData = true
        break
      default:
        break // retry and unknown fields are ignored
    }
    return null
  }

  /** Call at end of stream to flush an unterminated event. */
  finish(): SseEvent | null {
    return this.dispatch()
  }

  private dispatch(): SseEvent | null {
    if (!this.hasData) {
      this.event = null
      this.id = null
      return null
    }
    const ev: SseEvent = { event: this.event, data: this.data, id: this.id }
    this.event = null
    this.id = null
    this.data = ''
    this.hasData = false
    return ev
  }
}

/**
 * Splits streamed text into lines (LF, CRLF and lone CR all end a line) and feeds them to a
 * parser. Chunk boundaries may fall anywhere, including between CR and LF.
 */
export class SseChunkParser {
  private readonly lines = new SseLineParser()
  private buf = ''
  private first = true

  /** Pushes decoded text; returns the events completed by it. */
  push(text: string): SseEvent[] {
    if (this.first && text.length > 0) {
      this.first = false
      if (text.charCodeAt(0) === 0xfeff) text = text.slice(1) // BOM
    }
    this.buf += text
    const out: SseEvent[] = []
    let start = 0
    for (let i = 0; i < this.buf.length; i++) {
      const c = this.buf[i]
      if (c !== '\n' && c !== '\r') continue
      if (c === '\r' && i === this.buf.length - 1) break // may be the first half of CRLF
      const ev = this.lines.feed(this.buf.slice(start, i))
      if (ev) out.push(ev)
      if (c === '\r' && this.buf[i + 1] === '\n') i++
      start = i + 1
    }
    this.buf = this.buf.slice(start)
    return out
  }

  /** End of stream: flushes a pending line and an unterminated event. */
  finish(): SseEvent[] {
    const out: SseEvent[] = []
    let rest = this.buf
    this.buf = ''
    if (rest.endsWith('\r')) rest = rest.slice(0, -1)
    if (rest !== '') {
      const ev = this.lines.feed(rest)
      if (ev) out.push(ev)
    }
    const last = this.lines.finish()
    if (last) out.push(last)
    return out
  }
}

/** Parses a whole SSE document at once (tests and tools). */
export function parseSse(raw: string): SseEvent[] {
  const p = new SseChunkParser()
  return [...p.push(raw), ...p.finish()]
}

/** Reads a fetch body as SSE events until the stream ends. Throws what the reader throws (abort, network). */
export async function* readSse(
  body: ReadableStream<Uint8Array>,
  onChunk?: () => void,
): AsyncGenerator<SseEvent> {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  const parser = new SseChunkParser()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      onChunk?.()
      if (value) yield* parser.push(decoder.decode(value, { stream: true }))
    }
    yield* parser.push(decoder.decode())
    yield* parser.finish()
  } finally {
    try {
      await reader.cancel()
    } catch {
      /* already closed */
    }
  }
}
