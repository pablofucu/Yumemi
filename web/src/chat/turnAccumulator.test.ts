import { describe, expect, it } from 'vitest'
import type { SseEvent } from '../api/sse'
import type { ApprovalPart, MessagePart, ToolCallPart } from '../domain'
import { decodeParts, encodeParts } from './transcript'
import { mapChatCompletions, mapSession, type StreamEvent } from './streamEvent'
import { TurnAccumulator } from './turnAccumulator'

const sse = (event: string | null, data: string, id: string | null = null): SseEvent => ({ event, data, id })

function feed(acc: TurnAccumulator, event: string | null, data: string) {
  for (const ev of mapSession(sse(event, data))) acc.apply(ev)
}

describe('session turn', () => {
  it('maps events to parts in order', () => {
    const acc = new TurnAccumulator()
    feed(acc, 'assistant.delta', '{"delta":"Hola "}')
    feed(acc, 'assistant.delta', '{"delta":"mundo"}')
    feed(acc, 'tool.started', '{"tool":"terminal","preview":"ls"}')
    feed(acc, 'tool.completed', '{"tool":"terminal","duration":1.5,"error":false,"preview":"a b"}')
    feed(acc, 'reasoning.delta', '{"text":"pienso"}')
    feed(acc, 'assistant.delta', '{"delta":"fin"}')
    feed(acc, 'mystery.event', '{}')
    feed(acc, 'run.completed', '{}')
    expect(acc.finished).toBe(true)
    expect(acc.parts).toHaveLength(4)
    expect(acc.parts[0]).toEqual({ type: 'text', text: 'Hola mundo' })
    const tool = acc.parts[1] as ToolCallPart
    expect(tool.status).toBe('ok')
    expect(tool.durationSec).toBeCloseTo(1.5)
    expect(tool.resultPreview).toBe('a b')
    expect(acc.parts[2]).toEqual({ type: 'reasoning', text: 'pienso' })
    expect(acc.parts[3]).toEqual({ type: 'text', text: 'fin' })
  })

  it('marks tool.failed and run.failed', () => {
    const acc = new TurnAccumulator()
    feed(acc, 'tool.started', '{"tool":"web"}')
    feed(acc, 'tool.failed', '{"tool":"web"}')
    feed(acc, 'run.failed', '{"error":"boom"}')
    expect((acc.parts[0] as ToolCallPart).status).toBe('failed')
    expect(acc.error).toBe('boom')
  })

  it('error event fails and assistant.completed fills a missing answer', () => {
    expect(mapSession(sse('error', '{"message":"boom"}'))).toEqual([{ type: 'failed', message: 'boom' }])
    const acc = new TurnAccumulator()
    acc.apply({ type: 'toolStarted', id: 't1', tool: 'terminal', preview: 'ls' })
    feed(acc, 'assistant.completed', '{"content":"Listo"}')
    expect(acc.parts[acc.parts.length - 1]).toEqual({ type: 'text', text: 'Listo' })
  })

  it('reads the event type from the JSON when no SSE event name is given', () => {
    expect(mapSession(sse(null, '{"event":"run.cancelled"}'))).toEqual([{ type: 'cancelled' }])
    expect(mapSession(sse('message', '{"type":"message.delta","delta":"x"}'))).toEqual([{ type: 'text', text: 'x' }])
  })

  it('run.completed carries the output as final text, which never overrides streamed text', () => {
    const evs = mapSession(sse(null, '{"event":"run.completed","output":"Listo","seq":4}', '4'))
    expect(evs).toEqual([{ type: 'finalText', text: 'Listo' }, { type: 'completed' }])
    const acc = new TurnAccumulator()
    evs.forEach((e) => acc.apply(e))
    expect(acc.finished).toBe(true)
    expect(acc.parts).toEqual([{ type: 'text', text: 'Listo' }])

    const acc2 = new TurnAccumulator()
    acc2.apply({ type: 'text', text: 'Hola' })
    evs.forEach((e) => acc2.apply(e))
    expect(acc2.parts).toEqual([{ type: 'text', text: 'Hola' }])

    expect(mapSession(sse(null, '{"event":"run.completed","output":""}'))).toEqual([{ type: 'completed' }])
  })

  it('run.interrupted, replay.truncated and reasoning.available', () => {
    expect(mapSession(sse(null, '{"event":"run.interrupted","error":"apagado"}'))).toEqual([
      { type: 'failed', message: 'apagado' },
    ])
    expect(mapSession(sse(null, '{"event":"run.interrupted"}'))).toEqual([
      { type: 'failed', message: 'Hermes se reinició y cortó la respuesta' },
    ])
    expect(mapSession(sse(null, '{"event":"replay.truncated"}'))[0].type).toBe('unknown')
    expect(mapSession(sse(null, '{"event":"reasoning.available","text":"pienso"}'))).toEqual([
      { type: 'reasoning', text: 'pienso' },
    ])
  })

  it('[DONE] completes and tool.progress with a "_" tool is reasoning', () => {
    expect(mapSession(sse(null, '[DONE]'))).toEqual([{ type: 'completed' }])
    expect(mapSession(sse('tool.progress', '{"tool_name":"_thinking","delta":"hm"}'))).toEqual([
      { type: 'reasoning', text: 'hm' },
    ])
    expect(mapSession(sse('tool.progress', '{"tool_name":"terminal","delta":"x"}'))).toEqual([])
  })

  it('matches tool completion by id first, then by running tool name', () => {
    const acc = new TurnAccumulator()
    acc.apply({ type: 'toolStarted', id: 'a', tool: 'terminal', preview: '1' })
    acc.apply({ type: 'toolStarted', id: 'b', tool: 'terminal', preview: '2' })
    acc.apply({ type: 'toolFinished', id: 'b', tool: 'terminal', ok: true, durationSec: 2, preview: 'r' })
    expect((acc.parts[0] as ToolCallPart).status).toBe('running')
    expect((acc.parts[1] as ToolCallPart).status).toBe('ok')
    acc.apply({ type: 'toolFinished', id: null, tool: 'terminal', ok: false, durationSec: null, preview: null })
    expect((acc.parts[0] as ToolCallPart).status).toBe('failed')
  })

  it('reports a tool that starts after answer text', () => {
    const acc = new TurnAccumulator()
    expect(acc.apply({ type: 'toolStarted', id: null, tool: 'a', preview: '' })).toBe(false)
    acc.apply({ type: 'text', text: 'hola' })
    expect(acc.apply({ type: 'toolStarted', id: null, tool: 'b', preview: '' })).toBe(true)
  })

  it('stop() cancels once and keeps what arrived', () => {
    const acc = new TurnAccumulator()
    acc.apply({ type: 'text', text: 'a medias' })
    acc.apply({ type: 'toolStarted', id: 'x', tool: 't', preview: '' })
    acc.stop()
    expect(acc.stopped).toBe(true)
    expect(acc.finished).toBe(true)
    expect((acc.parts[1] as ToolCallPart).status).toBe('failed')
  })

  it('already-streamed commentary turns the streamed text into commentary', () => {
    const acc = new TurnAccumulator()
    acc.apply({ type: 'text', text: 'Voy a mirar' })
    feed(acc, 'assistant.commentary', '{"text":"Voy a mirar","already_streamed":true}')
    expect(acc.parts).toEqual([{ type: 'commentary', text: 'Voy a mirar' }])
  })
})

