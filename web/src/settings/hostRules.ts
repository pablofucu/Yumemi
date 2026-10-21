// Pure helpers about the Hermes base URL (port of HostRules.kt).

/** Extracts the lowercase host of a URL (with or without scheme). Empty if none. */
export function hostOf(url: string): string {
  let s = url.trim()
  const schemeIdx = s.indexOf('://')
  if (schemeIdx >= 0) s = s.slice(schemeIdx + 3)
  s = s.split('/')[0].split('?')[0].split('#')[0]
  const at = s.lastIndexOf('@')
  if (at >= 0) s = s.slice(at + 1)
  if (s.startsWith('[')) {
    const inner = s.slice(1)
    const end = inner.indexOf(']')
    return (end >= 0 ? inner.slice(0, end) : inner).toLowerCase()
  }
  return s.split(':')[0].toLowerCase()
}

function ipv4(host: string): number[] | null {
  const parts = host.split('.')
  if (parts.length !== 4) return null
  const nums: number[] = []
  for (const p of parts) {
    if (!/^\d+$/.test(p)) return null
    const n = Number(p)
    if (n < 0 || n > 255) return null
    nums.push(n)
  }
  return nums
}

/** True for localhost, Tailscale (100.64.0.0/10, *.ts.net) and RFC1918 private ranges. */
export function isPrivateOrTailscaleHost(host: string): boolean {
  const h = host.toLowerCase()
  if (h === '') return false
  if (h === 'localhost' || h === '::1' || h.endsWith('.localhost')) return true
  if (h.endsWith('.ts.net')) return true
  const ip = ipv4(h)
  if (!ip) return false
  if (ip[0] === 127) return true
  if (ip[0] === 10) return true
  if (ip[0] === 192 && ip[1] === 168) return true
  if (ip[0] === 172 && ip[1] >= 16 && ip[1] <= 31) return true
  if (ip[0] === 100 && ip[1] >= 64 && ip[1] <= 127) return true
  return false
}

/** localhost and loopback: browsers treat these as secure even over http://. */
export function isLoopbackHost(host: string): boolean {
  const h = host.toLowerCase()
  if (h === 'localhost' || h === '::1' || h.endsWith('.localhost')) return true
  const ip = ipv4(h)
  return !!ip && ip[0] === 127
}

/**
 * Trims, removes trailing slashes and adds a scheme when missing. Without a scheme, private and
 * Tailscale hosts get http:// (like the Android app) unless [pageIsHttps] is set: an https page
 * cannot call http:// (mixed content), and `tailscale serve` exposes https://*.ts.net.
 */
export function normalizeBaseUrl(input: string, opts: { pageIsHttps?: boolean } = {}): string {
  let s = input.trim().replace(/\/+$/, '')
  if (s === '') return ''
  if (!s.includes('://')) {
    const host = hostOf(s)
    const plainOk = isPrivateOrTailscaleHost(host) && !(opts.pageIsHttps && !isLoopbackHost(host))
    s = `${plainOk ? 'http' : 'https'}://${s}`
  }
  return s
}

/** True when the URL is plain http:// and the host is not on a private/Tailscale network. */
export function isInsecure(input: string): boolean {
  const url = normalizeBaseUrl(input)
  if (!url.toLowerCase().startsWith('http://')) return false
  return !isPrivateOrTailscaleHost(hostOf(url))
}

/**
 * True when a page served over [pageProtocol] ("https:") cannot call [input] because it is
 * plain http:// (mixed content). Loopback hosts are exempt: browsers allow them.
 */
export function isMixedContent(input: string, pageProtocol: string): boolean {
  if (pageProtocol !== 'https:') return false
  const url = normalizeBaseUrl(input)
  if (!url.toLowerCase().startsWith('http://')) return false
  return !isLoopbackHost(hostOf(url))
}
