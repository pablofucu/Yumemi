// How a thread's state is read off its messages, for the activity shown in the thread list
// (port of ThreadActivityRules.kt).
import type { ChatMessage, ThreadActivity, ToolCallPart } from '../domain'
import { parseJsonObject, str } from '../util'

/** A turn that went quiet this long after the user spoke is assumed dead. */
export const USER_WAIT_MS = 3 * 60_000
/** Tools (a long terminal command) can stay silent for longer. */
export const TOOL_WAIT_MS = 10 * 60_000

/** Activity of a turn running in this app. */
export function fromLiveTurn(message: ChatMessage): ThreadActivity {
  const parts = message.parts
  if (parts.some((p) => p.type === 'approval' && p.status === 'pending')) {
    return { kind: 'waiting', detail: '' }
  }
  const tool = [...parts].reverse().find((p): p is ToolCallPart => p.type === 'tool' && p.status === 'running')
  if (tool) return { kind: 'terminal', detail: commandOf(tool) }
  if (parts.some((p) => p.type === 'subagent' && p.status === 'running')) {
    return { kind: 'thinking', detail: '' }
  }
  return parts[parts.length - 1]?.type === 'text' ? { kind: 'writing', detail: '' } : { kind: 'thinking', detail: '' }
}

/**
 * Activity guessed from a server transcript (a turn driven by another client). Hermes stores a
 * turn's answer when it ends, so a transcript ending in the user's message or in a tool call
 * means the agent is still working, as long as the session moved recently.
 */
export function fromTranscript(messages: ChatMessage[], updatedAt: number, now: number): ThreadActivity | null {
  const last = messages[messages.length - 1]
  if (!last) return null
  const age = now - updatedAt
  if (last.role === 'user') {
    return age < USER_WAIT_MS ? { kind: 'thinking', detail: '' } : null
  }
  if (age >= TOOL_WAIT_MS) return null
  const p = last.parts[last.parts.length - 1]
  if (!p) return null
  if (p.type === 'tool') return { kind: 'terminal', detail: commandOf(p) }
  if (p.type === 'subagent') return { kind: 'thinking', detail: '' }
  return null
}

/** The command a tool runs, for a one-line terminal preview. */
export function commandOf(tool: ToolCallPart): string {
  const args = tool.argsPreview.trim()
  const fromJson = args.startsWith('{')
    ? str(parseJsonObject(args), 'command', 'cmd', 'code', 'query', 'path', 'url', 'file_path')
    : null
  const source = fromJson ?? args
  const line = source.split(/\r?\n/).find((l) => l.trim() !== '')?.trim() ?? ''
  return (line === '' ? tool.tool : line).slice(0, 120)
}

/** One-line snippet of a message for the list ("" when it has nothing to show). */
export function snippetOf(m: ChatMessage): string {
  const text = m.parts
    .filter((p): p is Extract<typeof p, { type: 'text' }> => p.type === 'text')
    .map((p) => p.text)
    .join(' ')
  let raw = text
  if (raw.trim() === '') {
    const tool = [...m.parts].reverse().find((p): p is ToolCallPart => p.type === 'tool')
    raw = tool ? `› ${commandOf(tool)}` : ''
  }
  return raw
    .replace(/^\s{0,3}(#+\s*|>\s*|[-*+]\s+)/gm, '')
    .replace(/[*`]+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 160)
}