describe('approvals', () => {
  it('becomes a card and expires when the turn ends', () => {
    const ev = mapSession(
      sse(
        'approval.request',
        '{"event":"approval.request","run_id":"run_1","request_id":"r1","command":"rm -rf build","description":"recursive delete","choices":["once","deny"]}',
      ),
    )[0] as Extract<StreamEvent, { type: 'approval' }>
    expect(ev.runId).toBe('run_1')
    expect(ev.choices).toEqual(['once', 'deny'])

    const acc = new TurnAccumulator()
    acc.apply(ev)
    acc.apply(ev) // the same request again does not add a second card
    expect(acc.parts).toHaveLength(1)
    expect((acc.parts[0] as ApprovalPart).status).toBe('pending')

    acc.apply({ type: 'completed' })
    expect((acc.parts[0] as ApprovalPart).status).toBe('expired')
  })

  it('uses the four default choices and ignores requests without run id', () => {
    const [ev] = mapSession(sse('approval.request', '{"run_id":"r","command":"x"}')) as Extract<
      StreamEvent,
      { type: 'approval' }
    >[]
    expect(ev.choices).toEqual(['once', 'session', 'always', 'deny'])
    expect(mapSession(sse('approval.request', '{"command":"x"}'))).toEqual([])
  })

  it('answered cards survive the turn ending', () => {
    const acc = new TurnAccumulator()
    acc.apply({ type: 'approval', runId: 'r', requestId: 'q', command: 'ls', description: '', choices: ['once'] })
    acc.setApproval('q', 'answered', 'once')
    acc.apply({ type: 'completed' })
    expect((acc.parts[0] as ApprovalPart).status).toBe('answered')
  })
})

describe('chat completions', () => {
  it('maps reasoning, tool progress and content, completing tools automatically', () => {
    const acc = new TurnAccumulator(true)
    const f = (event: string | null, data: string) => mapChatCompletions(sse(event, data)).forEach((e) => acc.apply(e))
    f(null, '{"choices":[{"delta":{"reasoning_content":"hmm"}}]}')
    f('hermes.tool.progress', '{"tool":"terminal","label":"ls"}')
    f(null, '{"choices":[{"delta":{"content":"Listo"}}]}')
    f(null, '[DONE]')
    expect(acc.parts).toHaveLength(3)
    expect((acc.parts[1] as ToolCallPart).status).toBe('ok')
    expect(acc.finished).toBe(true)
  })
})

describe('part codec', () => {
  it('round-trips parts', () => {
    const parts: MessagePart[] = [
      { type: 'text', text: 'a' },
      { type: 'reasoning', text: 'b' },
      { type: 'commentary', text: 'c' },
      { type: 'tool', id: '1', tool: 't', argsPreview: 'args', resultPreview: 'res', status: 'ok', durationSec: 2 },
      { type: 'subagent', id: '2', summary: 'sum', status: 'failed', durationSec: null },
    ]
    expect(decodeParts(encodeParts(parts))).toEqual(parts)
  })

  it('a stored running tool fails and a stored open question expires', () => {
    const json = encodeParts([
      { type: 'tool', id: '1', tool: 't', argsPreview: '', resultPreview: null, status: 'running', durationSec: null },
      { type: 'approval', requestId: 'q', runId: 'r', command: 'ls', description: 'd', choices: ['once'], status: 'pending', answer: null },
      { type: 'approval', requestId: 'p', runId: 'r', command: 'ls', description: 'd', choices: ['once'], status: 'answered', answer: 'once' },
    ])
    const out = decodeParts(json)
    expect((out[0] as ToolCallPart).status).toBe('failed')
    expect((out[1] as ApprovalPart).status).toBe('expired')
    expect((out[2] as ApprovalPart).status).toBe('answered')
    expect(decodeParts('not json')).toEqual([])
  })
})
