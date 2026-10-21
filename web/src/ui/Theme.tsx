import { useSyncExternalStore } from 'react'
import { storageGet, storageSet } from '../settings/settingsStore'
import { Store } from '../state/store'
import { IconMoon, IconSun } from './icons'

export type ThemeMode = 'auto' | 'light' | 'dark'
const K = 'yumemi.theme'

function load(): ThemeMode {
  const v = storageGet('local', K)
  return v === 'light' || v === 'dark' ? v : 'auto'
}

const mode = new Store<ThemeMode>(load())

export function applyTheme(m: ThemeMode = mode.get()): void {
  const root = document.documentElement
  if (m === 'auto') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', m)
}

const media = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null

function subscribeMedia(cb: () => void) {
  media?.addEventListener('change', cb)
  return () => media?.removeEventListener('change', cb)
}

function effectiveDark(m: ThemeMode): boolean {
  return m === 'dark' || (m === 'auto' && !!media?.matches)
}

export function ThemeButton() {
  const m = useSyncExternalStore(mode.subscribe, mode.get)
  const systemDark = useSyncExternalStore(subscribeMedia, () => !!media?.matches)
  const dark = m === 'dark' || (m === 'auto' && systemDark)
  return (
    <button
      type="button"
      className="icon-btn"
      aria-label={dark ? 'Tema claro' : 'Tema oscuro'}
      title={dark ? 'Tema claro' : 'Tema oscuro'}
      onClick={() => {
        const next: ThemeMode = effectiveDark(mode.get()) ? 'light' : 'dark'
        mode.set(next)
        storageSet('local', K, next)
        applyTheme(next)
      }}
    >
      {dark ? <IconSun /> : <IconMoon />}
    </button>
  )
}
