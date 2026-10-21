// Message views (port of ui/chat/MessageViews.kt and ApprovalViews.kt).
import { lazy, Suspense, useState } from 'react'
import type { ApprovalPart, ChatMessage, MessagePart, ToolStatus } from '../domain'
import { copyText } from './clipboard'
import { IconBrain, IconCheck, IconChevron, IconClose, IconCopy, IconShield, IconTool } from './icons'

// Markdown + syntax highlighting is the heaviest part of the bundle: load it on first use.
const Markdown = lazy(() => import('./Markdown').then((m) => ({ default: m.Markdown })))
import { Sprite } from './Sprite'

export function messageText(m: ChatMessage): string {
  return m.parts
    .filter((p): p is Extract<MessagePart, { type: 'text' }> => p.type === 'text')
    .map((p) => p.text)
    .join('\n\n')
    .trim()
}

function CopyButton({ text }: { text: string }) {
  const [done, setDone] = useState(false)
  if (!text) return null
  return (
    <button
      type="button"
      className="icon-btn small msg-copy"
      aria-label="Copiar mensaje"
      title="Copiar"
      onClick={async () => {
        if (await copyText(text)) {
          setDone(true)
          setTimeout(() => setDone(false), 1500)
        }
      }}
    >
      {done ? <IconCheck size={15} /> : <IconCopy size={15} />}
    </button>
  )
}

export function UserBubble({ message }: { message: ChatMessage }) {
  const text = messageText(message)
  return (
    <div className="msg-row user">
      <CopyButton text={text} />
      <div className="bubble user-bubble">{text}</div>
    </div>
  )
}

function TypingDots() {
  return (
    <span className="typing" aria-label="Escribiendo">
      <i />
      <i />
      <i />
    </span>
  )
}

const approvalLabel = (choice: string): string => {
  switch (choice) {
    case 'once':
      return 'Permitir una vez'
    case 'session':
      return 'Permitir en este chat'
    case 'always':
      return 'Permitir siempre'
    case 'deny':
      return 'Denegar'
    default:
      return choice.charAt(0).toUpperCase() + choice.slice(1)
  }
}

/** Hermes paused before a risky command: the question and its options as buttons. */
export function ApprovalCard({ part, onChoice }: { part: ApprovalPart; onChoice: (choice: string) => void }) {
  const active = part.status === 'pending'
  return (
    <div className={`approval ${active ? 'active' : ''}`}>
      <div className="approval-title">
        <IconShield size={18} />
        <span>¿Me dejas ejecutar esto?</span>
      </div>
      {part.description.trim() !== '' && <div className="muted small">{part.description}</div>}
      {part.command.trim() !== '' && <pre className="approval-cmd">{part.command.slice(0, 1500)}</pre>}
      {part.status === 'pending' && (
        <>
          {part.answer !== null && <div className="danger small">No he podido enviar tu respuesta. Prueba otra vez.</div>}
          <div className="approval-buttons">
            {part.choices.map((c, i) => (
              <button
                key={c}
                type="button"
                className={c === 'deny' ? 'btn outline' : i === 0 ? 'btn primary' : 'btn tonal'}
                onClick={() => onChoice(c)}
              >
                {approvalLabel(c)}
              </button>
            ))}
          </div>
        </>
      )}
      {part.status === 'sending' && <div className="muted small">Enviando: {approvalLabel(part.answer ?? '')}</div>}
      {part.status === 'answered' && (
        <div className={`small ${part.answer === 'deny' ? 'danger' : 'success'}`}>
          Respondiste: {approvalLabel(part.answer ?? '')}
        </div>
      )}
      {part.status === 'expired' && <div className="muted small">Sin respuesta: Hermes dejó de esperar.</div>}
    </div>
  )
}

const isActionPart = (p: MessagePart) => p.type === 'tool' || p.type === 'subagent'

function statusOf(p: MessagePart): ToolStatus | null {
  return p.type === 'tool' || p.type === 'subagent' ? p.status : null
}

function actionCount(n: number): string | null {
  return n === 0 ? null : n === 1 ? '1 acción' : `${n} acciones`
}

