// "Informes": overview of the automation feeds and the full report of each one
// (port of ui/feeds/FeedsScreen.kt and FeedBlocks.kt).
import { Fragment, useEffect, useMemo, useState } from 'react'
import { feedRepo, type FeedBlock, type FeedEnvelope, type FeedInfo, type FeedStatus } from '../data/feeds'
import { useStore } from '../state/store'
import { IconBack, IconChevron, IconClose, IconRefresh } from './icons'
import { Sprite } from './Sprite'

const AUTO_REFRESH_MS = 5 * 60_000
const MAX_HISTORY_SHOWN = 14
const DAY_MS = 86_400_000

/** "hoy 09:07", "ayer 21:25", "mañana 09:00", else "15 feb, 09:00". */
export function formatDayTime(millis: number, now: number = Date.now()): string {
  const d = new Date(millis)
  const time = d.toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' })
  const startOf = (t: number) => new Date(new Date(t).getFullYear(), new Date(t).getMonth(), new Date(t).getDate()).getTime()
  const days = Math.round((startOf(millis) - startOf(now)) / DAY_MS)
  if (days === 0) return `hoy ${time}`
  if (days === 1) return `mañana ${time}`
  if (days === -1) return `ayer ${time}`
  return `${d.toLocaleDateString('es-ES', { day: 'numeric', month: 'short' })}, ${time}`
}

/** Refreshes on open and every few minutes while the tab is visible: reports change a few times a day. */
function useAutoRefresh(refresh: () => void) {
  useEffect(() => {
    refresh()
    const id = setInterval(() => {
      if (!document.hidden) refresh()
    }, AUTO_REFRESH_MS)
    return () => clearInterval(id)
  }, [refresh])
}

function Scaffold({
  title,
  refreshing,
  onRefresh,
  onBack,
  children,
}: {
  title: string
  refreshing: boolean
  onRefresh: () => void
  onBack: () => void
  children: React.ReactNode
}) {
  return (
    <div className="feeds">
      <header className="topbar">
        <button type="button" className="icon-btn" onClick={onBack} aria-label="Volver">
          <IconBack />
        </button>
        <h1 className="topbar-title">{title}</h1>
        <button type="button" className="icon-btn" onClick={onRefresh} aria-label="Actualizar" title="Actualizar">
          <IconRefresh className={refreshing ? 'spin' : ''} />
        </button>
      </header>
      <div className="feeds-scroll">
        <div className="feeds-col">{children}</div>
      </div>
    </div>
  )
}

function ErrorBanner({ text, onDismiss }: { text: string; onDismiss?: () => void }) {
  return (
    <div className="feed-error" role="alert">
      <span>{text}</span>
      {onDismiss && (
        <button type="button" className="icon-btn small" onClick={onDismiss} aria-label="Cerrar">
          <IconClose size={16} />
        </button>
      )}
    </div>
  )
}

function EmptyState({ title, text, sleeping }: { title: string; text: string; sleeping: boolean }) {
  return (
    <div className="empty-list">
      <Sprite mood={sleeping ? 'sleeping' : 'idle'} className="empty-sprite" />
      <strong>{title}</strong>
      <p className="muted">{text}</p>
    </div>
  )
}

function StatusChip({ status }: { status: FeedStatus }) {
  const label = status === 'ok' ? 'OK' : status === 'error' ? 'Fallo' : 'Sin datos'
  return <span className={`status-chip ${status}`}>{label}</span>
}

function runText(env: FeedEnvelope | undefined | null): string {
  return env && env.runAt > 0 ? formatDayTime(env.runAt) : ''
}

// ---------- overview ----------

export function FeedsView({ onBack, onOpen }: { onBack: () => void; onOpen: (tag: string) => void }) {
  const st = useStore(feedRepo.state)
  useAutoRefresh(() => void feedRepo.refreshAll())

  return (
    <Scaffold title="Informes" refreshing={st.refreshing} onRefresh={() => void feedRepo.refreshAll()} onBack={onBack}>
      {st.error && <ErrorBanner text={st.error} onDismiss={() => feedRepo.dismissError()} />}
      {st.loaded && st.feeds.length === 0 && (
        <EmptyState
          title="Aún no hay informes"
          text="Cuando una automatización de Hermes publique su feed, aparecerá aquí."
          sleeping={st.error !== null}
        />
      )}
      {st.feeds.map((f) => (
        <FeedCard key={f.tag} feed={f} latest={st.latest[f.tag]} onClick={() => onOpen(f.tag)} />
      ))}
    </Scaffold>
  )
}

