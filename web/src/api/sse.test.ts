import { describe, expect, it } from 'vitest'
import { parseSse, readSse, SseChunkParser, SseLineParser } from './sse'
import type { SseEvent } from './sse'

describe('SseLineParser', () => {
  it('handles events, multiline data, comments and [DONE]', () => {
    const raw =
      ': keepalive\n' +
      'event: assistant.delta\ndata: {"delta":"Hola"}\n\n' +
      'data: line1\ndata: line2\n\n' +
      ': another comment\n\n' +
      'data: [DONE]\n\n'
    const evs = parseSse(raw)
    expect(evs).toHaveLength(3)
    expect(evs[0].event).toBe('assistant.delta')
    expect(evs[0].data).toBe('{"delta":"Hola"}')
    expect(evs[1].event).toBeNull()
    expect(evs[1].data).toBe('line1\nline2')
    expect(evs[2].data).toBe('[DONE]')
  })

  it('flushes an unterminated event', () => {
    expect(parseSse('data: x')).toHaveLength(1)
  })

  it('records the id per event and does not leak it to the next one', () => {
    const evs = parseSse('id: 7\ndata: {"a":1}\n\ndata: x\n\n: keepalive\nid: 9\nevent: foo\ndata: y\n\n')
    expect(evs).toHaveLength(3)
    expect(evs[0].id).toBe('7')
    expect(evs[1].id).toBeNull()
    expect(evs[2].id).toBe('9')
    expect(evs[2].event).toBe('foo')
  })

  it('removes only one leading space and ignores unknown fields', () => {
    const p = new SseLineParser()
    p.feed('retry: 1000')
    p.feed('data:  two spaces')
    p.feed('data')
    expect(p.feed('')?.data).toBe(' two spaces\n')
  })

  it('does not dispatch events without data', () => {
    expect(parseSse('event: ping\n\n')).toHaveLength(0)
  })
})

describe('SseChunkParser', () => {
  it('supports CRLF, lone CR and a BOM', () => {
    const evs = parseSse('﻿event: a\r\ndata: 1\r\n\r\ndata: 2\r\rdata: 3\n\n')
    expect(evs.map((e) => e.data)).toEqual(['1', '2', '3'])
    expect(evs[0].event).toBe('a')
  })

  it('is independent of chunk boundaries', () => {
    const raw = 'event: assistant.delta\r\ndata: {"delta":"hé"}\r\n\r\nid: 5\ndata: b\n\n'
    const whole = parseSse(raw)
    for (let size = 1; size <= raw.length; size++) {
      const p = new SseChunkParser()
      const got: SseEvent[] = []
      for (let i = 0; i < raw.length; i += size) got.push(...p.push(raw.slice(i, i + size)))
      got.push(...p.finish())
      expect(got).toEqual(whole)
    }
  })
})

describe('readSse', () => {
  it('decodes multi-byte characters split across network chunks', async () => {
    const bytes = new TextEncoder().encode('data: {"delta":"ñandú ✦"}\n\ndata: [DONE]\n\n')
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        // Split in the middle of multi-byte sequences.
        for (let i = 0; i < bytes.length; i += 3) controller.enqueue(bytes.slice(i, i + 3))
        controller.close()
      },
    })
    const out: string[] = []
    for await (const ev of readSse(body)) out.push(ev.data)
    expect(out).toEqual(['{"delta":"ñandú ✦"}', '[DONE]'])
  })
})
