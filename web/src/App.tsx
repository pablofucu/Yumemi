import { useEffect, useSyncExternalStore } from 'react'
import { client } from './services'
import { threadList } from './state/threadList'
import { isConfigured, settings } from './settings/settingsStore'
import { useStore } from './state/store'
import { ChatView } from './ui/ChatView'
import { ConnectionView } from './ui/ConnectionView'
import { Sprite } from './ui/Sprite'
import { ThreadList } from './ui/ThreadList'

type Route = { name: 'list' } | { name: 'chat'; id: string } | { name: 'connection' }

function parseHash(hash: string): Route {
  const h = hash.replace(/^#\/?/, '')
  if (h === 'connection') return { name: 'connection' }
  const m = /^chat\/(.+)$/.exec(h)
  if (m) {
    try {
      return { name: 'chat', id: decodeURIComponent(m[1]) }
    } catch {
      return { name: 'list' }
    }
  }
  return { name: 'list' }
}

function subscribeHash(cb: () => void) {
  window.addEventListener('hashchange', cb)
  return () => window.removeEventListener('hashchange', cb)
}

const navigate = (hash: string) => {
  window.location.hash = hash
}

export function App() {
  const hash = useSyncExternalStore(subscribeHash, () => window.location.hash)
  const s = useStore(settings)
  const configured = isConfigured(s)
  // Without a server address there is nothing to show but the connection screen.
  const route: Route = configured ? parseHash(hash) : { name: 'connection' }

  useEffect(() => {
    threadList.configChanged()
    void client.prewarm()
  }, [])

  // The tab title shows a dot when an answer finished in the background.
  useEffect(() => {
    const reset = () => {
      if (!document.hidden) document.title = 'Yumemi'
    }
    document.addEventListener('visibilitychange', reset)
    return () => document.removeEventListener('visibilitychange', reset)
  }, [])

  const listActive = route.name === 'list'
  return (
    <div className={`app route-${route.name}`}>
      <aside className={`pane-list ${listActive ? 'active' : ''}`}>
        {configured && (
          <ThreadList
            selectedId={route.name === 'chat' ? route.id : null}
            onOpenChat={(id) => navigate(id ? `#/chat/${encodeURIComponent(id)}` : '#/')}
            onOpenSettings={() => navigate('#/connection')}
          />
        )}
      </aside>
      <main className="pane-main">
        {route.name === 'chat' && <ChatView key={route.id} threadId={route.id} onBack={() => navigate('#/')} />}
        {route.name === 'connection' && (
          <ConnectionView
            key={configured ? 'cfg' : 'new'}
            canGoBack={configured}
            onDone={() => navigate('#/')}
          />
        )}
        {route.name === 'list' && (
          <div className="placeholder">
            <Sprite mood="idle" className="placeholder-sprite" />
            <p className="muted">Elige una conversación o crea una nueva.</p>
          </div>
        )}
      </main>
    </div>
  )
}
