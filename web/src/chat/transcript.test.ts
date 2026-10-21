import { describe, expect, it } from 'vitest'
import { commandOf, fromTranscript, snippetOf, TOOL_WAIT_MS, USER_WAIT_MS } from './activity'
import {
  finalAssistantText,
  isCronRun,
  mergeTranscripts,
  parseCreatedSession,
  parseSessionList,
  parseTranscript,
} from './transcript'
import type { ChatMessage, ToolCallPart } from '../domain'

describe('parseTranscript', () => {
  it('groups assistant and tool messages of a turn', () => {
    const body = JSON.stringify({
      messages: [
        { role: 'user', content: 'hola' },
        {
          role: 'assistant',
          content: '',
          tool_calls: [{ id: 'c1', function: { name: 'terminal', arguments: '{"cmd":"ls"}' } }],
        },
        { role: 'tool', tool_call_id: 'c1', content: 'file.txt' },
        { role: 'assistant', content: 'Hecho', reasoning: 'pensando' },
      ],
    })
    const msgs = parseTranscript(body)
    expect(msgs).toHaveLength(2)
    expect(msgs[0].role).toBe('user')
    const parts = msgs[1].parts
    expect(parts[0].type).toBe('tool')
    expect((parts[0] as ToolCallPart).resultPreview).toBe('file.txt')
    expect((parts[0] as ToolCallPart).argsPreview).toBe('{"cmd":"ls"}')
    expect(parts.some((p) => p.type === 'reasoning')).toBe(true)
    expect(parts.some((p) => p.type === 'text' && p.text === 'Hecho')).toBe(true)
  })

  it('accepts a bare array, content parts, object arguments and unknown roles', () => {
    const msgs = parseTranscript(
      JSON.stringify([
        { role: 'system', content: 'ignored' },
        {
          role: 'human',
          content: [
            { type: 'text', text: 'a' },
            { type: 'text', text: 'b' },
          ],
        },
        { role: 'ai', content: 'ok', tool_calls: [{ name: 'web', args: { q: 1 } }] },
      ]),
    )
    expect(msgs).toHaveLength(2)
    expect(msgs[0].parts).toEqual([{ type: 'text', text: 'a\nb' }])
    expect((msgs[1].parts[1] as ToolCallPart).argsPreview).toBe('{"q":1}')
  })

  it('matches an orphan tool result by name and survives garbage', () => {
    const msgs = parseTranscript(
      JSON.stringify({
        data: [
          { role: 'assistant', content: 'x', tool_calls: [{ function: { name: 'n' } }] },
          { role: 'tool', name: 'n', content: 'r' },
        ],
      }),
    )
    expect((msgs[0].parts[1] as ToolCallPart).resultPreview).toBe('r')
    expect(parseTranscript('')).toEqual([])
    expect(parseTranscript('{oops')).toEqual([])
    expect(parseTranscript('{"messages":"no"}')).toEqual([])
  })

  it('finalAssistantText skips tool-calling messages', () => {
    const body = JSON.stringify([
      { role: 'assistant', content: 'resultado final' },
      { role: 'assistant', content: 'voy a mirar', tool_calls: [{ function: { name: 'x' } }] },
    ])
    expect(finalAssistantText(body)).toBe('resultado final')
  })
})

describe('session lists', () => {
  it('parses defensively', () => {
    const body =
      '{"sessions":[{"session_id":"a","title":"T","updated_at":1700000000},{"id":"b","created_at":"2024-01-01T00:00:00Z"},{"nothing":1}]}'
    const list = parseSessionList(body)
    expect(list).toHaveLength(2)
    expect(list[0].updatedAt).toBe(1_700_000_000_000)
    expect(list[1].updatedAt).toBeGreaterThan(0)
    expect(parseSessionList('[{"id":"x"}]')).toHaveLength(1)
    expect(parseSessionList('garbage')).toEqual([])
  })

  it('treats epoch millis and numeric strings', () => {
    const [a, b] = parseSessionList('[{"id":"a","updated_at":1700000000123},{"id":"b","updated_at":"1700000000"}]')
    expect(a.updatedAt).toBe(1_700_000_000_123)
    expect(b.updatedAt).toBe(1_700_000_000_000)
  })

  it('parses created sessions, also wrapped', () => {
    expect(parseCreatedSession('{"id":"s1"}')?.id).toBe('s1')
    expect(parseCreatedSession('{"session":{"session_id":"s2","title":"x"}}')?.id).toBe('s2')
    expect(parseCreatedSession('{"data":{"id":"s3"}}')?.id).toBe('s3')
    expect(parseCreatedSession('{"ok":true}')).toBeNull()
  })

  it('detects cron runs', () => {
    expect(isCronRun({ id: 'cron_ab_20260101_000000', source: null })).toBe(true)
    expect(isCronRun({ id: 'x', source: 'Cron' })).toBe(true)
    expect(isCronRun({ id: 'x', source: 'api_server' })).toBe(false)
  })
})

