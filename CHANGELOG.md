# Changelog

All notable changes to ServerCore are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/).

## Versioning

ServerCore does not use [Semantic Versioning](https://semver.org/). Every build is identified as
`build-<commitCount>-<shortSha>` — the commit count of the repository history at build time and the
short SHA of the built commit. Release tags take exactly that form, and the tag is baked into the
plugin jar so the in-plugin updater can compare builds.

There are no numbered releases yet: the project is pre-release and everything below describes the
current, unreleased state of the codebase.

## [Unreleased]

### Added

#### Guest mode (plugin)

- Join-time identity resolution with a local cache and a fail-closed fallback to guest when the
  backend answer does not arrive in time.
- Spectator guest sessions with a full snapshot of the player's original state (world, position,
  angles, gamemode, speeds) and restoration on authorization, quit or leave.
- Per-interaction restrictions, all configurable: block break/place, interaction, containers, item
  pickup/use, entity damage, portals, inventory edits, chat, commands and gamemode changes.
- Guest session limits with configurable kick-after-limit behaviour, actionbar reminders, a command
  whitelist and a plugin-answered `/link` prompt.
- Durable guest state: snapshots and a bounded outbox survive plugin restarts, with restore-on-restart.
- Chat isolation enforced before other chat plugins see the event, so guests neither read nor write
  chat.

#### Accounts and admission (backend)

- Minecraft UUID ↔ Telegram ID account registry with uniqueness checks; linking uses one-time
  deep-link codes and is verified against the live game session, never against a typed nickname.
- Bot command set: `/start`, `/status`, `/application`, `/myapplication`, `/cancel`, `/language`,
  `/help`, plus the admin-only `/stats`.
- Step-by-step admission questionnaire: configurable RU/EN question sets of equal length, resumable
  drafts, cancellation at any step, duplicate and rate limits, and a 3-month reapplication lock
  anchored to the last submission.
- Community voting in the closed Telegram group: 3-hour window, eligible-voter count frozen at start,
  one vote per UUID, no self-voting, ephemeral per-vote confirmations, a single automatic extension,
  and `EXPIRED` routing to manual review instead of silent approval.
- Verdicts pushed to the plugin, the applicant and the admin chat; single-use group invites resolved
  by an actual join before an account becomes `ACTIVE`.
- Account deletion as a sequence: live-session termination, group removal, invite revocation, link
  revocation, recovery notifications, anonymized audit record.
- Passwordless authentication: one-time tokens stored as hashes, 60–90 s TTL, session binding,
  single-flight cancellation of previous requests, per-player and per-Telegram rate limits, a sweep
  for expiry and prompt retries, explicit reject and expire states.

#### Anti-ESP packet layer (plugin)

- PacketEvents foundation with per-viewer view-state stores and a canonical `VISIBLE` / `DISGUISED` /
  `HIDDEN` model shared by every consumer.
- Visibility engine: spatial grid, server-side region-safe line-of-sight, distance and sneaking
  rules, per-pair caching with rate-limited re-evaluation.
- Per-viewer disguises: hidden players are replaced by synthetic mob entities with synthetic
  equipment and attributes, chosen from a configurable mob list.
- Packet-state synchronization: reconciler, resync executors, identity tracking and invalidation on
  world change, respawn, teleport, gamemode or equipment change.

#### Hidden observation (`/spec`)

- `/spec <player>` sessions with access policy from LuckPerms group levels or a standalone permission
  node, plus tab completion for online players.
- Hidden observer with target-side invisibility, particle/sound trace filtering and configurable
  actionbar suppression.
- Read-only live inventory viewer with push throttling and periodic full resync.
- Session registry and coordinator, automatic session end when either side disconnects, and a restore
  journal so staff state survives the session.
- Audit trail written both to a rotating local JSONL trail and to backend `audit_logs`, with the live
  session count surfaced in `/stats`.

#### Server protection

- Blocked-command matcher that resolves names against the live command tree, so one entry covers its
  whole alias group and namespace prefixes match the same command.
- Datapack execution paths denied (`/function`, `/schedule`, `/datapack`).
- Gamemode enforcement: Survival is the player-facing default, non-sanctioned changes are cancelled
  and drifted modes reverted by a periodic sweep; sanctioned states come from ServerCore's own state,
  not from OP status.
- OP lockdown, command-block auditing with optional in-place neutralization, and tab-completion
  filtering for denied commands.
- `/servercore groups <player>` and `/servercore deop-all` administrative commands.

#### Ops

