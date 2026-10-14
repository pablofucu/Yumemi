# Conectar la app con Hermes en Google Cloud

La app solo necesita dos cosas: una **URL** donde responda el API server de Hermes y su **clave**.

## 1. Activar el API server de Hermes (en la VM)

En `~/.hermes/.env` (o en `config.yaml` bajo `gateway.api_server`):

```bash
API_SERVER_ENABLED=true
API_SERVER_KEY=pon-aqui-una-clave-larga-y-aleatoria   # openssl rand -hex 32
API_SERVER_PORT=8642
API_SERVER_HOST=127.0.0.1
```

Reinicia el gateway (`hermes gateway`) y comprueba en la VM:

```bash
curl http://127.0.0.1:8642/health
curl -H "Authorization: Bearer $API_SERVER_KEY" http://127.0.0.1:8642/v1/capabilities
```

> La clave da acceso total al agente (incluida la terminal). No abras el puerto 8642 a internet en el firewall de Google Cloud.

## 2. Elegir el túnel

### Opción A (recomendada): Tailscale
Privado, cifrado y sin abrir puertos.

1. En la VM: `curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up`
2. Para que Hermes escuche en la red de Tailscale, cambia `API_SERVER_HOST=0.0.0.0` (el firewall de GCP sigue bloqueando el puerto desde internet) o usa `sudo tailscale serve --bg 8642` para publicarlo solo dentro de tu tailnet con HTTPS.
3. Instala Tailscale en el móvil con la misma cuenta.
4. En la app: URL `https://<nombre-vm>.<tu-tailnet>.ts.net` (con `tailscale serve`) o `http://100.x.y.z:8642`.

### Opción B: Cloudflare Tunnel
Útil si quieres usarlo sin VPN en el móvil. Necesitas un dominio en Cloudflare.

```bash
cloudflared tunnel login
cloudflared tunnel create hermes
cloudflared tunnel route dns hermes hermes.tu-dominio.com
cloudflared tunnel run --url http://127.0.0.1:8642 hermes
```

En la app: URL `https://hermes.tu-dominio.com`. Recomendable añadir Cloudflare Access delante.

## 3. En la app
Pantalla **Conexión**: pega la URL y la clave, pulsa **Probar conexión** y luego **Guardar**.