function FeedCard({ feed, latest, onClick }: { feed: FeedInfo; latest: FeedEnvelope | undefined; onClick: () => void }) {
  const series = latest?.view.find((b): b is Extract<FeedBlock, { type: 'series' }> => b.type === 'series')
  const headline = latest?.view.find((b): b is Extract<FeedBlock, { type: 'stat' }> => b.type === 'stat')
  const when = runText(latest)
  const failed = latest?.status === 'error'
  return (
    <button type="button" className="card feed-card" onClick={onClick}>
      <div className="feed-card-head">
        <FeedBadge feed={feed} />
        <div className="feed-card-titles">
          <strong>{feed.title}</strong>
          {when && <span className="muted small">{when}</span>}
        </div>
        {latest && latest.status !== 'ok' && <StatusChip status={latest.status} />}
        <IconChevron className="feed-chevron" />
      </div>
      <div className="feed-card-body">
        <div className="feed-card-text">
          {headline && <div className="feed-headline">{headline.value}</div>}
          <div className={failed ? 'danger' : latest ? '' : 'muted'}>
            {!latest
              ? 'Sin informe todavía'
              : failed
                ? `La última ejecución falló${latest.error ? `: ${latest.error}` : ''}`
                : latest.summary || 'Sin resumen'}
          </div>
        </div>
        {series && series.points.length > 1 && <LineChart values={series.points.map((p) => p[1])} className="sparkline" />}
      </div>
    </button>
  )
}

function FeedBadge({ feed }: { feed: FeedInfo }) {
  return <div className="feed-badge">{feed.emoji || '📈'}</div>
}

// ---------- detail ----------

export function FeedDetailView({ tag, onBack }: { tag: string; onBack: () => void }) {
  const st = useStore(feedRepo.state)
  useAutoRefresh(() => void feedRepo.refresh(tag))

  const latest = st.latest[tag]
  const feed: FeedInfo | undefined =
    st.feeds.find((f) => f.tag === tag) ?? (latest ? { tag, title: tag[0].toUpperCase() + tag.slice(1), emoji: '', jobId: latest.jobId } : undefined)
  const history = useMemo(
    () =>
      (st.history[tag] ?? [])
        .filter((e) => !latest || e.runAt !== latest.runAt)
        .slice(0, MAX_HISTORY_SHOWN),
    [st.history, tag, latest],
  )

  return (
    <Scaffold title={feed?.title ?? 'Informe'} refreshing={st.refreshing} onRefresh={() => void feedRepo.refresh(tag)} onBack={onBack}>
      {st.error && <ErrorBanner text={st.error} onDismiss={() => feedRepo.dismissError()} />}
      {!st.loaded && !latest ? null : !feed ? (
        <EmptyState title="Este informe ya no existe" text="Puede que la automatización se haya borrado en Hermes." sleeping={st.error !== null} />
      ) : !latest ? (
        <>
          <FeedHeader feed={feed} latest={undefined} />
          <EmptyState title="Sin informe todavía" text={`Aparecerá tras la próxima ejecución de «${feed.title}».`} sleeping={st.error !== null} />
        </>
      ) : (
        <>
          <FeedHeader feed={feed} latest={latest} />
          {latest.status === 'error' && <ErrorBanner text={`La última ejecución falló${latest.error ? `: ${latest.error}` : ''}`} />}
          <Blocks blocks={latest.view} />
          {history.length > 0 && (
            <>
              <h2 className="feed-section">Ejecuciones anteriores</h2>
              {history.map((h) => (
                <HistoryRow key={h.runAt} env={h} />
              ))}
            </>
          )}
        </>
      )}
    </Scaffold>
  )
}

function FeedHeader({ feed, latest }: { feed: FeedInfo; latest: FeedEnvelope | undefined }) {
  const when = runText(latest)
  return (
    <div className="card feed-header">
      <div className="feed-header-title">
        <strong>{[feed.emoji, feed.title].filter(Boolean).join(' ')}</strong>
        {latest && <StatusChip status={latest.status} />}
      </div>
      {latest?.summary && <p>{latest.summary}</p>}
      {when && <span className="muted small">Última: {when}</span>}
    </div>
  )
}

function HistoryRow({ env }: { env: FeedEnvelope }) {
  const [open, setOpen] = useState(false)
  const text = env.summary || env.error || ''
  return (
    <div className="card history-row">
      <button type="button" className="history-head" onClick={() => setOpen(!open)} aria-expanded={open}>
        <span className="muted small">{runText(env)}</span>
        {env.status !== 'ok' && <StatusChip status={env.status} />}
      </button>
      {text && <div className={open ? '' : 'clamp-2'}>{text}</div>}
      {open && <Blocks blocks={env.view} />}
    </div>
  )
}