const msg = (
  id: string,
  role: 'user' | 'assistant',
  parts: ChatMessage['parts'],
  createdAt = 0,
  isStreaming = false,
): ChatMessage => ({ id, role, parts, createdAt, isStreaming })

describe('mergeTranscripts', () => {
  it('prefers local detail and keeps unsent local turns', () => {
    const server = [
      msg('s1', 'user', [{ type: 'text', text: 'hola' }]),
      msg('s2', 'assistant', [{ type: 'text', text: 'Hecho' }]),
    ]
    const local = [
      msg('l1', 'user', [{ type: 'text', text: 'hola' }], 1),
      msg('l2', 'assistant', [{ type: 'reasoning', text: 'r' }, { type: 'text', text: 'Hecho' }], 2),
      msg('l3', 'user', [{ type: 'text', text: 'nuevo' }], 3),
    ]
    expect(mergeTranscripts(server, local).map((m) => m.id)).toEqual(['l1', 'l2', 'l3'])
  })

  it('returns local messages while the server transcript is unknown', () => {
    const local = [msg('l1', 'user', [{ type: 'text', text: 'x' }])]
    expect(mergeTranscripts(null, local)).toBe(local)
  })

  it('uses the server answer when the local one is shorter and has no detail', () => {
    const server = [
      msg('s1', 'user', [{ type: 'text', text: 'q' }]),
      msg('s2', 'assistant', [{ type: 'text', text: 'respuesta larga' }]),
    ]
    const local = [
      msg('l1', 'user', [{ type: 'text', text: 'q' }]),
      msg('l2', 'assistant', [{ type: 'text', text: 'resp' }]),
    ]
    const merged = mergeTranscripts(server, local)
    expect(merged[1].id).toBe('l2')
    expect(merged[1].parts).toEqual([{ type: 'text', text: 'respuesta larga' }])
  })

  it('keeps local detail and appends the server answer when the stream never delivered it', () => {
    const server = [msg('s1', 'user', [{ type: 'text', text: 'q' }]), msg('s2', 'assistant', [{ type: 'text', text: 'A' }])]
    const local = [
      msg('l1', 'user', [{ type: 'text', text: 'q' }]),
      msg('l2', 'assistant', [
        { type: 'tool', id: 't', tool: 'x', argsPreview: '', resultPreview: null, status: 'ok', durationSec: null },
      ]),
    ]
    expect(mergeTranscripts(server, local)[1].parts.map((p) => p.type)).toEqual(['tool', 'text'])
  })

  it('never replaces a streaming local message', () => {
    const server = [
      msg('s1', 'user', [{ type: 'text', text: 'q' }]),
      msg('s2', 'assistant', [{ type: 'text', text: 'texto del servidor mas largo' }]),
    ]
    const local = [
      msg('l1', 'user', [{ type: 'text', text: 'q' }]),
      msg('l2', 'assistant', [{ type: 'text', text: 'a' }], 2, true),
    ]
    const merged = mergeTranscripts(server, local)
    expect(merged[1].id).toBe('l2')
    expect(merged[1].parts).toEqual([{ type: 'text', text: 'a' }])
  })
})

describe('activity rules', () => {
  const tool = (args: string): ToolCallPart => ({
    type: 'tool',
    id: 'i',
    tool: 'terminal',
    argsPreview: args,
    resultPreview: null,
    status: 'running',
    durationSec: null,
  })

  it('commandOf reads JSON arguments or plain text', () => {
    expect(commandOf(tool('{"command":"ls -la\\nsecond"}'))).toBe('ls -la')
    expect(commandOf(tool('  \n  echo hi\nmore'))).toBe('echo hi')
    expect(commandOf(tool(''))).toBe('terminal')
  })

  it('fromTranscript reads a trailing user message or tool call as work in progress', () => {
    const now = 10_000_000
    const user = [msg('u', 'user', [{ type: 'text', text: 'x' }])]
    expect(fromTranscript(user, now - 1000, now)?.kind).toBe('thinking')
    expect(fromTranscript(user, now - USER_WAIT_MS - 1, now)).toBeNull()
    const withTool = [msg('a', 'assistant', [tool('{"command":"make"}')])]
    expect(fromTranscript(withTool, now - 1000, now)).toEqual({ kind: 'terminal', detail: 'make' })
    expect(fromTranscript(withTool, now - TOOL_WAIT_MS, now)).toBeNull()
    expect(fromTranscript([msg('a', 'assistant', [{ type: 'text', text: 'listo' }])], now, now)).toBeNull()
    expect(fromTranscript([], now, now)).toBeNull()
  })

  it('snippetOf strips markdown and falls back to the last command', () => {
    expect(snippetOf(msg('a', 'assistant', [{ type: 'text', text: '## Título\n- **hola**  `x`' }]))).toBe('Título hola x')
    expect(snippetOf(msg('a', 'assistant', [tool('{"command":"ls"}')]))).toBe('› ls')
    expect(snippetOf(msg('a', 'assistant', []))).toBe('')
  })
})
