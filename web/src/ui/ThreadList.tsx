// Thread list (port of ui/threads/ThreadListScreen.kt and ThreadWidgets.kt).
import { useEffect, useMemo, useRef, useState } from 'react'
import { repo } from '../services'
import { threadList } from '../state/threadList'
import type { ChatThread, ThreadActivity } from '../domain'
import { useThreadList } from '../state/threadList'
import { ConfirmDialog, RenameDialog } from './Dialogs'
import { IconHeart, IconMore, IconPlus, IconRefresh, IconSettings } from './icons'
import { Sprite } from './Sprite'
import { connectionLabel } from './StatusPanel'
import { ThemeButton } from './Theme'

type Section = 'now' | 'pinned' | 'today' | 'yesterday' | 'week' | 'month' | 'older'

const SECTION_LABEL: Record<Section, string> = {
  now: 'Ahora mismo',
  pinned: 'Favoritas',
  today: 'Hoy',
  yesterday: 'Ayer',
  week: 'Esta semana',
  month: 'Este mes',
  older: 'Antes',
}
const SECTION_ORDER: Section[] = ['now', 'pinned', 'today', 'yesterday', 'week', 'month', 'older']

const DAY_MS = 24 * 60 * 60 * 1000

export function startOfToday(now: number): number {
  const d = new Date(now)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

export function sectionOf(t: ChatThread, today: number): Section {
  if (t.activity) return 'now'
  if (t.pinned) return 'pinned'
  if (t.updatedAt >= today) return 'today'
  if (t.updatedAt >= today - DAY_MS) return 'yesterday'
  if (t.updatedAt >= today - 6 * DAY_MS) return 'week'
  if (t.updatedAt >= today - 30 * DAY_MS) return 'month'
  return 'older'
}

/** Short time for a row: the hour today and yesterday, the weekday this week, else the date. */
function rowTime(t: ChatThread, today: number): string {
  if (t.activity) return 'ahora'
  if (t.updatedAt <= 0) return ''
  const d = new Date(t.updatedAt)
  if (t.updatedAt >= today - DAY_MS) return d.toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' })
  if (t.updatedAt >= today - 6 * DAY_MS) return d.toLocaleDateString('es-ES', { weekday: 'short' })
  return d.toLocaleDateString('es-ES', { day: 'numeric', month: 'short' })
}

const OWN_SOURCES = new Set(['api_server', 'api', 'app', 'yumemi', 'http', 'webui', 'web'])

/** Label for a session's source, or null when it is not worth showing. */
export function sourceLabel(source: string | null): string | null {
  const s = source?.trim().toLowerCase()
  if (!s || OWN_SOURCES.has(s)) return null
  if (s === 'cli' || s === 'terminal' || s === 'tui') return 'CLI'
  const t = s.replace(/_/g, ' ')
  return t.charAt(0).toUpperCase() + t.slice(1)
}

function activityText(a: ThreadActivity): string {
  switch (a.kind) {
    case 'thinking':
      return 'Pensando…'
    case 'writing':
      return 'Escribiendo…'
    case 'waiting':
      return 'Esperando tu permiso'
    case 'terminal':
      return a.detail ? `› ${a.detail}` : 'Usando una herramienta'
  }
}

type Filter = 'all' | 'active' | 'pinned'
const FILTER_LABEL: Record<Filter, string> = { all: 'Todas', active: 'Activas', pinned: 'Favoritas' }

function ThreadRow({
  thread,
  today,
  selected,
  onOpen,
  onRename,
  onDelete,
}: {
  thread: ChatThread
  today: number
  selected: boolean
  onOpen: () => void
  onRename: () => void
  onDelete: () => void
}) {
  const [menu, setMenu] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!menu) return
    const close = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setMenu(false)
    }
    window.addEventListener('mousedown', close)
    return () => window.removeEventListener('mousedown', close)
  }, [menu])
  const tag = sourceLabel(thread.source)
  const last = thread.lastMessage || thread.preview
  return (
    <div className={`thread-row ${selected ? 'selected' : ''} ${thread.activity ? 'busy' : ''}`} ref={ref}>
      <button type="button" className="thread-main" onClick={onOpen}>
        <Sprite mood={thread.activity ? (thread.activity.kind === 'terminal' ? 'working' : 'thinking') : 'idle'} face headOnly className="thread-avatar" />
        <span className="thread-text">
          <span className="thread-title-line">
            <span className="thread-title">{thread.title}</span>
            {thread.pinned && <IconHeart size={13} fill className="pin-icon" />}
            {tag && <span className="tag">{tag}</span>}
          </span>
          {thread.activity ? (
            <span className="thread-sub activity">
              <span className="dots">
                <i />
                <i />
                <i />
              </span>
              {activityText(thread.activity)}
            </span>
          ) : (
            last && (
              <span className="thread-sub">
                {thread.lastMessage && <span className="author">{thread.lastFromUser ? 'Tú: ' : 'Yumemi: '}</span>}
                {last}
              </span>
            )
          )}
        </span>
        <span className="thread-time">{rowTime(thread, today)}</span>
      </button>
      <button type="button" className="icon-btn thread-more" onClick={() => setMenu(!menu)} aria-label="Más opciones" aria-expanded={menu}>
        <IconMore size={18} />
      </button>
      {menu && (
        <div className="menu" role="menu">
          <button type="button" role="menuitem" onClick={() => { setMenu(false); onRename() }}>
            Renombrar
          </button>
          <button type="button" role="menuitem" onClick={() => { setMenu(false); repo.setPinned(thread.id, !thread.pinned) }}>
            {thread.pinned ? 'Quitar de favoritas' : 'Añadir a favoritas'}
          </button>
          <button type="button" role="menuitem" className="danger-text" onClick={() => { setMenu(false); onDelete() }}>
            Borrar
          </button>
        </div>
      )}
    </div>
  )
}

