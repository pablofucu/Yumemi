// Errors with Spanish, user-facing messages (port of HermesException.kt).
import type { ConnectionState } from '../domain'

const GENERIC_UNREACHABLE = 'No puedo conectar con Hermes'

export type NetworkContext = {
  /** Base URL being called, to tell mixed-content from CORS problems. */
  baseUrl?: string
  /** Protocol of the page, e.g. "https:". */
  pageProtocol?: string
}

export type HermesErrorKind =
  | 'not_configured'
  | 'unauthorized'
  | 'unreachable'
  | 'http'
  | 'protocol'
  | 'runs_unsupported'

export class HermesError extends Error {
  readonly kind: HermesErrorKind
  readonly code?: number
  readonly detail?: string | null
  readonly timeout: boolean

  constructor(
    kind: HermesErrorKind,
    message: string,
    opts: { code?: number; detail?: string | null; cause?: unknown; timeout?: boolean } = {},
  ) {
    super(message, opts.cause !== undefined ? { cause: opts.cause } : undefined)
    this.name = 'HermesError'
    this.kind = kind
    this.code = opts.code
    this.detail = opts.detail
    this.timeout = opts.timeout ?? false
  }

  static notConfigured() {
    return new HermesError('not_configured', 'Configura la dirección de Hermes en Conexión')
  }
  static unauthorized() {
    return new HermesError('unauthorized', 'Clave de API incorrecta')
  }
  static unreachable(cause?: unknown, ctx: NetworkContext = {}, timeout = false) {
    return new HermesError('unreachable', networkMessage(cause, ctx, timeout), { cause, timeout })
  }
  static http(code: number, detail?: string | null) {
    return new HermesError('http', `Hermes respondió con un error (${code})`, { code, detail })
  }
  static protocol(detail: string) {
    return new HermesError('protocol', `Respuesta inesperada de Hermes: ${detail}`)
  }
  static runsUnsupported() {
    return new HermesError('runs_unsupported', 'Este Hermes no tiene la API de ejecuciones')
  }
}

export function isAbort(e: unknown): boolean {
  return (e instanceof DOMException || e instanceof Error) && e.name === 'AbortError'
}

function isLocalHost(url: string | undefined): boolean {
  if (!url) return false
  try {
    const h = new URL(url).hostname.toLowerCase()
    return h === 'localhost' || h === '127.0.0.1' || h === '[::1]' || h === '::1' || h.endsWith('.localhost')
  } catch {
    return false
  }
}

export function pageProtocol(): string | undefined {
  return typeof location !== 'undefined' ? location.protocol : undefined
}

/**
 * Specific Spanish explanation for a network failure. In browsers `fetch` rejects with an opaque
 * `TypeError: Failed to fetch` for CORS, mixed content, certificate and DNS problems alike, so the
 * message lists the usual suspects instead of pretending to know.
 */
export function networkMessage(cause: unknown, ctx: NetworkContext = {}, timeout = false): string {
  if (timeout) return 'Hermes tarda demasiado en responder. Revisa la conexión (y Tailscale, si lo usas).'
  const looksLikeFetchFailure =
    cause instanceof TypeError ||
    (cause instanceof Error && /failed to fetch|networkerror|load failed/i.test(cause.message))
  if (looksLikeFetchFailure) {
    const mixed =
      ctx.pageProtocol === 'https:' &&
      !!ctx.baseUrl &&
      /^http:\/\//i.test(ctx.baseUrl) &&
      !isLocalHost(ctx.baseUrl)
    if (mixed) {
      return 'El navegador bloquea llamar a una dirección http:// desde una página https:// (contenido mixto). Usa una URL https:// para Hermes: Tailscale Serve (https://<equipo>.<tailnet>.ts.net) o Cloudflare Tunnel.'
    }
    return 'No puedo llegar a Hermes. Casi siempre es una de estas: el servidor no tiene este sitio en API_SERVER_CORS_ORIGINS (CORS), el certificado https no es válido, la dirección es incorrecta o falta Tailscale/VPN en este ordenador.'
  }
  return GENERIC_UNREACHABLE
}

export function toUserMessage(e: unknown): string {
  if (e instanceof HermesError) return e.message
  if (e instanceof TypeError) return networkMessage(e, { pageProtocol: pageProtocol() })
  if (e instanceof Error) return `Algo salió mal: ${e.message || e.name}`
  return 'Algo salió mal'
}

/** Null when the failure says nothing about connectivity. */
export function toConnectionState(e: unknown): ConnectionState | null {
  if (e instanceof HermesError) {
    switch (e.kind) {
      case 'not_configured':
        return { kind: 'not_configured' }
      case 'unauthorized':
        return { kind: 'unauthorized' }
      case 'unreachable':
        return { kind: 'unreachable', message: e.message || GENERIC_UNREACHABLE }
      default:
        return null
    }
  }
  if (e instanceof TypeError) {
    return { kind: 'unreachable', message: networkMessage(e, { pageProtocol: pageProtocol() }) }
  }
  return null
}
