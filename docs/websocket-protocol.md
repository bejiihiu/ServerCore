# WebSocket protocol

The WebSocket channel is the only transport between the backend and the Minecraft plugin. There is no HTTP fallback and none is planned. The plugin is always the initiating side; the backend is the gateway.

## Transport and security

- **WSS only.** Connections ride TLS; plaintext upgrades are rejected.
- **Per-server secret.** Each registered Minecraft server has its own HMAC secret, configured identically on both ends.
- **Handshake authentication.** Connection auth happens **before the upgrade**: the plugin appends `?server=<id>&ts=<ms>&nonce=<n>&sig=<hmac>` to the upgrade request, and the backend verifies the HMAC over `server|ts|nonce` before the socket ever exists. A stale timestamp or a replayed nonce is rejected outright. Legacy `SERVER_HELLO` frames after an upgrade are refused.
- **Message signing.** Every frame in both directions carries an HMAC signature computed over the canonical json of the envelope (id, type, timestamp, payload) minus the hmac field.
- **Replay protection.** Timestamps must fall within a tolerance window; per-frame envelope ids are tracked and duplicates are re-acked but never reprocessed.
- **Schema validation.** Every inbound message is validated against its type's schema — Zod on the backend, typed DTOs with kotlinx.serialization on the plugin. A type an old build does not know decodes to a raw passthrough instead of killing the connection. Both sides are additionally tested against the shared field contract in `protocol/messages.json` (see [Changing the protocol](#changing-the-protocol)).
- **Size limit.** Oversized frames are rejected outright (`WS_MAX_PACKET_BYTES`, default 262144).

## Message envelope

Every message, in both directions, shares one envelope:

```text
{
  id: string            // unique message id
  type: string          // one of the message types below
  timestamp: number     // epoch millis, sender clock
  correlationId?: string // set when the message answers an earlier one
  payload: <T>          // type-specific body, validated by schema
  hmac: string          // hmac-sha256 over the canonical json of the frame minus the hmac field
}
```

The `hmac` is computed over the **canonical json** of the envelope without the hmac field: object keys sorted recursively at every level, compact separators, exactly what both implementations hash. `correlationId` links a response to the request that caused it — session-check results answer specific probes.

Canonical json has no representation for values that would make one signature cover two different payloads: non-finite numbers (a raw `1e400` literal parses to `Infinity`, which hashes the same as `null`) and absurd nesting depth are refused, and a frame carrying them is rejected as malformed instead of signed or verified.

## Message types

### Lifecycle and status

| Type | Direction | Purpose |
|---|---|---|
| `SERVER_STATUS` | plugin → backend | Registration and status pushes; carries `onlinePlayers`/`maxPlayers`, optional `tps`, `version`, and the ops lifecycle `state` — `STARTING`, `RUNNING`, `RESTARTING`, `STOPPING`, `OFFLINE`, `UPDATE_READY`. Optional `activeSpecSessions` comes from the plugin's own spec registry and surfaces to admins in `/stats`; optional `telegramSuppressed` tells the backend that FlectonePulse's own Telegram module is announcing right now, so the bot stays quiet for this event |
| `SERVER_HEARTBEAT` | plugin → backend | Periodic liveness and metrics snapshot; carries optional `activeSpecSessions`. Heartbeats are deliberately ACK-expected — the ack is the plugin's read-side liveness signal |
| `SERVER_READY` | backend → plugin | Registration acknowledgement carrying the expected heartbeat cadence (`heartbeatIntervalMs`, `heartbeatTimeoutMs`). Defined in the protocol but **not emitted yet** — today the ack of the registration `SERVER_STATUS` is the effective "you are registered" signal |

### Player presence

| Type | Direction | Purpose |
|---|---|---|
| `PLAYER_JOINED` | plugin → backend | A session started; triggers account checks and, if needed, an auth request |
| `PLAYER_LEFT` | plugin → backend | Session ended; pending auth requests for that session are cancelled |

### Authentication

| Type | Direction | Purpose |
|---|---|---|
| `AUTH_REQUEST` | backend → plugin | Backend asks the plugin to gate a session pending Telegram confirmation |
| `AUTH_APPROVED` | backend → plugin | Confirmation accepted; plugin restores the player to normal play |
| `AUTH_REJECTED` | backend → plugin | Confirmation denied; the session stays gated |
| `AUTH_EXPIRED` | backend → plugin | Token TTL elapsed without a decision |

### Guest mode

| Type | Direction | Purpose |
|---|---|---|
| `GUEST_STARTED` | plugin → backend | Guest session created, snapshot stored |
| `GUEST_ENDED` | plugin → backend | Guest session finished (authed, or left and snapshot retained) |

### Admission and account