export function ThreadList({
  selectedId,
  onOpenChat,
  onOpenSettings,
}: {
  selectedId: string | null
  onOpenChat: (id: string) => void
  onOpenSettings: () => void
}) {
  const ui = useThreadList()
  const [filter, setFilter] = useState<Filter>('all')
  const [renaming, setRenaming] = useState<ChatThread | null>(null)
  const [deleting, setDeleting] = useState<ChatThread | null>(null)
  const [now, setNow] = useState(() => Date.now())

  // Watches turns other clients run while the list is on screen.
  useEffect(() => {
    const ctrl = new AbortController()
    void threadList.watchActivity(ctrl.signal)
    const onVisible = () => {
      if (document.visibilityState === 'visible') threadList.refresh()
    }
    document.addEventListener('visibilitychange', onVisible)
    const t = setInterval(() => setNow(Date.now()), 60_000)
    return () => {
      ctrl.abort()
      document.removeEventListener('visibilitychange', onVisible)
      clearInterval(t)
    }
  }, [])

  const today = startOfToday(now)
  const busyCount = ui.threads.filter((t) => t.activity).length
  const shown = useMemo(
    () =>
      ui.threads.filter((t) => (filter === 'active' ? !!t.activity : filter === 'pinned' ? t.pinned : true)),
    [ui.threads, filter],
  )
  const groups = useMemo(() => {
    const map = new Map<Section, ChatThread[]>()
    for (const t of [...shown].sort((a, b) => b.updatedAt - a.updatedAt)) {
      const s = sectionOf(t, today)
      map.set(s, [...(map.get(s) ?? []), t])
    }
    return SECTION_ORDER.filter((s) => map.has(s)).map((s) => [s, map.get(s)!] as const)
  }, [shown, today])

  const create = async () => {
    const id = await threadList.createThread()
    if (id) onOpenChat(id)
  }

  const label = connectionLabel(ui.connection)
  const problem = ui.connection.kind !== 'connected' && ui.connection.kind !== 'unknown' && ui.connection.kind !== 'checking'

  return (
    <div className="threads">
      <header className="topbar">
        <Sprite mood={ui.mood} face className="topbar-avatar big" />
        <div className="topbar-titles">
          <h1 className="topbar-title">Yumemi</h1>
          <span className={`conn-label ${problem ? 'problem' : ''}`}>{label ?? (ui.isRefreshing ? 'Conectando…' : '')}</span>
        </div>
        <button type="button" className="icon-btn" onClick={() => threadList.refresh()} aria-label="Actualizar" title="Actualizar">
          <IconRefresh className={ui.isRefreshing ? 'spin' : ''} />
        </button>
        <ThemeButton />
        <button type="button" className="icon-btn" onClick={onOpenSettings} aria-label="Conexión" title="Conexión">
          <IconSettings />
        </button>
      </header>

      {(ui.error || problem) && (
        <div className="banner" role="alert">
          <span>{ui.error ?? label}</span>
          {ui.connection.kind === 'not_configured' || ui.connection.kind === 'unauthorized' || ui.connection.kind === 'unreachable' ? (
            <button type="button" className="btn text" onClick={onOpenSettings}>
              Conexión
            </button>
          ) : null}
        </div>
      )}

      <div className="filters" role="tablist">
        {(Object.keys(FILTER_LABEL) as Filter[]).map((f) => (
          <button key={f} type="button" role="tab" aria-selected={filter === f} className={`filter ${filter === f ? 'on' : ''}`} onClick={() => setFilter(f)}>
            {f === 'active' && busyCount > 0 ? `${FILTER_LABEL[f]} · ${busyCount}` : FILTER_LABEL[f]}
          </button>
        ))}
      </div>

      <div className="thread-scroll">
        {groups.length === 0 && (
          <div className="empty-list">
            <Sprite mood={ui.mood} className="empty-sprite" />
            <p className="muted">
              {ui.threads.length === 0
                ? ui.connection.kind === 'connected'
                  ? 'Aún no hay conversaciones. Crea la primera.'
                  : 'Aquí aparecerán tus conversaciones.'
                : filter === 'active'
                  ? 'Nadie está trabajando ahora mismo.'
                  : 'No hay favoritas todavía.'}
            </p>
          </div>
        )}
        {groups.map(([section, list]) => (
          <section key={section}>
            <h2 className="section-title">{SECTION_LABEL[section]}</h2>
            {list.map((t) => (
              <ThreadRow
                key={t.id}
                thread={t}
                today={today}
                selected={t.id === selectedId}
                onOpen={() => onOpenChat(t.id)}
                onRename={() => setRenaming(t)}
                onDelete={() => setDeleting(t)}
              />
            ))}
          </section>
        ))}
      </div>

      <button type="button" className="fab" onClick={() => void create()} aria-label="Nueva conversación" title="Nueva conversación">
        <IconPlus size={24} />
      </button>

      {renaming && (
        <RenameDialog
          initial={repo.rawTitle(renaming.id) || renaming.title}
          onSave={(t) => void threadList.renameThread(renaming.id, t)}
          onClose={() => setRenaming(null)}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title="Borrar conversación"
          message={`«${deleting.title}» se borrará también en Hermes. No se puede deshacer.`}
          confirmLabel="Borrar"
          danger
          onConfirm={() => {
            const id = deleting.id
            void threadList.deleteThread(id)
            if (id === selectedId) onOpenChat('')
          }}
          onClose={() => setDeleting(null)}
        />
      )}
    </div>
  )
}