// ---------- blocks ----------

/** Consecutive stats share a row (two per row); every other block is a full-width card. */
export function groupBlocks(blocks: FeedBlock[]): FeedBlock[][] {
  const out: FeedBlock[][] = []
  let stats: FeedBlock[] = []
  const flush = () => {
    for (let i = 0; i < stats.length; i += 2) out.push(stats.slice(i, i + 2))
    stats = []
  }
  for (const b of blocks) {
    if (b.type === 'stat') stats.push(b)
    else {
      flush()
      out.push([b])
    }
  }
  flush()
  return out
}

function Blocks({ blocks }: { blocks: FeedBlock[] }) {
  return (
    <>
      {groupBlocks(blocks).map((g, i) => (
        <Fragment key={i}>
          {g[0].type === 'stat' ? (
            <div className="stat-row">
              {g.map((s, j) => s.type === 'stat' && <StatCard key={j} stat={s} />)}
            </div>
          ) : (
            <BlockView block={g[0]} />
          )}
        </Fragment>
      ))}
    </>
  )
}

function BlockView({ block }: { block: FeedBlock }) {
  switch (block.type) {
    case 'kv':
      return (
        <div className="card kv-card">
          {block.items.map(([k, v], i) => (
            <div key={i} className="kv-row">
              <span className="muted">{k}</span>
              <strong>{v}</strong>
            </div>
          ))}
        </div>
      )
    case 'table':
      return (
        <div className="card table-card">
          <table>
            {block.columns.length > 0 && (
              <thead>
                <tr>
                  {block.columns.map((c, i) => (
                    <th key={i}>{c}</th>
                  ))}
                </tr>
              </thead>
            )}
            <tbody>
              {block.rows.map((r, i) => (
                <tr key={i}>
                  {r.map((c, j) => (
                    <td key={j}>{c}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )
    case 'series':
      return <SeriesCard block={block} />
    case 'text':
      return <div className="card feed-text">{block.text}</div>
    case 'link':
      return (
        <a className="card feed-link" href={block.url} target="_blank" rel="noopener noreferrer">
          {block.label} ↗
        </a>
      )
    case 'stat':
      return null
  }
}

function StatCard({ stat }: { stat: Extract<FeedBlock, { type: 'stat' }> }) {
  return (
    <div className="card stat-card">
      <span className="muted small">{stat.label}</span>
      <strong className="stat-value">{stat.value}</strong>
      {stat.delta && <span className="delta-chip">{stat.delta}</span>}
    </div>
  )
}

/** Small magnitudes (€/kWh…) keep three decimals so 0,085 does not read as 0,09. */
function formatNumber(v: number): string {
  const abs = Math.abs(v)
  const digits = abs >= 100 ? 0 : abs >= 1 ? 2 : 3
  return v.toLocaleString('es-ES', { maximumFractionDigits: digits })
}

function SeriesCard({ block }: { block: Extract<FeedBlock, { type: 'series' }> }) {
  const values = block.points.map((p) => p[1])
  const min = Math.min(...values)
  const max = Math.max(...values)
  const unit = block.unit ? ` ${block.unit}` : ''
  const first = block.points[0][0]
  const last = block.points[block.points.length - 1][0]
  return (
    <div className="card series-card">
      {block.label && <strong>{block.label}</strong>}
      <LineChart values={values} className="series-chart" />
      <div className="series-axis muted small">
        <span>{first}</span>
        <span>{last}</span>
      </div>
      <div className="series-range small">
        <span className="muted">mín</span> {formatNumber(min)}
        {unit} · <span className="muted">máx</span> {formatNumber(max)}
        {unit} · <span className="muted">último</span> {formatNumber(values[values.length - 1])}
        {unit}
      </div>
    </div>
  )
}

/** Plain SVG line with an area fill; stretches to its container. */
function LineChart({ values, className }: { values: number[]; className?: string }) {
  const W = 100
  const H = 40
  const pad = 3
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const x = (i: number) => (values.length === 1 ? W / 2 : (i / (values.length - 1)) * W)
  const y = (v: number) => H - pad - ((v - min) / span) * (H - pad * 2)
  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(2)} ${y(v).toFixed(2)}`).join(' ')
  const area = `${line} L${x(values.length - 1).toFixed(2)} ${H} L${x(0).toFixed(2)} ${H} Z`
  return (
    <svg className={className} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
      <path d={area} className="chart-area" />
      <path d={line} className="chart-line" vectorEffect="non-scaling-stroke" />
    </svg>
  )
}
