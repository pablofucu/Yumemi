// The "stage" under the top bar: Yumemi with a speech bubble saying what she is doing
// (port of ui/chat/StatusPanel.kt).
import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import type { ConnectionState, MessagePart, ToolStatus, YumemiMood } from '../domain'
import type { ChatUi } from '../state/chat'
import { IconCheck, IconChevron, IconClock, IconClose, IconTimeline } from './icons'
import { Sprite } from './Sprite'

export function connectionLabel(c: ConnectionState): string | null {
  switch (c.kind) {
    case 'connected':
      return 'Conectada'
    case 'checking':
      return 'Comprobando…'
    case 'unauthorized':
      return 'Clave de API incorrecta'
    case 'not_configured':
      return 'Falta configurar la conexión'
    case 'unreachable':
      return 'Sin conexión con Hermes'
    default:
      return null
  }
}

type StatusText = { title: string; detail: string | null; mono?: boolean }

function firstLine(s: string): string {
  return (s.split('\n').find((l) => l.trim() !== '') ?? '').trim()
}

export function statusText(state: ChatUi, talking: boolean): StatusText {
  const lastMsg = state.messages[state.messages.length - 1]
  const last = lastMsg && lastMsg.role === 'assistant' ? lastMsg : null
  const parts: MessagePart[] = last?.parts ?? []
  const actions = parts.filter((p) => p.type === 'tool' || p.type === 'subagent').length
  const actionsLabel = actions === 0 ? null : actions === 1 ? '1 acción' : `${actions} acciones`
  const pending = [...parts].reverse().find((p) => p.type === 'approval' && p.status === 'pending')
  if (pending && pending.type === 'approval') {
    return { title: 'Necesito tu permiso', detail: firstLine(pending.command), mono: true }
  }
  switch (state.mood) {
    case 'working': {
      const running = [...parts].reverse().find((p) => (p.type === 'tool' || p.type === 'subagent') && p.status === 'running')
      if (running?.type === 'tool') return { title: `Usando ${running.tool}`, detail: firstLine(running.argsPreview), mono: true }
      if (running?.type === 'subagent') return { title: 'Trabajando en una subtarea', detail: running.summary }
      return { title: 'Trabajando…', detail: actionsLabel }
    }
    case 'thinking': {
      const t = [...parts].reverse().find((p) => p.type === 'reasoning' || p.type === 'commentary')
      const line = t && (t.type === 'reasoning' || t.type === 'commentary') ? ([...t.text.split('\n')].reverse().find((l) => l.trim() !== '') ?? '').trim() : null
      return { title: 'Pensando…', detail: line || actionsLabel }
    }
    case 'surprised':
      return { title: '¡Oh! Una cosa más…', detail: actionsLabel }
    case 'happy':
      return { title: '¡Hecho!', detail: actionsLabel ? `${actionsLabel} en esta respuesta` : null }
    case 'error':
      return { title: '¡Ay! Algo ha salido mal', detail: state.error ?? last?.error ?? null }
    case 'sleeping':
      return { title: 'Zzz…', detail: connectionLabel(state.connection) }
    case 'idle':
      if (talking || state.isSending) return { title: 'Escribiendo…', detail: actionsLabel }
      if (state.connection.kind === 'connected') return { title: 'Lista para ayudarte', detail: null }
      return { title: 'Aquí estoy', detail: connectionLabel(state.connection) }
  }
}

const statusOfPart = (p: MessagePart): ToolStatus | null => (p.type === 'tool' || p.type === 'subagent' ? p.status : null)

function formatElapsed(sec: number): string {
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`
}

/** Seconds since [start], ticking once a second while [running]; null otherwise. */
function useElapsed(start: number | null, running: boolean): number | null {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!running) return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [running])
  if (!running || start === null) return null
  return Math.max(0, Math.floor((now - start) / 1000))
}

const moodClass = (m: YumemiMood) => `mood-${m}`

function Chip({ children, onClick, className = '', label }: { children: ReactNode; onClick: () => void; className?: string; label?: string }) {
  return (
    <button type="button" className={`chip ${className}`} onClick={onClick} aria-label={label}>
      {children}
    </button>
  )
}

export function StatusPanel({
  state,
  talking,
  expanded,
  detailsOn,
  onToggle,
  onDetails,
}: {
  state: ChatUi
  talking: boolean
  expanded: boolean
  detailsOn: boolean
  onToggle: () => void
  onDetails: () => void
}) {
  const status = statusText(state, talking)
  const lastMsg = state.messages[state.messages.length - 1]
  const last = lastMsg && lastMsg.role === 'assistant' ? lastMsg : null
  const parts = last?.parts ?? []
  const done = parts.filter((p) => statusOfPart(p) === 'ok').length
  const running = parts.filter((p) => statusOfPart(p) === 'running').length
  const failed = parts.filter((p) => statusOfPart(p) === 'failed').length
  const lastUser = [...state.messages].reverse().find((m) => m.role === 'user')
  const elapsed = useElapsed(lastUser?.createdAt ?? null, last?.isStreaming === true)

  return (
    <section className={`stage ${moodClass(state.mood)} ${expanded ? 'expanded' : ''}`} aria-live="polite">
      <div className="stage-glow" />
      <div className="stage-row">
        {expanded && (
          <div className="stage-sprite">
            <Sprite mood={state.mood} talking={talking} className="stage-sprite-inner" />
          </div>
        )}
        <div
          className="stage-body"
          role="button"
          tabIndex={0}
          onClick={onToggle}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              onToggle()
            }
          }}
          aria-expanded={expanded}
        >
          <div className={expanded ? 'bubble-speech' : ''}>
            <div className="stage-title">{status.title}</div>
            {status.detail && <div className={`stage-detail ${status.mono ? 'mono' : ''}`}>{status.detail}</div>}
          </div>
        </div>
        <button type="button" className="icon-btn" onClick={onToggle} aria-label={expanded ? 'Plegar' : 'Desplegar'}>
          <IconChevron size={18} className={expanded ? 'rot' : ''} />
        </button>
      </div>
      {expanded && (done + running + failed > 0 || elapsed !== null) && (
        <div className="stage-chips">
          {done > 0 && (
            <Chip onClick={onDetails} className="ok">
              <IconCheck size={13} /> {done}
            </Chip>
          )}
          {running > 0 && (
            <Chip onClick={onDetails} className="run">
              <span className="spinner" /> {running}
            </Chip>
          )}
          {failed > 0 && (
            <Chip onClick={onDetails} className="bad">
              <IconClose size={13} /> {failed}
            </Chip>
          )}
          {elapsed !== null && (
            <Chip onClick={onDetails} className="muted">
              <IconClock size={13} /> {formatElapsed(elapsed)}
            </Chip>
          )}
          <Chip onClick={onDetails} className={detailsOn ? 'on' : ''} label={detailsOn ? 'Ocultar detalles' : 'Ver detalles'}>
            <IconTimeline size={13} />
          </Chip>
        </div>
      )}
    </section>
  )
}
