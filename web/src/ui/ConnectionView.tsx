// Connection screen (port of ui/settings/SettingsScreen.kt for the web).
import { useState } from 'react'
import { client, repo } from '../services'
import { feedRepo } from '../data/feeds'
import { threadList } from '../state/threadList'
import { pageProtocol } from '../api/errors'
import type { ConnectionState } from '../domain'
import { hostOf, isInsecure, isMixedContent, normalizeBaseUrl } from '../settings/hostRules'
import { forgetEverything, saveSettings, settings } from '../settings/settingsStore'
import { useStore } from '../state/store'
import { ConfirmDialog } from './Dialogs'
import { IconBack } from './icons'
import { Sprite } from './Sprite'

function resultText(c: ConnectionState): { text: string; ok: boolean } | null {
  switch (c.kind) {
    case 'connected':
      return { text: 'Conectado: Hermes responde y la clave es correcta.', ok: true }
    case 'unauthorized':
      return { text: 'Hermes responde, pero la clave de API no es correcta.', ok: false }
    case 'not_configured':
      return { text: 'Escribe la dirección de Hermes.', ok: false }
    case 'unreachable':
      return { text: c.message, ok: false }
    default:
      return null
  }
}

export function ConnectionView({ onDone, canGoBack }: { onDone: () => void; canGoBack: boolean }) {
  const saved = useStore(settings)
  const [baseUrl, setBaseUrl] = useState(saved.baseUrl)
  const [apiKey, setApiKey] = useState(saved.apiKey)
  const [remember, setRemember] = useState(saved.remember)
  const [useCC, setUseCC] = useState(saved.useChatCompletions)
  const [feedsUrl, setFeedsUrl] = useState(saved.feedsUrl)
  const [result, setResult] = useState<ConnectionState>({ kind: 'unknown' })
  const [forgetting, setForgetting] = useState(false)

  const proto = pageProtocol() ?? 'https:'
  const normalized = normalizeBaseUrl(baseUrl, { pageIsHttps: proto === 'https:' })
  const mixed = normalized !== '' && isMixedContent(normalized, proto)
  const insecure = normalized !== '' && !mixed && isInsecure(normalized)
  const host = hostOf(normalized)

  const test = async () => {
    if (normalized === '') {
      setResult({ kind: 'not_configured' })
      return
    }
    setResult({ kind: 'checking' })
    setResult(await client.testConnection(normalized, apiKey.trim()))
  }

  const save = () => {
    saveSettings({ baseUrl, apiKey, remember, useChatCompletions: useCC, feedsUrl })
    repo.reset()
    feedRepo.reset()
    threadList.configChanged()
    void client.prewarm()
    onDone()
  }

  const r = resultText(result)

  return (
    <div className="connection">
      <header className="topbar">
        {canGoBack && (
          <button type="button" className="icon-btn" onClick={onDone} aria-label="Volver">
            <IconBack />
          </button>
        )}
        <h1 className="topbar-title">Conexión</h1>
      </header>
      <div className="connection-scroll">
        <div className="connection-hero">
          <Sprite mood={result.kind === 'connected' ? 'happy' : result.kind === 'unauthorized' || result.kind === 'unreachable' ? 'error' : 'idle'} className="hero-sprite" />
          <p className="muted">Yumemi habla directamente con tu servidor Hermes desde este navegador.</p>
        </div>

        <form
          className="card form"
          onSubmit={(e) => {
            e.preventDefault()
            save()
          }}
          autoComplete="off"
        >
          <label className="label" htmlFor="base-url">
            Dirección de Hermes
          </label>
          <input
            id="base-url"
            className="field"
            type="url"
            inputMode="url"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            placeholder="https://mi-equipo.mi-tailnet.ts.net"
            value={baseUrl}
            onChange={(e) => {
              setBaseUrl(e.target.value)
              setResult({ kind: 'unknown' })
            }}
          />
          {normalized !== '' && normalized !== baseUrl.trim() && <div className="hint">Se usará {normalized}</div>}
          {mixed && (
            <div className="warn" role="alert">
              Esta página es https:// y el navegador no deja llamar a una dirección http:// (contenido mixto). Publica Hermes con
              HTTPS: <code>tailscale serve</code> o Cloudflare Tunnel (mira «Cómo conectar»).
            </div>
          )}
          {insecure && (
            <div className="warn" role="alert">
              {host} no es una red privada ni Tailscale: con http:// tu clave viajaría sin cifrar. Usa https://.
            </div>
          )}

          <label className="label" htmlFor="api-key">
            Clave de API
          </label>
          <input
            id="api-key"
            className="field"
            type="password"
            autoComplete="new-password"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            placeholder="API_SERVER_KEY"
            value={apiKey}
            onChange={(e) => {
              setApiKey(e.target.value)
              setResult({ kind: 'unknown' })
            }}
          />

          <label className="check">
            <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
            <span>
              Recordar en este equipo
              <small>
                {remember
                  ? 'La clave y las conversaciones en caché se guardan en este navegador.'
                  : 'La clave solo dura mientras la pestaña esté abierta.'}
              </small>
            </span>
          </label>

          <details className="advanced">
            <summary>Avanzado</summary>
            <label className="check">
              <input type="checkbox" checked={useCC} onChange={(e) => setUseCC(e.target.checked)} />
              <span>
                Usar chat completions
                <small>Transporte alternativo (/v1/chat/completions). Sin aprobaciones ni parada de ejecución.</small>
              </span>
            </label>
            <label className="label" htmlFor="feeds-url">
              Dirección de los informes
            </label>
            <input
              id="feeds-url"
              className="field"
              type="url"
              inputMode="url"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              placeholder="Vacío = la dirección de Hermes + /feeds"
              value={feedsUrl}
              onChange={(e) => setFeedsUrl(e.target.value)}
            />
          </details>

          {r && (
            <div className={`result ${r.ok ? 'ok' : 'bad'}`} role="status">
              {r.text}
            </div>
          )}
          {result.kind === 'checking' && <div className="result" role="status">Comprobando…</div>}

          <div className="form-actions">
            <button type="button" className="btn tonal" onClick={() => void test()} disabled={result.kind === 'checking'}>
              Probar conexión
            </button>
            <button type="submit" className="btn primary" disabled={normalized === ''}>
              Guardar
            </button>
          </div>
        </form>

        <details className="card help">
          <summary>Cómo conectar</summary>
          <ol>
            <li>
              En el servidor, publica Hermes con HTTPS (por ejemplo <code>tailscale serve --bg 8642</code>) y usa esa URL.
            </li>
            <li>
              Añade este sitio a <code>API_SERVER_CORS_ORIGINS</code> (por ejemplo <code>{location.origin}</code>) y reinicia{' '}
              <code>hermes gateway</code>.
            </li>
            <li>Si falla con «No puedo llegar a Hermes», casi siempre es CORS, el certificado o la VPN.</li>
          </ol>
        </details>

        <div className="danger-zone">
          <button type="button" className="btn text danger-text" onClick={() => setForgetting(true)}>
            Olvidar este equipo
          </button>
          <small className="muted">Borra la dirección, la clave y las conversaciones en caché de este navegador.</small>
        </div>
      </div>

      {forgetting && (
        <ConfirmDialog
          title="Olvidar este equipo"
          message="Se borrarán la dirección, la clave y las conversaciones en caché de este navegador. Las conversaciones siguen en Hermes."
          confirmLabel="Olvidar"
          danger
          onConfirm={() => {
            forgetEverything()
            repo.reset()
            setBaseUrl('')
            setApiKey('')
            setRemember(true)
            setUseCC(false)
            setFeedsUrl('')
            feedRepo.reset()
            setResult({ kind: 'unknown' })
            threadList.configChanged()
          }}
          onClose={() => setForgetting(false)}
        />
      )}
    </div>
  )
}
