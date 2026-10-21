// Chat screen (port of ui/chat/ChatScreen.kt).
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { repo } from '../services'
import { threadList } from '../state/threadList'
import { useChat } from '../state/chat'
import { storageGet, storageSet } from '../settings/settingsStore'
import { RenameDialog } from './Dialogs'
import { IconArrowDown, IconBack, IconClose, IconEdit, IconSend, IconStop } from './icons'
import { AssistantMessage, UserBubble } from './Messages'
import { Sprite } from './Sprite'
import { StatusPanel } from './StatusPanel'

const STARTERS = ['¿Qué sabes hacer?', 'Revisa mis tareas programadas', 'Cuéntame algo bonito']
const FOLLOW_UPS = ['Explícamelo mejor', 'Hazlo más corto', 'Dame un ejemplo', 'Continúa']

const K_STAGE = 'yumemi.stageExpanded'

export function ChatView({ threadId, onBack }: { threadId: string; onBack: () => void }) {
  const { ui, ctl } = useChat(threadId)
  const [text, setText] = useState('')
  const [expanded, setExpanded] = useState(() => storageGet('local', K_STAGE) !== 'false')
  const [detailsOn, setDetailsOn] = useState(false)
  const [renaming, setRenaming] = useState(false)
  const [atBottom, setAtBottom] = useState(true)
  const scroller = useRef<HTMLDivElement>(null)
  const stick = useRef(true)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  const lastMsg = ui.messages[ui.messages.length - 1]
  const talking = !!lastMsg && lastMsg.role === 'assistant' && !!lastMsg.isStreaming && lastMsg.parts[lastMsg.parts.length - 1]?.type === 'text'

  const toggleStage = () => {
    setExpanded((v) => {
      storageSet('local', K_STAGE, String(!v))
      return !v
    })
  }

  const scrollToBottom = useCallback((smooth = false) => {
    const el = scroller.current
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  }, [])

  // Stay glued to the newest content unless the reader scrolled up.
  useLayoutEffect(() => {
    if (stick.current) scrollToBottom()
  }, [ui.messages, ui.error, scrollToBottom])

  useEffect(() => {
    stick.current = true
    setAtBottom(true)
    scrollToBottom()
    inputRef.current?.focus()
  }, [threadId, scrollToBottom])

  const onScroll = () => {
    const el = scroller.current
    if (!el) return
    const near = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    stick.current = near
    setAtBottom(near)
  }

  const send = (value: string) => {
    const t = value.trim()
    if (t === '' || ui.isSending) return
    stick.current = true
    ctl.send(t)
    setText('')
  }

  const lastAssistantIdx = (() => {
    for (let i = ui.messages.length - 1; i >= 0; i--) if (ui.messages[i].role === 'assistant') return i
    return -1
  })()
  const empty = ui.messages.length === 0
  const lastIsAssistantDone = !ui.isSending && lastMsg?.role === 'assistant' && !lastMsg.error && lastMsg.parts.length > 0
  const suggestions = !ui.isSending && !text ? (empty ? STARTERS : lastIsAssistantDone ? FOLLOW_UPS : []) : []
  const title = ui.title.trim() !== '' ? ui.title : 'Conversación'

  return (
    <div className="chat">
      <header className="topbar">
        <button type="button" className="icon-btn back-btn" onClick={onBack} aria-label="Volver">
          <IconBack />
        </button>
        <Sprite mood={ui.mood} face headOnly className="topbar-avatar" />
        <h1 className="topbar-title" title={title}>
          {title}
        </h1>
        <button type="button" className="icon-btn" onClick={() => setRenaming(true)} aria-label="Renombrar" title="Renombrar">
          <IconEdit size={18} />
        </button>
      </header>

      <StatusPanel
        state={ui}
        talking={talking}
        expanded={expanded}
        detailsOn={detailsOn}
        onToggle={toggleStage}
        onDetails={() => setDetailsOn((v) => !v)}
      />

      <div className="messages" ref={scroller} onScroll={onScroll}>
        <div className="messages-inner">
          {empty && !ui.isSending && (
            <div className="empty-chat">
              <Sprite mood={ui.mood} className="empty-sprite" />
              <h2>Escribe algo para empezar</h2>
              <p className="muted">Estoy lista para ayudarte.</p>
            </div>
          )}
          {ui.messages.map((m, i) =>
            m.role === 'user' ? (
              <UserBubble key={m.id} message={m} />
            ) : (
              <AssistantMessage
                key={m.id}
                message={m}
                detailsExpanded={detailsOn}
                showInlineError={i === lastAssistantIdx && !ui.error}
                onApprovalChoice={(rid, choice) => ctl.answerApproval(rid, choice)}
              />
            ),
          )}
          {ui.error && (
            <div className="error-card" role="alert">
              <div className="error-text">{ui.error}</div>
              <button type="button" className="btn text danger-text" onClick={() => ctl.retryLast()}>
                Reintentar
              </button>
              <button type="button" className="icon-btn" onClick={() => ctl.clearError()} aria-label="Cerrar">
                <IconClose size={16} />
              </button>
            </div>
          )}
        </div>
        {!atBottom && (
          <button type="button" className="fab-down" onClick={() => scrollToBottom(true)} aria-label="Ir al final">
            <IconArrowDown />
          </button>
        )}
      </div>

      <footer className="composer">
        {suggestions.length > 0 && (
          <div className="suggestions">
            {suggestions.map((s) => (
              <button key={s} type="button" className="suggestion" onClick={() => send(s)}>
                {s}
              </button>
            ))}
          </div>
        )}
        <div className="composer-row">
          <textarea
            ref={inputRef}
            className="composer-input"
            placeholder="Escribe a Yumemi…"
            rows={1}
            value={text}
            onChange={(e) => {
              setText(e.target.value)
              e.target.style.height = 'auto'
              e.target.style.height = `${Math.min(e.target.scrollHeight, 180)}px`
            }}
            onKeyDown={(e) => {
              // Enter sends, Shift+Enter inserts a line break; ignore Enter while an IME is composing.
              if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault()
                send(text)
                e.currentTarget.style.height = 'auto'
              }
            }}
          />
          {ui.isSending ? (
            <button type="button" className="send-btn stop" onClick={() => ctl.stop()} aria-label="Detener" title="Detener">
              <IconStop />
            </button>
          ) : (
            <button
              type="button"
              className="send-btn"
              onClick={() => send(text)}
              disabled={text.trim() === ''}
              aria-label="Enviar"
              title="Enviar (Intro)"
            >
              <IconSend />
            </button>
          )}
        </div>
      </footer>

      {renaming && (
        <RenameDialog
          initial={repo.rawTitle(threadId) || ui.title}
          onSave={(t) => void threadList.renameThread(threadId, t)}
          onClose={() => setRenaming(false)}
        />
      )}
    </div>
  )
}
