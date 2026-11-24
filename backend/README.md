# servercore backend

bun + fastify + mongoose + grammY backend for servercore. everything the game
plugin does over the websocket gateway lives here, plus the telegram bot.

## run

```sh
bun install
cp .env.example .env   # fill in real values
bun run src/app.ts     # starts http + ws + bot (bot only if BOT_TOKEN set)
```

## tests

```sh
bun test        # unit tests, no mongo needed
bun run typecheck
```

## layout

- `src/websocket/` — gateway, connection state, protocol (envelope, hmac, replay)
- `src/database/` — mongoose connection + models, one file per collection
- `src/bot/` — grammY bootstrap, `commands/` for command handlers
- `src/services/` — business logic shared by bot and websocket layers
- `src/config/` — env parsing (zod), `.env.example` holds every knob
- `src/errors/` — typed errors + the fastify error handler
- `src/logging/` — pino setup
- `src/utils/` — crypto helpers, shared small stuff

## websocket protocol in one paragraph

plugin connects to `GET /ws` and must send a signed `SERVER_HELLO` as its first
frame. every frame is `{ id, type, timestamp, correlationId?, payload, hmac }`
where `hmac` is hmac-sha256 over the canonical json of the envelope minus the
hmac field, keyed with the per-server secret from `SERVERCORE_SERVER_CREDENTIALS`.
stale timestamps and replayed ids close the connection. server frames get an
`ACK` back; backend pushes await the plugin's ack.