| Type | Direction | Purpose |
|---|---|---|
| `APPLICATION_SUBMITTED` | backend → plugin | An application entered voting (informational; used for in-game placeholders) |
| `APPLICATION_APPROVED` | backend → plugin | Application accepted; account may become ACTIVE |
| `APPLICATION_REJECTED` | backend → plugin | Application rejected |
| `APPLICATION_EXPIRED` | backend → plugin | Voting window closed without quorum after the single auto-extension |
| `ACCOUNT_DELETED` | backend → plugin | Account removed; any live session must be terminated |
| `LINK_TOKEN` | backend → plugin | A fresh one-time deep-link token for a joined player; the game chat shows the personal bot link |
| `SESSION_CHECK` | backend → plugin | Ownership probe: is this UUID in the game right now? |
| `SESSION_CHECK_RESULT` | plugin → backend | Probe answer carrying the live session state |

### Administration

| Type | Direction | Purpose |
|---|---|---|
| `SPEC_STARTED` | plugin → backend | An observation session started; backend records it in `audit_logs` (`kind: "spec"`). Payload: `adminUuid`, `targetUuid`, optional `sessionId`, `adminUsername`, `targetUsername`. |
| `SPEC_ENDED` | plugin → backend | Observation ended; backend closes the audit trail with `durationSec` and a `reason` (`NORMAL`, `FORCED`, `TARGET_DISCONNECTED`, `ADMIN_DISCONNECTED`, `ERROR`). |

### Localization

| Type | Direction | Purpose |
|---|---|---|
| `LOCALE_PUSH` | backend → plugin | Full content of one locale file (`ru` or `en`); replaces the plugin's copy for that language wholesale |
| `LOCALE_ACK` | plugin → backend | Outcome of an applied push; correlates with the push via `correlationId` |

`LOCALE_PUSH` payload:

```text
{
  locale: string     // language tag; the plugin bundles and accepts "ru" and "en"
  revision: string   // opaque backend-side revision id (commit sha or hash)
  content: string    // the FULL yaml file, never a diff
}
```

`LOCALE_ACK` payload:

```text
{
  locale: string
  revision: string   // echoed from the push
  accepted: boolean
  reason?: string    // present when accepted is false
}
```

Semantics:

- The plugin never fetches locale files itself; there is no HTTP path. The backend ships the canonical bundle in its own image and pushes it to every connected server.
- Pushes are **full-file**: the plugin replaces its whole locale store for that language. Partial merges are not attempted.
- The plugin validates the payload (yaml parses, locale is one it bundles, store is non-empty) before swapping the live store atomically; a rejected push leaves the running locale untouched and is reported back with a `reason`.
- On success the plugin persists the file to its data folder; the persisted file wins over the bundled seed on boot, so locale state survives restarts. The revision is not durable across a plugin restart — the next connection receives the current bundle again.
- Re-delivery of the same `revision` is safe: the plugin re-parses and re-applies it idempotently.

Push semantics:

- The backend loads its canonical bundle once at boot (a missing or empty bundle fails boot; an oversized one is refused before it can produce an undeliverable frame). `revision` is derived from the content: the first 16 hex chars of its sha256, so an unchanged file always pushes the same id.
- The backend currently serves only `ru`. The plugin also bundles `en` as its base layer, and its push handler accepts `en` the same way, so serving the second locale later is a backend-side change with no protocol change.
- Every fresh connection receives one `LOCALE_PUSH` immediately after its registration `SERVER_STATUS` is acknowledged. The push is not repeated for the status frames that follow on the same socket; a reconnect is a new socket and gets a fresh push.
- `LOCALE_ACK` is tracked per server id. The backend distinguishes an applied bundle, a rejection (with the plugin's reason), and an ack for a revision this process no longer serves. A missing ack is not retried on the same connection — the next registration re-delivers the current revision.
- Old plugins without a `LOCALE_PUSH` consumer simply never ack: the push stays unacknowledged on that connection and the next registration re-delivers it. An older backend never sends the frame at all, and a newer one is harmless to a build that does not know the type.

## Changing the protocol

`protocol/messages.json` is the machine-readable contract for every wire type, and both sides are tested against it. One entry per type carries:

- `type` — the wire name
- `direction` — `plugin_to_backend`, `backend_to_plugin` or `both`
- `fields[]` — the payload contract: `uuid` / `objectId` / `string` / `integer` / `number` / `boolean` / `enum` (with `values`) / `object` (with nested `fields`), each flagged `required` or not
- `example` — one complete, schema-valid payload
- `pluginTransport` (`typed` / `raw` / `absent`) and, when the ws layer decodes it, `pluginDto`

Two suites check themselves against that file:

- `backend/tests/protocol-contract.test.ts` walks the zod schemas: every manifest type has a payload schema and every schema is in the manifest, field names / required-ness / types / enum values line up both ways, `BACKEND_TO_PLUGIN_TYPES` matches the declared directions, and every example validates.
- `plugin/src/test/kotlin/kz/bejiihiu/servercore/websocket/ProtocolContractTest.kt` checks the kotlinx side: `MessageType` lists exactly the carried types, each typed payload branch parses its example and re-emits every required field, each raw type decodes to `Payload.Raw` with the wire name intact, and every ws DTO declares the manifest fields with the same optionality and parses the example.

**Adding or changing a message is one change across four places:** the manifest entry, the backend zod schema (plus `MESSAGE_TYPES` / `BACKEND_TO_PLUGIN_TYPES`), the plugin `MessageType` and payload (or DTO), and any handler. `bun test` and `./gradlew :plugin:test` fail the build when they drift — a renamed or removed required field, a required↔optional flip, a direction change, an enum value the other side does not know, a type or field missing from one of the two sides, or an example that no longer parses. Types marked `status: "reserved"` are documented but not on the wire; the plugin deliberately does not carry them.

## Handshake flow

```text
plugin                              backend
  | --- WS connect (WSS) -----------> |
  |      /ws?server=<id>&ts=<ms>&nonce=<n>&sig=<hmac>
  |                                  verifies hmac over server|ts|nonce
  |                                  BEFORE upgrade (replay-guarded nonce)
  | <--- 101 upgrade accepted ------- |   socket now exists
  | --- SERVER_STATUS (register) ---> |  identity + current load + ops state
  | <--- ACK ------------------------ |
  | <--- LOCALE_PUSH ---------------- |  full canonical bundle (fresh socket only)
  | --- LOCALE_ACK -----------------> |  applied / rejected + reason
  | --- SERVER_HEARTBEAT (periodic) ->|  acked by the backend
```

The handshake has no in-band hello: the query-string signature is the whole authentication, and a `SERVER_HELLO` frame arriving after the upgrade is refused as a downgrade attempt. `SERVER_READY` exists in the protocol but is not emitted yet; the ack of the registration `SERVER_STATUS` is the effective registration signal.

Until the connection passes the pre-upgrade handshake, the backend never even creates the socket — there is no unauthenticated frame window to exploit.

## Heartbeat and liveness

- The plugin sends `SERVER_HEARTBEAT` on a fixed cadence (default 15 s) and expects an ack for each one.
- The backend tracks last-seen per connection and flags stale servers.
- The plugin runs the mirror check: no inbound frame for `backend.liveness-timeout-milliseconds` on a "connected" socket is treated as a half-open link and forces a reconnect.

## Reconnect semantics

Disconnections are expected and handled on both sides:

1. The plugin reconnects with **exponential backoff**, starting at `backend.reconnect.initial-delay-seconds` and capped at `backend.reconnect.max-delay-seconds`.
2. After a successful reconnect, the plugin re-handshakes and **reconciles state** — live sessions are re-announced so both sides agree on reality.
3. Events are classified by durability: heartbeats/acks are stale-by-definition and dropped on a blip; presence, status and spec events are queued in memory; guest lifecycle frames (with snapshots) are **durable** and parked on disk in a bounded outbox (`backend.outbox-max-entries`), replayed once the connection returns, preserving order.
4. Redelivery is safe on both ends: the plugin remembers processed backend pushes for `backend.dedupe-ttl-milliseconds` and acks-but-ignores duplicates; the backend re-acks replayed frames without reprocessing them.

## Failure posture

The backend being down must never silently grant access:

- **New logins fail closed.** Without a live backend no one completes authentication; the server does not auto-approve.
- **Brief blips do not evict players.** Already-authenticated players keep playing through short outages.
- **Guest mode keeps working.** Guest sessions and restrictions are enforced locally and continue during an outage.
- **Nothing is lost.** Events generated offline wait in the outbox; critical state lives in MongoDB, not in memory.

The same posture applies one layer down, when the backend is up but MongoDB is not:

- An `ACK` means *received*, not *processed*. If handling a frame needs the database and the write fails, the backend answers that ack with `ERROR { code: "HANDLER_FAILED", inReplyTo: <frame id> }` on the same connection. The socket stays open and later frames are processed normally — a database blip never costs the game server its link.
- The failed frame is not retried by the backend; the retry decision belongs to the plugin (next point). The backend's own parked pushes (auth prompts and verdicts, link tokens) flush on the next sweep once the database is back.
- The plugin owns the retry. A correlated `ERROR` means the frame was *not applied*, so a `QUEUE` or `DURABLE` frame goes back into its park and is resent after a short delay. The resend carries a fresh envelope id and timestamp — a recycled id would only be re-acked as a duplicate and the handler would never run again — so every handler has to tolerate processing the same event twice. Retries are bounded at three; after that the frame is logged at warn and dropped, and a dropped counter is incremented. Fire-and-forget frames (heartbeats, status) are only logged, and deterministic failures such as `BAD_PAYLOAD` are never retried. An `ERROR` without `inReplyTo` is logged and otherwise ignored.
