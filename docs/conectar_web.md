# Conectar Yumemi Web con Hermes

Yumemi Web (carpeta `web/`) es una página estática (Vite + React + TypeScript). No tiene servidor propio: el navegador habla **directamente** con el API server de Hermes, igual que la app Android. Por eso hay tres requisitos en el servidor: URL HTTPS, CORS y la clave.

## 1. API server de Hermes

Igual que para la app (ver [conectar_hermes.md](conectar_hermes.md)): `API_SERVER_ENABLED=true`, `API_SERVER_KEY=<clave larga>`, `API_SERVER_PORT=8642`, `API_SERVER_HOST=127.0.0.1`, y `hermes gateway`.

## 2. URL HTTPS (obligatoria)

Una página `https://` no puede llamar a `http://100.x.y.z:8642` (contenido mixto: el navegador lo bloquea sin mensaje útil). Hermes tiene que estar publicado con HTTPS:

- **Tailscale Serve** (privado, solo equipos de tu tailnet):
  ```bash
  sudo tailscale serve --bg --https=443 http://127.0.0.1:8642
  ```
  La URL es `https://<vm>.<tailnet>.ts.net`. El ordenador desde el que abres Yumemi Web necesita Tailscale conectado.
- **Cloudflare Tunnel** (para un ordenador sin Tailscale): `cloudflared tunnel` hacia `http://127.0.0.1:8642` y protege el hostname con **Cloudflare Access** (la clave de Hermes da acceso a la terminal del agente; no la dejes detrás de una URL pública sin más).
- `http://localhost:8642` sí funciona desde una página https (los navegadores lo tratan como seguro) y desde `npm run dev`.

## 3. CORS

El servidor debe aceptar el origen de la web. En `~/.hermes/.env`:

```bash
API_SERVER_CORS_ORIGINS=https://<tu-sitio>.netlify.app,http://localhost:5173
```

Reinicia `hermes gateway`. Si usas un dominio propio, añádelo también. El origen es exacto: esquema + host (+ puerto), sin barra final.

La web envía estas cabeceras, así que Hermes tiene que permitirlas en la respuesta de preflight (`OPTIONS`): `Authorization`, `Content-Type`, `Idempotency-Key`, `Last-Event-ID`, `X-Hermes-Session-Id`. Si tu versión de Hermes limita las cabeceras permitidas y el chat falla solo al enviar mensajes, es lo primero que hay que revisar.

Para comprobarlo desde un terminal:

```bash
curl -i -X OPTIONS https://<vm>.<tailnet>.ts.net/v1/runs \
  -H "Origin: https://<tu-sitio>.netlify.app" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: authorization,content-type,idempotency-key"
```

Debe devolver `Access-Control-Allow-Origin: https://<tu-sitio>.netlify.app` y las cabeceras en `Access-Control-Allow-Headers`.

## 4. Desplegar en Netlify

`netlify.toml` (en la raíz del repo) ya define todo: `base = "web"`, `npm run build`, `publish = "dist"`, redirección SPA y cabeceras de seguridad (CSP, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`).

1. Netlify → **Add new site → Import an existing project** → elige el repositorio de GitHub. Netlify lee `netlify.toml`; no hay que cambiar nada (Node 22).
2. No hace falta ninguna variable de entorno: la URL y la clave se escriben en la pantalla **Conexión** y nunca entran en el build.
3. Copia la URL de Netlify (`https://<sitio>.netlify.app`) y añádela a `API_SERVER_CORS_ORIGINS` (paso 3); reinicia el gateway.

Alternativa sin Git: `npm run build` dentro de `web/` y subir la carpeta `web/dist` a Netlify (o `netlify deploy --prod` desde `web/`).

## 5. Usarla

1. Abre la web → **Conexión** → pega la URL HTTPS de Hermes y la clave → **Probar conexión** → **Guardar**.
2. «Recordar en este equipo» guarda la clave (y las conversaciones en caché) en `localStorage`. Desmarcado, la clave va a `sessionStorage` y desaparece al cerrar la pestaña. En un ordenador ajeno, desmárcalo o usa **Olvidar este equipo** al terminar.
3. La clave solo viaja en la cabecera `Authorization`; nunca en la URL, el build ni los registros.

## Errores típicos

| Mensaje | Causa |
|---|---|
| «No puedo llegar a Hermes… CORS» | El navegador solo ve `TypeError: Failed to fetch`. Casi siempre: el sitio no está en `API_SERVER_CORS_ORIGINS`, el certificado no es válido, la URL es incorrecta o falta Tailscale en este ordenador. |
| «contenido mixto» | La URL empieza por `http://` y la página es https. Usa Tailscale Serve o Cloudflare Tunnel. |
| «Clave de API incorrecta» | `API_SERVER_KEY` no coincide (401/403). |
| El chat funciona pero una respuesta larga se corta | Un proxy intermedio corta el SSE; las ejecuciones (`/v1/runs`) se reconectan solas y retoman donde se quedaron. |

## Desarrollo

```bash
cd web
npm install
npm run dev        # http://localhost:5173 (añádelo a API_SERVER_CORS_ORIGINS)
npm test           # Vitest: SSE, acumulador de turnos, transcripción, reglas de host
npm run lint
npm run build
```

## Qué incluye y qué no

Portado de la app Android: conexión (health + capabilities), hilos (crear, renombrar, borrar, secciones por fecha, último mensaje, favoritas, actividad en vivo también de otros clientes), chat con streaming por Runs API (con reconexión y reanudación tras recargar la página) y respaldo al stream de sesión, razonamiento y acciones plegables, aprobaciones (una vez / en este chat / siempre / denegar), botón parar, copiar, Markdown GFM con resaltado de código y el sprite de Yumemi con sus expresiones.

Fuera de esta versión: tareas programadas (cron), feeds y notificaciones.
