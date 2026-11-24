# WebSocket protocol

The WebSocket channel is the only transport between the backend and the Minecraft plugin. There is no HTTP fallback and none is planned. The plugin is always the initiating side; the backend is the gateway.

## Transport and security

- **WSS only.** Connections ride TLS; plaintext upgrades are rejected.
- **Per-server secret.** Each registered Minecraft server has its own HMAC secret, configured identically on both ends.
- **Handshake authentication.** The plugin's first frame is a `SERVER_STATUS` (hello) message carrying the server identity, a timestamp and a nonce. The backend verifies the HMAC signature over the frame body before accepting anything else.
- **Message signing.** Critical messages (auth decisions, commands, state changes) carry an HMAC signature computed over the serialized payload plus timestamp plus nonce.
- **Replay protection.** Timestamps must fall within a tolerance window; nonces are tracked and duplicates rejected.
- **Schema validation.** Every inbound message is validated against its type's schema — Zod on the backend, typed DTOs with kotlinx.serialization on the plugin.
- **Size limit.** Oversized frames are rejected outright (`WS_MAX_PAYLOAD_BYTES`).

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

## Message types

### Lifecycle and status

| Type | Direction | Purpose |
|---|---|---|
| `SERVER_STATUS` | plugin → backend | Initial hello and registration; carries server identity, version, current load. The payload also carries an optional `state` — the ops lifecycle: `STARTING`, `RUNNING`, `RESTARTING`, `STOPPING`, `OFFLINE`, `UPDATE_READY`. Backend fans these out to telegram (users + admins). |
| `SERVER_HEARTBEAT` | plugin → backend | Periodic liveness and metrics snapshot |
| `SERVER_STATUS` (response) | backend → plugin | Registration acknowledgement, expected heartbeat cadence |

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
| `ACCOUNT_DELETED` | backend → plugin | Account removed; any live session must be terminated |
| `LINK_TOKEN` | backend → plugin | A fresh one-time deep-link token for a joined player; the game chat shows the personal bot link |
| `SESSION_CHECK` | backend → plugin | Ownership probe: is this UUID in the game right now? |
| `SESSION_CHECK_RESULT` | plugin → backend | Probe answer carrying the live session state |

### Administration

| Type | Direction | Purpose |
|---|---|---|
| `SPEC_STARTED` | backend → plugin | Audit notification that an observation session started |
| `SPEC_ENDED` | backend → plugin | Observation ended; recorded in audit logs |

### Localization

| Type | Direction | Purpose |
|---|---|---|
| `LOCALE_PUSH` | backend → plugin | Full content of one locale file (canonical `ru`); replaces the plugin's copy wholesale |
| `LOCALE_ACK` | plugin → backend | Outcome of an applied push; correlates with the push via `correlationId` |

`LOCALE_PUSH` payload:

```text
{
  locale: string     // language tag, currently only "ru" is accepted
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

- The plugin never fetches locale files itself; there is no HTTP path. The backend renders the current canonical content (hosted on GitHub) and pushes it to every connected server.
- Pushes are **full-file**: the plugin replaces its whole locale store for that language. Partial merges are not attempted.
- The plugin validates the payload (yaml parses, locale matches the canonical tag, store is non-empty) before swapping the live store atomically; a rejected push leaves the running locale untouched and is reported back with a `reason`.
- On success the plugin persists the file to its data folder; the persisted file wins over the bundled seed on boot, so locale state survives restarts. After a restart the revision is reported as unknown, and the backend may re-push the current revision.
- Re-delivery of the same `revision` is safe: the plugin re-parses and re-applies it idempotently.

## Handshake flow

```text
plugin                              backend
  | --- WS connect (WSS) -----------> |
  |      ?server=<id>&ts=<ms>&nonce=<n>&sig=<hmac>
  |                                  verifies hmac over server|ts|nonce
  |                                  BEFORE upgrade (replay-guarded nonce)
  | --- SERVER_STATUS (hello) -----> |  identity + current load
  | <--- ACK ----------------------- |
  | --- SERVER_HEARTBEAT (periodic) ->|
```

Until the handshake is acknowledged, the backend ignores every other message type from that connection.

## Heartbeat and liveness

- The plugin sends `SERVER_HEARTBEAT` on a fixed cadence (default 15 s).
- The backend tracks last-seen per connection and flags stale servers.
- Missed heartbeats eventually close the connection from the backend side.

## Reconnect semantics

Disconnections are expected and handled on both sides:

1. The plugin reconnects with **exponential backoff**, starting at `backend.reconnect.initial-delay-seconds` and capped at `backend.reconnect.max-delay-seconds`.
2. After a successful reconnect, the plugin re-handshakes and **reconciles state** — live sessions are re-announced so both sides agree on reality.
3. Events that must not be lost are queued in a durable local outbox while the connection is down and replayed once the connection returns, preserving order per player.
4. Command handling is **idempotent**: a replayed command must not produce a duplicated game effect; message IDs allow the receiver to drop duplicates.

## Failure posture

The backend being down must never silently grant access:

- **New logins fail closed.** Without a live backend no one completes authentication; the server does not auto-approve.
- **Brief blips do not evict players.** Already-authenticated players keep playing through short outages.
- **Guest mode keeps working.** Guest sessions and restrictions are enforced locally and continue during an outage.
- **Nothing is lost.** Events generated offline wait in the outbox; critical state lives in MongoDB, not in memory.
