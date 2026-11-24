# servercore backend

bun + fastify + mongoose + grammY backend for servercore. everything the game
plugin does over the websocket gateway lives here, plus the telegram bot.

## run

```sh
bun install
cp .env.example .env   # fill in real values
bun run src/app.ts     # starts http + ws + bot (bot only if BOT_TOKEN set)
```

## docker (northflank / any container host)

```sh
docker build -t servercore-backend .
docker run --rm -p 8080:8080 -p 8081:8081 --env-file .env servercore-backend
```

the image is multi-stage (frozen lockfile install), runs as a non-root user,
exposes 8080 (fastify http + /health) and 8081 (plugin websocket gateway) and
ships a healthcheck on /health. on northflank point the health check at
`/health` on port 8080 and pass the env vars from the platform's secret store.

## mongodb (atlas in production)

`MONGODB_URI` takes the atlas `mongodb+srv://...` form in production — TLS is
implicit for srv URIs and `retryWrites` is enabled by the connection code, so
transient primary elections during atlas maintenance don't fail writes. local
development keeps the plain `mongodb://` form. index creation on boot
(`syncIndexes`) is failure-tolerant: a connection without index-creation
rights logs a warning and the backend keeps serving; create the indexes once
with an admin user instead.

## tests

```sh
bun test        # unit tests, no mongo needed
bun run typecheck
```

## layout

- `src/websocket/` — gateway, connection state, protocol (envelope, hmac, replay)
- `src/modules/` — business-logic modules (`account/` lifecycle + linking + deletion, `notification/` fan-out)
- `src/database/` — mongoose connection + models, one file per collection
- `src/bot/` — grammY bootstrap, routes, conversations, i18n (ru/en), keyboards
- `src/services/` — business logic shared by bot and websocket layers
- `src/config/` — env parsing (zod), `.env.example` holds every knob
- `src/errors/` — typed errors + the fastify error handler
- `src/logging/` — pino setup (timestamps in Europe/Moscow)
- `src/utils/` — crypto, rate limiting, timezone helpers

## websocket protocol in one paragraph

plugin connects to `GET /ws?server=<id>&ts=<ms>&nonce=<n>&sig=<hmac>` and is
authenticated pre-upgrade: the hmac is taken over `server|ts|nonce` with the
per-server secret from `SERVERCORE_SERVER_CREDENTIALS` (the serverId rides on
the connection from that moment). every frame is
`{ id, type, timestamp, correlationId?, payload, hmac }` where `hmac` is
hmac-sha256 over the canonical json of the envelope minus the hmac field.
stale timestamps and replayed ids close the connection. server frames get an
`ACK` back; backend pushes await the plugin's ack. account linking adds three
types: `LINK_TOKEN` (backend → plugin, fresh one-time deep-link token for the
game chat), `SESSION_CHECK` / `SESSION_CHECK_RESULT` (ownership probe for the
telegram linking flow).