function WorkSummary({ parts, streaming, open, onToggle }: { parts: MessagePart[]; streaming: boolean; open: boolean; onToggle: () => void }) {
  const actions = parts.filter(isActionPart).length
  const failed = parts.filter((p) => statusOf(p) === 'failed').length
  const running = parts.some((p) => statusOf(p) === 'running')
  const label = [
    parts.some((p) => p.type === 'reasoning' || p.type === 'commentary') ? 'Razonamiento' : null,
    actionCount(actions),
    failed > 0 ? `${failed} con error` : null,
  ]
    .filter(Boolean)
    .join(' · ')
  return (
    <button type="button" className={`work-summary ${failed > 0 ? 'failed' : ''}`} onClick={onToggle} aria-expanded={open}>
      {running || (streaming && !open) ? <span className="spinner" /> : <IconBrain size={15} />}
      <span>{label}</span>
      <IconChevron size={14} className={open ? 'rot' : ''} />
    </button>
  )
}

function duration(sec: number | null): string {
  if (sec === null) return ''
  return sec < 10 ? `${sec.toFixed(1)} s` : `${Math.round(sec)} s`
}

function Step({ part }: { part: MessagePart }) {
  const [open, setOpen] = useState(false)
  if (part.type === 'reasoning' || part.type === 'commentary') {
    return (
      <div className={`aside ${part.type === 'reasoning' ? 'italic' : ''}`}>
        <div className="aside-label">{part.type === 'reasoning' ? 'Pensando' : 'Comentario'}</div>
        {part.text}
      </div>
    )
  }
  if (part.type === 'tool' || part.type === 'subagent') {
    const title = part.type === 'tool' ? part.tool : 'Subagente'
    const subtitle =
      part.type === 'tool'
        ? (part.argsPreview.split('\n').find((l) => l.trim() !== '') ?? '').trim()
        : part.summary
    const details: [string, string][] =
      part.type === 'tool'
        ? [
            ['Argumentos', part.argsPreview],
            ['Resultado', part.resultPreview ?? ''],
          ]
        : [['Resumen', part.summary]]
    const shown = details.filter(([, v]) => v.trim() !== '')
    return (
      <div className={`step ${part.status}`}>
        <button type="button" className="step-head" onClick={() => setOpen(!open)} aria-expanded={open} disabled={shown.length === 0}>
          <span className="step-icon">
            {part.status === 'running' ? <span className="spinner" /> : part.status === 'ok' ? <IconCheck size={14} /> : <IconClose size={14} />}
          </span>
          <IconTool size={14} />
          <span className="step-title">{title}</span>
          <span className="step-sub">{subtitle}</span>
          <span className="step-time">{duration(part.durationSec)}</span>
        </button>
        {open &&
          shown.map(([label, value]) => (
            <div key={label} className="step-detail">
              <div className="aside-label">{label}</div>
              <pre>{value}</pre>
            </div>
          ))}
      </div>
    )
  }
  return null
}

export function AssistantMessage({
  message,
  detailsExpanded,
  showInlineError,
  onApprovalChoice,
}: {
  message: ChatMessage
  detailsExpanded: boolean
  showInlineError: boolean
  onApprovalChoice: (requestId: string, choice: string) => void
}) {
  const hasDetails = message.parts.some((p) => p.type !== 'text' && p.type !== 'approval')
  // Folded, the message is a plain bubble with a one-line summary of the work; unfolded (per
  // message, or for all of them from the stage counters) it shows every step in order.
  const [override, setOverride] = useState<{ base: boolean; value: boolean } | null>(null)
  const showWork = override && override.base === detailsExpanded ? override.value : detailsExpanded
  const open = showWork && hasDetails
  const hasText = message.parts.some((p) => p.type === 'text' && p.text.trim() !== '')
  const waiting = message.parts.some((p) => p.type === 'approval' && p.status === 'pending')
  const text = messageText(message)
  return (
    <div className="msg-row assistant">
      <Sprite mood="idle" face headOnly className="avatar" />
      <div className="bubble assistant-bubble">
        {hasDetails && (
          <WorkSummary parts={message.parts} streaming={!!message.isStreaming} open={open} onToggle={() => setOverride({ base: detailsExpanded, value: !showWork })} />
        )}
        {message.parts.map((p, i) => {
          switch (p.type) {
            case 'text':
              return p.text.trim() !== '' ? (
                <Suspense key={i} fallback={<div className="md plain">{p.text}</div>}>
                  <Markdown text={p.text} />
                </Suspense>
              ) : null
            case 'approval':
              // Never folded: Hermes is waiting on the answer.
              return <ApprovalCard key={`a${p.requestId}${i}`} part={p} onChoice={(c) => onApprovalChoice(p.requestId, c)} />
            default:
              return open ? <Step key={i} part={p} /> : null
          }
        })}
        {message.isStreaming && !hasText && !waiting && <TypingDots />}
        {showInlineError && message.error && <div className="danger small">{message.error}</div>}
      </div>
      <CopyButton text={text} />
    </div>
  )
}
