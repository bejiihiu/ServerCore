# Architecture

ServerCore is split into two independent applications that share nothing at the code level:

1. **Backend** — Bun + TypeScript. Owns the Telegram bot, all business logic that does not need Minecraft API access, and MongoDB.
2. **Minecraft plugin** — Kotlin on Java 25, running inside a Folia server. Owns everything that touches the game world.

They communicate over exactly one channel: a secured outbound WebSocket connection initiated by the plugin. There is no HTTP API between the two halves — the backend's Fastify instance serves the `/health` endpoint only, never plugin traffic.

## System overview

```text
                 TELEGRAM
                    |
                grammY bot
                    |
             Bun backend (Fastify /health + native WS gateway)
                    |
          application services
                    |
                 Mongoose
                    |
                 MongoDB
                    |
                 WebSocket  (WSS, HMAC-signed, replay-protected)
                    |
             Minecraft plugin
                    |
          +---------+---------+
          |                   |
       Folia API         PacketEvents
          |                   |
          +---------+---------+
                    |
                 Minecraft
                    |
              FlectonePulse
```

## Backend

The backend is the brain. It owns:

- The Telegram bot (grammY): menus, account management, application forms, voting, login confirmations, notifications, account deletion. One bot instance carries every flow — account routes, admission (questionnaire/voting/invites) and auth callbacks.
- Account registry: Minecraft UUID ↔ Telegram ID linkage, with uniqueness and integrity checks.
- Admission pipeline: applications, 3-hour community votes, quorum evaluation, group invitations.
- Authentication: one-time tokens (stored hashed), short expiry windows, session binding.
- Telegram group administration: single-use invites, join confirmation, removal on account deletion.
- Storage: every persistent collection in MongoDB.
- Event history, session history, audit logging, notifications, retention sweeps.
- Ops watch: per-server live state from `SERVER_STATUS`/`SERVER_HEARTBEAT`, offline detection with admin alerts, and the admin-only `/stats` bot command.

Its HTTP server (Fastify) serves the `/health` endpoint only. All plugin traffic rides the WebSocket gateway — a separate native listener that never touches Fastify.

It never manipulates the game directly. Whenever game state must change, the backend sends a validated command over WebSocket and the plugin performs it inside the correct Folia context.

Runtime stack: Bun, TypeScript (strict), Fastify, grammY, Mongoose, Zod, Pino. Runtime validation of every incoming structure goes through Zod.

## Plugin

The plugin is the executor and the game-side authority. Its code is organized as focused packages:

| Module | Responsibility |
|---|---|
| `guest` | Join-time identity checks, guest sessions and session limits, interaction restrictions, location snapshots and restoration, local account fallback |
| `packet` | PacketEvents integration core: entity packet listener, per-viewer view-state stores, packet threads and compatibility handling |
| `esp` | Anti-ESP engine: spatial grid, region-safe raytracer, per-(viewer, target) visibility decisions with caching |
| `disguise` | Per-viewer synthetic mob disguises for hidden players: profile selection, PacketEvents rendering, Folia-safe scheduling |
| `sync` | Packet-state synchronization: reconciler, resync executors, identity tracking, invalidation — keeps client view state agreeing with server truth |
| `spec` | Hidden observation stack: `/spec` command, session registry and coordinator, permission policy (group levels or standalone node), hidden observer, audit trail, restore journal |
| `spectator` | The observation toolkit behind `/spec`: read-only live inventory viewer (push-throttled, with periodic full resync), trace filtering, observer hiding, view-state applier |
| `protection` | Command protection (blocked-command matcher with alias expansion against the live command tree), gamemode guard and drift sweeps, OP lockdown, command block auditing, `/servercore` admin commands |
| `integration` | LuckPerms (permission authority, with a static config fallback when absent), FlectonePulse (punishment lookups, telegram announcement suppression) |
| `placeholderapi` | The `%servercore_…%` expansion, served from an in-memory state cache fed by websocket events and player lifecycle |
| `locale` | Locale store and service; accepts full-file `LOCALE_PUSH` frames and answers with `LOCALE_ACK` |
| `ops` | Self-update from GitHub releases, scheduled restarts, Pterodactyl panel restarts, graceful shutdown with player warnings, server lifecycle state broadcast |
| `websocket` | Gateway client: protocol codec, reconnect policy, inbound dedupe, durable outbox |
| `ui` | Shared UI toolkit: countdowns, holograms, theming, placeholders |
| `boot`, `config`, `player`, `util` | Bootstrap/commands wiring, config loading, presence heartbeat, shared utilities |

The plugin contains no Telegram API calls, no Mongoose, and no voting logic.

## Threading model — Folia regionised

The plugin assumes **no main thread exists**. Every operation is dispatched to the scheduler that owns the resource it touches:

| Operation | Mechanism |
|---|---|
| Work on a specific player | `EntityScheduler` |
| Work on a specific location/chunk | `RegionScheduler` |
| Global server operations | `GlobalRegionScheduler` |
| Background periodic tasks | `AsyncScheduler` |
| Blocking I/O (files, WebSocket handlers) | Virtual threads (Java 25) |

Hard rules the codebase follows:

- WebSocket callbacks never touch `Player`, `World`, `Entity` or `Inventory` directly. The path is always: *receive → validate → resolve UUID → hop onto the owning scheduler → execute*.
- No mutable Bukkit objects are held in global collections; concurrent state uses concurrent structures, never a plain `HashMap`.
- No blocking I/O on region threads; no heavy packet processing on region threads.
- Scheduled tasks tied to an entity are cancelled when the entity is removed.
- Virtual threads scale background I/O only — they are never a shortcut around Folia's regional model.

The plugin metadata declares `folia-supported: true`. That declaration is a contract the implementation is audited against, not a build flag.

## Data flow

### New player joins (first time)

```text
Minecraft join
   -> plugin checks registry (local cache, no sync DB call)
   -> no account -> guest session created
   -> original state snapshotted (world, coords, angles, gamemode, speeds)
   -> player moved to Spectator, interactions disabled
   -> personal one-time deep link posted in chat
   -> player opens Telegram -> /start <linking code>
   -> backend verifies ownership of the Minecraft session
   -> preliminary Telegram <-> UUID link created
   -> application flow unlocked
```

### Authenticated login

```text
Minecraft join
   -> plugin sends PLAYER_JOINED
   -> backend validates account status (ACTIVE, not banned/suspended)
   -> backend creates the session (its own session id) and an auth request:
        one-time token (hashed, 60-90 s TTL), bound to that session id
   -> bot asks the owner: confirm / deny
   -> confirmation -> AUTH_APPROVED over WebSocket (session id echoed back)
   -> plugin validates the event, hops to EntityScheduler
   -> player restored to normal gameplay
```

### Admission vote

```text
Application submitted (Telegram, step-by-step form)
   -> backend starts vote, 3 h window
   -> eligible-voter count frozen at start
   -> members vote via inline keyboard (one vote per UUID)
   -> expiry: quorum >= 50 % of eligible AND > 50 % of valid votes in favour
   -> approved -> single-use group invite -> join event confirms membership
   -> rejected/expired -> player notified, 3-month reapplication lock
```

### Anti-ESP evaluation

```text
Player movement / periodic tick (budgeted rate)
   -> spatial index selects nearby candidates per viewer
   -> server-side line-of-sight check (block geometry, eye positions,
      distance, sneaking state)
   -> per (viewer, target) pair state resolved:
        VISIBLE  - real entity representation
        DISGUISED - synthetic entity, synthetic equipment/attributes
        HIDDEN   - no representation at all
   -> PacketEvents applies the per-viewer packet changes
   -> results cached with controlled refresh; invalidated on
      world change, respawn, teleport, gamemode or equipment change
```

### Backend outage

```text
WebSocket drops
   -> reconnect with exponential backoff
   -> new logins are NOT auto-approved (fail closed)
   -> already-authenticated players keep playing
   -> guest sessions keep working locally
   -> uncancellable events queue in a durable outbox
   -> on reconnect, state is reconciled and queued events replayed
```

## Storage model

MongoDB is the single persistent store on the backend side. Principal collections:

| Collection | Purpose |
|---|---|
| `users` | Accounts: Minecraft identity, Telegram link, lifecycle status |
| `applications` | Admission forms and their voting metadata |
| `application_drafts` | In-progress questionnaire state, so an abandoned conversation can resume |
| `votes` | Individual vote records (unique per application + voter) |
| `voting_messages` | The live vote card in the group, kept for result updates |
| `sessions` | Player sessions with lifecycle status |
| `auth_requests` | Pending/completed login confirmations (token hashes, TTL-indexed) |
| `telegram_links` | Telegram ↔ Minecraft linkage |
| `guest_snapshots` | Original player state captured before guest mode |
| `group_invites` | Single-use invite artifacts and their consumption state |
| `audit_logs` | Administrative actions, observation sessions, deletions |
| `server_events` | Raw event stream from the plugin |
| `conversation_states` | grammY conversation storage (Mongo-backed) |
| `punishments_external` | Read-only view over an external punishment source; ServerCore does not create its own punishment system |

Transient data (auth requests, expired artifacts) uses TTL indexes. Accounts, votes and audit records never expire via TTL.

Dead rows also get a daily retention sweep on top of TTL: closed sessions (60 days), orphaned guest snapshots, vote cards and votes of applications decided more than 90 days ago, revoked or expired-unused group invites (30 days, used ones after a year) and rejected/expired applications older than a year. Accounts, approved applications and audit records stay.

The plugin keeps its own small durable files next to its config: guest snapshots (`guest.snapshot-storage-path`), the durable outbox for guest lifecycle frames, and the local spec audit trail (`spec.audit.*`). These exist so the plugin survives its own restarts and backend outages without turning the game server into a second database.

## Performance posture

- WebSocket push instead of polling; no per-player periodic MongoDB reads.
- Local caches for account and auth state with controlled freshness.
- Anti-ESP uses spatial partitioning, per-pair caching and rate-limited checks — never an O(n²) per-tick sweep.
- Non-critical events are batched; queues have bounded memory.
- PlaceholderAPI expansions read from cache only, never issue synchronous database queries.
