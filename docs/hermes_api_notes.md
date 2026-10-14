# Hermes Agent API server: notes for the app

Source: https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server (read 2026-10-14).

- Enable on the server: `API_SERVER_ENABLED=true`, `API_SERVER_KEY=<secret>`, `API_SERVER_PORT=8642`, `API_SERVER_HOST=127.0.0.1` (default). Then `hermes gateway`.
- Auth: `Authorization: Bearer <API_SERVER_KEY>` on every request. The key is a root credential (the agent has terminal access).
- `GET /health` is public (`{"status":"ok"}`); `GET /v1/capabilities` is authenticated, returns `features` map (use it to check support). Good for "test connection": health for reachability, capabilities for auth (401 => Unauthorized).
- SSE streams send `: keepalive` comments after 10 s of silence. Skip lines starting with `:`.

## Sessions API (threads)
| Method | Path | Notes |
|---|---|---|
| GET | `/api/sessions?limit=&offset=&source=&include_children=` | list |
| POST | `/api/sessions` | create empty session (body may include `title`) |
| GET/PATCH/DELETE | `/api/sessions/{id}` | PATCH `{"title": ...}` |
| GET | `/api/sessions/{id}/messages` | transcript; `inline_images=false` |
| POST | `/api/sessions/{id}/fork` | branch |
| POST | `/api/sessions/{id}/chat` | one sync turn, body `{"input": "..."}` |
| POST | `/api/sessions/{id}/chat/stream` | SSE turn, body `{"input": "..."}` |

Chat stream events: `assistant.delta`, `assistant.commentary` (`message_id`, `text`, `already_streamed`), `tool.started` (`tool`, `preview`), `tool.completed` (`tool`, `duration`, `error`, `preview`), `tool.failed`, terminal `run.completed` / `run.failed` / `run.cancelled`.
Exact JSON field names of list/messages responses are not documented: parse defensively (ignoreUnknownKeys, optional fields, try `id`/`session_id`, `title`, `created_at`/`updated_at` as epoch seconds or ISO strings).

## Chat Completions (alternative transport, carries reasoning)
`POST /v1/chat/completions` with `stream: true` and header `X-Hermes-Session-Id: <session id>` for transcript continuity.
- `choices[0].delta.content` = answer text, `choices[0].delta.reasoning_content` = reasoning.
- Named SSE events `event: hermes.tool.progress` = tool start notices.

## Runs API
`POST /v1/runs` `{input, session_id}` -> `{run_id}`; `GET /v1/runs/{id}/events` SSE: `message.delta`, `message.interim`, `tool.started`, `tool.completed` (`tool`, `duration`, `error`, `preview`), `subagent.start`/`subagent.complete`, `approval.request`, `run.completed`. `POST /v1/runs/{id}/stop` to interrupt. `POST /v1/runs/{id}/approval`.

## Stopping a turn
Prefer runs `stop` when using runs; for session streams, cancel the HTTP call.
