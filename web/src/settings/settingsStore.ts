// Connection settings. The API key is stored in localStorage when "remember on this device" is on,
// otherwise in sessionStorage (gone when the tab closes). It never goes in a URL, the build or logs.
import { Store } from '../state/store'
import { normalizeBaseUrl } from './hostRules'

export type Settings = {
  baseUrl: string
  apiKey: string
  /** Remember the key (and the cached chats) on this device. */
  remember: boolean
  /** Advanced: stream through /v1/chat/completions instead of runs / session chat. */
  useChatCompletions: boolean
}

const K_URL = 'yumemi.baseUrl'
const K_KEY = 'yumemi.apiKey'
const K_REMEMBER = 'yumemi.remember'
const K_CC = 'yumemi.useChatCompletions'
export const PREFIX = 'yumemi.'

type Kind = 'local' | 'session'

function backend(kind: Kind): Storage | null {
  try {
    return kind === 'local' ? window.localStorage : window.sessionStorage
  } catch {
    return null
  }
}

export function storageGet(kind: Kind, key: string): string | null {
  try {
    return backend(kind)?.getItem(key) ?? null
  } catch {
    return null
  }
}

export function storageSet(kind: Kind, key: string, value: string): boolean {
  try {
    backend(kind)?.setItem(key, value)
    return backend(kind) !== null
  } catch {
    return false
  }
}

export function storageRemove(kind: Kind, key: string): void {
  try {
    backend(kind)?.removeItem(key)
  } catch {
    /* ignore */
  }
}

export function storageKeys(kind: Kind, prefix: string): string[] {
  try {
    const b = backend(kind)
    if (!b) return []
    const out: string[] = []
    for (let i = 0; i < b.length; i++) {
      const k = b.key(i)
      if (k && k.startsWith(prefix)) out.push(k)
    }
    return out
  } catch {
    return []
  }
}

function load(): Settings {
  const remember = storageGet('local', K_REMEMBER) !== 'false'
  const key = (remember ? storageGet('local', K_KEY) : storageGet('session', K_KEY)) ?? ''
  return {
    baseUrl: storageGet('local', K_URL) ?? '',
    apiKey: key,
    remember,
    useChatCompletions: storageGet('local', K_CC) === 'true',
  }
}

export const settings = new Store<Settings>(load())

/** Where chat caches live: with the key, so "do not remember" leaves nothing behind. */
export function cacheKind(): Kind {
  return settings.get().remember ? 'local' : 'session'
}

export function isConfigured(s: Settings = settings.get()): boolean {
  return s.baseUrl.trim() !== ''
}

export function saveSettings(next: Settings): void {
  const prev = settings.get()
  const baseUrl = normalizeBaseUrl(next.baseUrl, { pageIsHttps: typeof location !== 'undefined' && location.protocol === 'https:' })
  const clean: Settings = { ...next, baseUrl, apiKey: next.apiKey.trim() }
  storageSet('local', K_URL, clean.baseUrl)
  storageSet('local', K_REMEMBER, String(clean.remember))
  storageSet('local', K_CC, String(clean.useChatCompletions))
  if (clean.remember) {
    storageSet('local', K_KEY, clean.apiKey)
    storageRemove('session', K_KEY)
  } else {
    storageSet('session', K_KEY, clean.apiKey)
    storageRemove('local', K_KEY)
    // Cached chats move out of localStorage together with the key.
    if (prev.remember) clearCaches('local')
  }
  settings.set(clean)
}

function clearCaches(kind: Kind): void {
  for (const k of storageKeys(kind, PREFIX)) {
    if (k === K_URL || k === K_REMEMBER || k === K_CC || k === K_KEY || k === 'yumemi.pins' || k === 'yumemi.theme') continue
    storageRemove(kind, k)
  }
}

/** Forgets the key, the address and every cached chat on this device. */
export function forgetEverything(): void {
  for (const kind of ['local', 'session'] as const) {
    for (const k of storageKeys(kind, PREFIX)) storageRemove(kind, k)
  }
  settings.set({ baseUrl: '', apiKey: '', remember: true, useChatCompletions: false })
}