- Self-update from GitHub releases: polls `build-*` tags, verifies `SHA256SUMS`, swaps the jar and can
  restart automatically.
- Daily scheduled restarts with in-game title/actionbar countdowns, plus Pterodactyl panel restarts
  with a console fallback.
- Lifecycle state broadcast — `STARTING`, `RUNNING`, `RESTARTING`, `STOPPING`, `OFFLINE`,
  `UPDATE_READY` — to the backend.
- Server status and heartbeat tracking, offline detection with admin alerts (optionally DM-ed to all
  linked users), and `/stats` with real player, TPS, version and observation-session counts.
- Retention sweeps for audit records, server events and closed sessions.
- Deployment kit (`deploy/`): a docker-compose stack with Caddy TLS in front of the backend, a systemd
  unit, a Pterodactyl egg and a commented backend environment template, each with the same set of
  environment keys the code reads.
- Backup and restore tooling (`deploy/backup/`): a verified MongoDB dump plus the plugin's durable
  state files in one archive, with retention, dry runs, a checksum-verified restore and a disaster
  runbook.

#### Integrations

- LuckPerms as the permission authority, with a static config-driven fallback so the plugin still
  boots without it.
- PlaceholderAPI `%servercore_…%` expansion served exclusively from an in-memory state cache fed by
  WebSocket events and player lifecycle.
- FlectonePulse punishment lookups with event-driven cache invalidation and suppression of
  ServerCore's own Telegram announcements when FlectonePulse's Telegram module is active.

#### Server info bars

- Per-player toggle bars in the Purpur spirit — `/rambar`, `/tpsbar`, `/msptbar`, `/playersbar`, `/worldbar`,
  `/netbar` — rendered as a bossbar or an actionbar line, with `all` and per-player target forms and
  `.other` permission nodes.
- Toggle state is persisted per player (`bars.json`), so a bar survives a relog; Purpur drops it.
- Folia-native metrics: TPS and MSPT belong to the region the player stands in, read on that player's
  own entity scheduler, with the chunk pair and the 1-minute window in the title; MSPT uses Folia's
  region-scoped tick report with a tick-pacing fallback instead of a server-wide number that does not exist.
- Heap sampling on the async scheduler with a volatile snapshot, actionbar-priority-safe rendering, and
  a neutral surface for the player/world bars.
- `NETWORK_SNAPSHOT`: the backend aggregates every game server's heartbeat into one frame and pushes it
  to all servers after each heartbeat window, so `/netbar` shows the whole fleet (`3/3 servers · 7 online
  · 1 offline`) with per-server staleness instead of polling.
- Bar values exported to PlaceholderAPI: `%servercore_bar_ram%`, `%servercore_bar_tps%`,
  `%servercore_bar_mspt%`, `%servercore_bar_players%`, `%servercore_bar_net%`, served from the same
  snapshot layer (never computed on the asking thread), with an ambient sampler so they stay fresh with
  no bar toggled.
- `docs/bars.md`: command and config reference, permission model, the Folia scope of each number, and
  scoreboard/tab examples for the placeholders.

#### Telegram bot UX

- Shared bot UI toolkit (screens, keyboards, sending, text rendering) used by every flow, including
  authentication prompts.
- RU/EN localization with pluralization and an emoji registry; per-account language selection.
- One bot instance for account, admission and auth flows, with MongoDB-backed conversation storage.
- Notification fan-out with a configurable rate ceiling, approval message effects and optional custom
  emoji.

#### WebSocket resilience

- Signed handshake authentication before the upgrade, per-frame HMAC signatures, timestamp windows,
  burned handshake nonces and duplicate-id re-acknowledgement without re-execution.
- Reconnect with exponential backoff and configurable caps, plus a liveness watchdog that forces a
  reconnect on half-open links.
- Ack-expected frames with bounded retries and a durable on-disk outbox for guest lifecycle frames.
- Inbound dedupe window, graceful gateway stop with in-flight frame drain, and per-frame size limits.
- Backend handler registry with deduplicated registration and a single player-join handler.
- `NETWORK_SNAPSHOT` broadcast on the heartbeat cycle (throttled to one push per second) carrying every
  known server's load and heartbeat age; the protocol manifest, both contract suites and
  `docs/websocket-protocol.md` cover the new type.

#### Build, tests and docs

- Multi-module Gradle build for the plugin and a Bun/TypeScript backend, with CI for both sides and a
  tag-based release pipeline producing the plugin jar, backend bundle and checksums.
