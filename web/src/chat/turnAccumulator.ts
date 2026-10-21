// Folds StreamEvents of one assistant turn into an ordered list of message parts
// (port of TurnAccumulator.kt).
import type { ApprovalStatus, MessagePart, ToolStatus } from '../domain'
import type { StreamEvent } from './streamEvent'

/**
 * [autoCompleteTools]: for transports that only announce tool starts (chat completions),
 * running tools are marked OK as soon as the next content arrives.
 */
export class TurnAccumulator {
  parts: MessagePart[] = []
  finished = false
  error: string | null = null
  stopped = false
  private counter = 0

  constructor(private readonly autoCompleteTools = false) {}

  /** Applies an event. Returns true when a tool started after some answer text had already streamed. */
  apply(ev: StreamEvent): boolean {
    const list = [...this.parts]
    let toolAfterText = false
    switch (ev.type) {
      case 'text': {
        if (this.autoCompleteTools) finishRunning(list, 'ok')
        const last = list[list.length - 1]
        if (last && last.type === 'text') list[list.length - 1] = { type: 'text', text: last.text + ev.text }
        else list.push({ type: 'text', text: ev.text })
        break
      }
      case 'reasoning': {
        if (this.autoCompleteTools) finishRunning(list, 'ok')
        const last = list[list.length - 1]
        if (last && last.type === 'reasoning') list[list.length - 1] = { type: 'reasoning', text: last.text + ev.text }
        else list.push({ type: 'reasoning', text: ev.text })
        break
      }
      case 'commentary': {
        if (ev.alreadyStreamed) {
          // The same words already arrived as answer deltas: turn that text into commentary.
          let idx = -1
          for (let i = list.length - 1; i >= 0; i--) {
            if (list[i].type === 'text') {
              idx = i
              break
            }
          }
          if (idx >= 0) {
            const t = (list[idx] as { text: string }).text
            if (t.trim() === ev.text.trim()) list[idx] = { type: 'commentary', text: ev.text.trim() }
          }
        } else if (ev.text.trim() !== '') {
          list.push({ type: 'commentary', text: ev.text })
        }
        break
      }
      case 'toolStarted': {
        if (this.autoCompleteTools) finishRunning(list, 'ok')
        toolAfterText = list.some((p) => p.type === 'text' && p.text.trim() !== '')
        list.push({
          type: 'tool',
          id: ev.id ?? `tool-${this.counter++}`,
          tool: ev.tool,
          argsPreview: ev.preview,
          resultPreview: null,
          status: 'running',
          durationSec: null,
        })
        break
      }
      case 'toolFinished': {
        let idx = ev.id !== null ? list.findIndex((p) => p.type === 'tool' && p.id === ev.id) : -1
        if (idx < 0) {
          idx = list.findIndex((p) => p.type === 'tool' && p.status === 'running' && p.tool === ev.tool)
        }
        const status: ToolStatus = ev.ok ? 'ok' : 'failed'
        if (idx >= 0) {
          const old = list[idx] as Extract<MessagePart, { type: 'tool' }>
          list[idx] = {
            ...old,
            status,
            durationSec: ev.durationSec ?? old.durationSec,
            resultPreview: ev.preview ?? old.resultPreview,
          }
        } else {
          list.push({
            type: 'tool',
            id: ev.id ?? `tool-${this.counter++}`,
            tool: ev.tool,
            argsPreview: '',
            resultPreview: ev.preview,
            status,
            durationSec: ev.durationSec,
          })
        }
        break
      }
      case 'subagentStarted': {
        list.push({
          type: 'subagent',
          id: ev.id ?? `sub-${this.counter++}`,
          summary: ev.summary,
          status: 'running',
          durationSec: null,
        })
        break
      }
      case 'subagentFinished': {
        let idx = ev.id !== null ? list.findIndex((p) => p.type === 'subagent' && p.id === ev.id) : -1
        if (idx < 0) idx = list.findIndex((p) => p.type === 'subagent' && p.status === 'running')
        const status: ToolStatus = ev.ok ? 'ok' : 'failed'
        if (idx >= 0) {
          const old = list[idx] as Extract<MessagePart, { type: 'subagent' }>
          list[idx] = {
            ...old,
            status,
            durationSec: ev.durationSec ?? old.durationSec,
            summary: ev.summary ?? old.summary,
          }
        } else {
          list.push({
            type: 'subagent',
            id: ev.id ?? `sub-${this.counter++}`,
            summary: ev.summary ?? 'Subagente',
            status,
            durationSec: ev.durationSec,
          })
        }
        break
      }
      case 'finalText': {
        // Only when the answer never streamed (non-streaming providers, late failures).
        if (!list.some((p) => p.type === 'text' && p.text.trim() !== '')) {
          if (this.autoCompleteTools) finishRunning(list, 'ok')
          list.push({ type: 'text', text: ev.text })
        }
        break
      }
      case 'approval': {
        const known =
          ev.requestId !== '' && list.some((p) => p.type === 'approval' && p.requestId === ev.requestId)
        if (!known) {
          list.push({
            type: 'approval',
            requestId: ev.requestId,
            runId: ev.runId,
            command: ev.command,
            description: ev.description,
            choices: ev.choices,
            status: 'pending',
            answer: null,
          })
        }
        break
      }
      case 'completed':
        finishRunning(list, 'ok')
        this.finished = true
        break
      case 'failed':
        finishRunning(list, 'failed')
        this.error = ev.message
        this.finished = true
        break
      case 'cancelled':
        finishRunning(list, 'failed')
        this.stopped = true
        this.finished = true
        break
      case 'unknown':
        break
    }
    this.parts = list
    return toolAfterText
  }

  /** Updates the approval card [requestId] after the user picked an option. */
  setApproval(requestId: string, status: ApprovalStatus, answer: string | null): void {
    this.parts = this.parts.map((p) => (p.type === 'approval' && p.requestId === requestId ? { ...p, status, answer } : p))
  }

  /** Local failure (network error etc.) keeping what already arrived. */
  fail(message: string): void {
    this.apply({ type: 'failed', message })
  }

  /** User pressed stop. */
  stop(): void {
    if (!this.finished) this.apply({ type: 'cancelled' })
  }
}

function finishRunning(list: MessagePart[], status: ToolStatus): void {
  for (let i = 0; i < list.length; i++) {
    const p = list[i]
    if ((p.type === 'tool' || p.type === 'subagent') && p.status === 'running') list[i] = { ...p, status }
    if (p.type === 'approval' && p.status !== 'answered') list[i] = { ...p, status: 'expired' }
  }
}
