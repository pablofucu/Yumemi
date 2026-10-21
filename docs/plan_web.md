# Plan: Yumemi Web (Netlify)

Objetivo: una versión web de Yumemi, desplegada en Netlify, para hablar con Hermes desde cualquier ordenador con el mismo comportamiento que la app Android (hilos, streaming, herramientas, aprobaciones, sprite de Yumemi).

## Arquitectura

- **SPA estática** en `web/` (Vite + React + TypeScript). Sin backend propio.
- El navegador habla **directamente** con el API server de Hermes, igual que la app. Nada de proxy en Netlify Functions: no llegan a la tailnet, cortan respuestas largas y la clave pasaría por un tercero.
- Requisitos del servidor Hermes (documentar en `docs/conectar_web.md`):
  - URL **HTTPS** (una página https no puede llamar a `http://100.x.y.z:8642` por contenido mixto): `tailscale serve` (`https://<vm>.<tailnet>.ts.net`) o Cloudflare Tunnel.
  - `API_SERVER_CORS_ORIGINS=https://<sitio>.netlify.app` (y `http://localhost:5173` para desarrollo). Reiniciar `hermes gateway`.
  - Con Tailscale, el ordenador debe tener Tailscale; para un ordenador ajeno, Cloudflare Tunnel + Cloudflare Access.
- **Clave**: se pide en la pantalla Conexión y se guarda en `localStorage` (opción "recordar en este equipo"; si no, `sessionStorage`). Nunca en la URL ni en el build.
- **Streaming**: `EventSource` no admite cabecera `Authorization`, así que SSE se lee con `fetch` + `ReadableStream` y un parser propio (ignorar líneas `:` keepalive, soportar `event:`, `id:`, `data:` multilínea, `Last-Event-ID` para reanudar).

## Paridad con la app (fuente de verdad: el código Kotlin)

Portar la lógica pura a TS, no reinventarla:

| Kotlin | TS en `web/src/` |
|---|---|
| `data/remote/HermesClient.kt` (endpoints, `RENDER_HINT`, reintentos, `testConnection`) | `api/hermes.ts` |
| `data/remote/Sse.kt` | `api/sse.ts` |
| `data/chat/StreamEvent.kt` | `chat/streamEvent.ts` |
| `data/chat/TurnAccumulator.kt` | `chat/turnAccumulator.ts` |
| `data/chat/TranscriptParser.kt`, `TranscriptMerger.kt`, `PartCodec.kt` | `chat/transcript.ts` |
| `data/chat/ThreadActivityRules.kt` | `chat/activity.ts` |
| `data/chat/TurnRunner.kt` (runs API primero, fallback a `/api/sessions/{id}/chat/stream`, stop, aprobaciones, reanudar run pendiente) | `chat/turnRunner.ts` |
| `data/settings/HostRules.kt` | `settings/hostRules.ts` (avisar si la URL es `http://` en página https) |

El `RENDER_HINT` debe decir "web app" en vez de "Android chat app".

## Pantallas (alcance v1)

1. **Conexión**: URL + clave, Probar conexión (health + capabilities), Guardar. Mensajes de error claros para CORS/contenido mixto (un `TypeError: Failed to fetch` casi siempre es CORS o certificado: explicarlo).
2. **Lista de hilos** (barra lateral en escritorio, pantalla propia en móvil): crear, renombrar, borrar, secciones por fecha, último mensaje, favoritas (favoritas en `localStorage`).
3. **Chat**: Markdown GFM (`react-markdown` + `remark-gfm` + resaltado de código), streaming, razonamiento y herramientas plegables (como `StatusPanel`/`MessageViews`), aprobaciones (once/session/always/deny), botón parar, copiar mensaje. Enter envía, Shift+Enter salto de línea.
4. **Sprite de Yumemi** que cambia de expresión según el estado (pensando, herramienta, hablando, error), reutilizando las capas de `app/src/main/res/drawable-nodpi/yu_*.webp` (copiarlas a `web/public/sprites/`) y la lógica de `ui/sprite/YumemiSprite.kt`.
5. Tema kawaii con los colores de `ui/theme/Color.kt`, claro/oscuro.

Fuera de v1: cron, feeds, notificaciones push (se pueden añadir luego).

## Despliegue

- `netlify.toml` en la raíz: `base = "web"`, `command = "npm run build"`, `publish = "dist"`, redirect SPA `/* -> /index.html 200`, cabeceras de seguridad (CSP con `connect-src https:`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`).
- Workflow opcional `.github/workflows/web.yml`: `npm ci && npm run lint && npm test && npm run build` en `web/`.
- Netlify: conectar el repo de GitHub (o `netlify deploy --prod` desde `web/`). Luego añadir el dominio de Netlify a `API_SERVER_CORS_ORIGINS`.

## Pruebas

- Vitest para el parser SSE, `turnAccumulator`, `transcript` y `hostRules`, reutilizando muestras de `app/src/test` cuando existan.
- `npm run build` sin errores de TypeScript.
- Prueba manual con `npm run dev` contra el servidor Hermes real.