- Backend test suite covering protocol and envelope validation, authentication, admission and voting
  math, account lifecycle and deletion, retention, WebSocket wiring and resilience, i18n and bot
  screens.
- Cross-language protocol contract: one manifest (`protocol/messages.json`) describing every wire
  type, its direction and payload fields, checked against the zod schemas and the plugin codec/DTOs by
  tests on both sides, so schema drift fails the build instead of production.
- Plugin tests for guest logic, protocol compatibility, permissions and packet-state behaviour.
- Hot-path benchmark harness (`./gradlew :plugin:benchmark`) covering the view-state store, the
  spatial grid, the ray march, the visibility decider, the packet resync executor, the WebSocket codec
  and bar rendering, with the measurements and the rejected experiments written down in
  `docs/performance.md`.
- Documentation set: architecture, installation, a first-run walkthrough, configuration, WebSocket
  protocol, admission, authentication, security, FAQ, server info bars, the in-game UI design system,
  the performance guide and the backup and restore runbook.
- Russian documentation: a translated README and a `docs/ru/` set covering installation, first run,
  configuration and FAQ, linked from the English pages.

### Changed

- Plugin restructured into focused modules (`guest`, `packet`, `esp`, `disguise`, `sync`, `spec`,
  `spectator`, `protection`, `integration`, `placeholderapi`, `locale`, `ops`, `websocket`, `ui`) with
  a single Folia entry point.
- One canonical view-state enum and one packet bridge shared by ESP, disguises and synchronization.
- Observation sessions unified under a single coordinator instead of separate spec wiring paths.
- Locale handling accepts full-file `LOCALE_PUSH` frames for `ru` and `en` and answers `LOCALE_ACK`; the
  persisted file wins on boot. The backend serves and pushes the canonical `ru` bundle today, while `en`
  ships inside the plugin jar as the base layer.
- Player-facing text has one canonical store (`locales/ru.yml`, `locales/en.yml`). The old flat
  `messages_*.yml` layer is gone and `util/I18n.kt` is now a thin facade over `locale/LocaleService`.
- Documentation synced with the implemented code: real environment keys, full plugin config
  reference, protocol types and commands.

### Fixed

- Plugin: crash on disable after a partial boot; overlapping self-update cycles; in-flight frames
  dropped on gateway stop; stale spec journal replay after restart.
- Guest: latch leak on repeated joins, quit-versus-rejoin race, ordering of durable snapshot writes,
  dead item-use path, snapshot restore after restart.
- Packet layer: applied view state expiring on a timer, viewer state leaking past disconnect,
  override rows lost on destroy, entity identity not registered on join, already-online players
  missed by the ESP start pass, disguise capture when the scheduler refuses work, dead pins from
  stale engine settles.
- Protection and guest hardening: guests could damage players with projectiles.
- Backend: MongoDB readiness checks always false at boot (missing indexes, null stats), process
  surviving a startup `uncaughtException`, immortal outbox frames and ack races in the client,
  scheduler reentrancy, unbounded maps, config flag handling.
- Deletion completeness: invites now revoked, recovery notifications sent, draft and conversation
  state TTLs applied.
- `APPLICATION_EXPIRED` delivered as its own frame type instead of being folded into rejection.
- Ops bus sink registered on the gateway, ops fields carried on `SERVER_STATUS`, heartbeat cadence
  read from config.
- Localization gaps: missing Russian strings for the spec section and the guest auth card, plus a
  duplicate default language key in the env config.

### Security

- Per-server HMAC secrets authenticate the WebSocket handshake before the socket exists; unsigned or
  stale frames are dropped in both directions.
- One-time, hashed, session-bound authentication tokens with a 60–90 s TTL and replay-safe callbacks.
- Fail-closed posture: no backend (or no Telegram) means no new approvals; authenticated players and
  guest sessions are unaffected by short outages.
- OP is never used for administration; command protection is enforced at the command-source level for
  every game account, including operator-flagged ones.
- Permission lookups fail closed — failed LuckPerms lookups are never cached as grants, and
  FlectonePulse lookup failures are not cached as "no punishment".
- Every observation session and account deletion is audit-logged; hidden observation is invisible to
  the observed player but never to the audit trail.
- Secrets exist only in environment variables; examples and documentation carry placeholders only.
- Dependency and supply-chain status is audited and recorded: `bun audit` clean, every flagged plugin
  advisory traced to a server-provided compile-only API, the Gradle distribution pinned by SHA-256, and
  the license split (shipped / provided by the server) documented in `THIRD_PARTY_LICENSES.md`.
