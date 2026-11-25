# Configuration

**Languages:** English | [Русский](ru/configuration.md)

ServerCore is configured in two places: the backend's environment file and the plugin's `config.yml`. This page documents every option.

> **Presets.** [`examples/`](../examples/README.md) ships ready-to-copy starting points: complete plugin `config.yml` files for a small (10-30 players) and a large (100+) server, plus development and production backend env files. They use only the keys documented on this page.

## Backend — environment variables

All variables come from `.env` (copy `.env.example` as the starting point). Every key below is read and validated by the backend's Zod config at boot; an invalid value aborts the start with a summary of what is wrong.

### Runtime and storage

| Variable | Required | Default | Description |
|---|---|---|---|
| `NODE_ENV` | no | `development` | `development` / `production` / `test` |
| `PORT` | no | `8080` | HTTP port; serves the `/health` endpoint (Fastify) |
| `HOST` | no | `0.0.0.0` | Bind address, shared by the HTTP server and the WebSocket gateway |
| `WS_PORT` | no | `8081` | Port of the native WebSocket gateway — a separate listener, not part of the HTTP server |
| `MONGODB_URI` | no | `mongodb://localhost:27017/servercore` | MongoDB connection string, database name included (`mongodb+srv://…` for Atlas) |
| `LOG_LEVEL` | no | `info` | Pino level: `trace`, `debug`, `info`, `warn`, `error`, `fatal` |
| `LOG_TIMEZONE` | no | `Europe/Moscow` | Timezone for log timestamps and schedules |

### Server credentials

| Variable | Required | Default | Description |
|---|---|---|---|
| `SERVERCORE_SERVER_CREDENTIALS` | yes | `{}` | JSON object mapping server id → per-server HMAC secret: `{"game-1": "…"}`. Secrets must be at least 32 characters (`openssl rand -hex 32`). Each game server's plugin config carries the matching id and secret |
| `SERVER_NAME` | no | `ServerCore` | Pretty server name shown in Telegram authentication prompts |

### Telegram

| Variable | Required | Default | Description |
|---|---|---|---|
| `BOT_TOKEN` | no | — | Token from [@BotFather](https://t.me/BotFather). Leave empty to run without the bot — the backend then runs bot-less instead of crashing |
| `GROUP_CHAT_ID` | no | — | Numeric chat ID of the closed community group where voting runs and approved members join. Empty degrades the group flow gracefully |
| `TELEGRAM_GROUP_CHAT_ID` | no | — | Group chat id used for kick/invite management (string form). Empty until the group exists |
| `ADMIN_TELEGRAM_IDS` | no | — | Comma-separated Telegram user ids that receive copies of key events and may call the admin-only `/stats` bot command |
| `OPS_LOCALE` | no | `ru` | Default interface language for group/admin and ops messages (`ru` / `en`); per-account overrides apply |
| `BOT_CUSTOM_EMOJI` | no | `false` | Custom emoji entities in bot messages (Bot API 9.4, needs Telegram Premium on the bot owner). Off keeps unicode fallbacks everywhere |

### Admission

| Variable | Required | Default | Description |
|---|---|---|---|
| `APPLICATION_QUESTIONS_RU` | no | 4 default questions | Questionnaire questions (Russian), json array of 1–10 strings |
| `APPLICATION_QUESTIONS_EN` | no | 4 default questions | Questionnaire questions (English), must match the RU list length |
| `APPLY_IDLE_TIMEOUT_MINUTES` | no | `30` | A questionnaire step left unanswered this long closes the flow; the draft stays and `/application` resumes it |
| `VOTING_DURATION_MINUTES` | no | `180` | Voting window duration |
| `VOTING_SCAN_INTERVAL_MS` | no | `60000` | How often the scheduler sweeps for expired votings |
| `GROUP_INVITE_TTL_HOURS` | no | `48` | Lifetime of a one-time group invite |
| `APPLICATION_START_LIMIT` / `APPLICATION_START_WINDOW_MS` | no | `3` / `60000` | Rate limit on questionnaire starts per account |
| `VOTE_BUTTON_ICONS_ENABLED` | no | `false` | Custom emoji icons on vote buttons (needs Premium on the bot owner) |
| `VOTE_BUTTON_ICON_ACCEPT_ID` / `VOTE_BUTTON_ICON_REJECT_ID` | no | — | Custom emoji ids for the vote buttons |
| `APPROVAL_MESSAGE_EFFECT_ID` | no | `5104841245759776310` (confetti) | Message effect played on the approval notification |

### Authentication

| Variable | Required | Default | Description |
|---|---|---|---|
| `AUTH_REQUEST_TTL_SECONDS` | no | `75` | One-time login token lifetime (allowed range 60–90) |
| `AUTH_SWEEP_INTERVAL_MS` | no | `1000` | Cadence of the auth sweep: expiry enforcement and undelivered prompt retries |
| `AUTH_RATE_LIMIT_WINDOW_SECONDS` | no | `300` | Sliding window for the auth rate limits below |
| `AUTH_RATE_LIMIT_REQUESTS_PER_PLAYER` | no | `5` | Auth requests per player per window |
| `AUTH_RATE_LIMIT_REQUESTS_PER_TELEGRAM` | no | `10` | Auth requests per Telegram account per window |
| `AUTH_RATE_LIMIT_APPROVALS_PER_TELEGRAM` | no | `30` | Approvals per Telegram account per window |
| `AUTH_MESSAGE_EFFECT_ID` | no | — | Optional Telegram message effect on the auth prompt |

### WebSocket gateway tuning

| Variable | Required | Default | Description |
|---|---|---|---|
| `WS_HEARTBEAT_INTERVAL_MS` | no | `15000` | Expected heartbeat cadence, echoed to the plugin at registration |
| `WS_HEARTBEAT_TIMEOUT_MS` | no | `10000` | Heartbeat tolerance on the backend side |
| `WS_MAX_PACKET_BYTES` | no | `262144` | Hard limit on incoming frame size; larger frames are rejected |
| `WS_REPLAY_WINDOW_MS` | no | `30000` | Timestamp tolerance for signed frames and the handshake |
| `WS_NONCE_TTL_MS` | no | `120000` | How long a used handshake nonce stays burned |
| `WS_DRAIN_TIMEOUT_MS` | no | `5000` | Graceful shutdown: in-flight handler waits get this long before sockets close |

### Notifications and server watch

| Variable | Required | Default | Description |
|---|---|---|---|
| `SERVER_OFFLINE_ALERT_MINUTES` | no | `5` | WebSocket silent this long → the server is presumed offline; admins get a crash alert |
| `NOTIFY_USERS_ON_OFFLINE` | no | `false` | Crash alert also DM-ed to all linked users, not just admins |
| `NOTIFICATION_RATE_PER_SECOND` | no | `30` | Outbound broadcast rate ceiling (Telegram allows ~30/s) |

### Retention

| Variable | Required | Default | Description |
|---|---|---|---|
| `RETENTION_AUDIT_DAYS` | no | `90` | Lifetime of audit and server-event records |
| `RETENTION_SESSION_DAYS` | no | `60` | Lifetime of closed sessions |
| `RETENTION_INITIAL_DELAY_MINUTES` | no | `5` | First cleanup pass after boot, then daily |

Secrets never belong in the repository or in logs — keep them only in the environment.

## Plugin — config.yml

Located at `plugins/ServerCore/config.yml`, generated on first start. The shipped file is intentionally incomplete in two keys: `backend.url` and `backend.secret` are empty, and the plugin refuses to enable until both are filled in. A boot with an unusable `backend` block logs `config error: …` and disables itself instead of running half-connected. Once the plugin is enabled, an unreachable backend degrades gracefully: new logins fail closed and guests keep working from local state.

### backend

| Key | Default | Description |
|---|---|---|
| `backend.url` | — (required) | Full WebSocket URL of the backend gateway, `wss://host:port/path` (`ws://` for local development; TLS terminates at the proxy). Must start with `ws://` or `wss://` |
| `backend.server-id` | `game-1` | Unique id of this game server; must match a key in the backend's `SERVERCORE_SERVER_CREDENTIALS`. Allowed characters: `a-z 0-9 . _ -` |
| `backend.secret` | — (required) | The per-server HMAC secret matching the same key on the backend. At least 32 characters (`openssl rand -hex 32`) |
| `backend.reconnect.initial-delay-seconds` | `2` | First reconnect delay after a disconnect |
| `backend.reconnect.max-delay-seconds` | `120` | Cap for exponential backoff |
| `backend.heartbeat-interval-seconds` | `15` | Interval between heartbeat frames; must be positive |
| `backend.ack-timeout-milliseconds` | `10000` | How long an unacknowledged frame waits before the first retry |
| `backend.ack-max-retries` | `2` | Retries after the first ack timeout; durable frames then park on disk until the link returns, the rest are dropped |
| `backend.liveness-timeout-milliseconds` | `45000` | No inbound frame for this long on a "connected" socket = half-open link, forced reconnect |
| `backend.dedupe-ttl-milliseconds` | `600000` | How long a processed backend push is remembered; a redelivered copy inside the window is acked but never applied twice |
| `backend.outbox-max-entries` | `500` | Hard cap of the durable outbox file (guest lifecycle frames) |

Configs written before the nested `backend.reconnect.*` section existed may still carry the flat `backend.reconnect-initial-seconds` and `backend.reconnect-max-seconds` keys. They remain honored as a fallback when the nested keys are absent; new installs should use the nested form.

### guest

| Key | Default | Description |
|---|---|---|
| `guest.enabled` | `true` | Master switch. When disabled, unauthenticated players are kicked with the register message instead of entering guest mode |
| `guest.restore-on-restart` | `true` | Rebuild guest sessions and restore snapshots after a server restart |
| `guest.resolve-timeout-milliseconds` | `4000` | How long the backend-backed account check may take before the plugin falls back to local state (fail closed to guest) |
| `guest.session-limit` | `3` | Maximum guest sessions per player. Every join without authorization burns one; successful authorization resets the budget |
| `guest.kick-after-limit` | `true` | Past the limit: `true` kicks with a register-now screen, `false` lets the player keep exploring with a loud warning |
| `guest.reminder-interval-seconds` | `60` | Cadence of the subtle actionbar reminder shown while a guest waits for authorization |
| `guest.gamemode-sweep-seconds` | `2` | How often the sweep re-checks that a guest is still in Spectator (another plugin may force a different mode); corrections are throttled so it never becomes a per-tick fight |
| `guest.lang` | `ru` | UI language, picks `locales/<lang>.yml` from the canonical store (`en` and `ru` are bundled) |
| `guest.bot-username` | `ServerCoreBot` | Telegram bot username, used for the fallback deep link when no personal one-time link has been handed out |
| `guest.link-ttl-milliseconds` | `90000` | Personal deep links carry one-time tokens; they stop being shown after this window |
| `guest.snapshot-storage-path` | `guest-snapshots.json` | Durable snapshot storage file, relative to `plugins/ServerCore/` |
| `guest.command-whitelist` | `help, rules, link` | Command names (no slash) a guest may still run. `/link` is answered by the plugin itself with the registration prompt |
| `guest.local-accounts` | — | Optional `uuid: authorized` or `uuid: banned` overrides that work while the backend is down. On this offline-mode server a UUID is only a nickname derivative, so these are convenience bookkeeping — the backend Telegram binding is the identity authority |

### guest.restrictions

Per-guest interaction toggles. Each defaults to `true` (restricted):

`guest.restrictions.block-interaction`, `block-break`, `block-place`, `item-pickup`, `item-use`, `container-interaction`, `entity-damage`, `commands`, `gamemode-change`, `portals`, `inventory-edit`, `chat`

`chat` is full isolation: a guest neither sees nor writes chat, and the suppression runs before other chat plugins see the event. Only free flight and world observation are guaranteed to guests; everything above is denied by design.

### guest messages

Player-facing text lives in one canonical store: `locales/ru.yml` and `locales/en.yml`. Both files ship inside the plugin jar and share the same nested key set — `tablist.*`, `guest.*`, `auth.*`, `account.*`, `spec.*`, `protection.*`, `luckperms.groups.*`, the `placeholders.*` block that feeds the PlaceholderAPI expansion, `bars.*` (bar titles and toggle feedback), `diag.*` (the `/servercore diag` card) and `countdown.*`. `guest.lang` picks the active language; a key missing from it falls back to the English file, and a key missing everywhere renders as the key itself.

The backend pushes the canonical `ru` bundle as a full file over the WebSocket (`LOCALE_PUSH`); the plugin persists it to `plugins/ServerCore/locales/ru.yml`, and that persisted copy wins over the jar seed on boot. The same channel accepts `en`, but the backend currently serves only `ru` — the English file travels in the plugin jar as the base layer.

Templates are MiniMessage with the plugin's theme tags only (`<primary>`, `<secondary>`, `<muted>`, `<gold>`, `<ok>`, `<warn>`, `<danger>`, `<info>`); vanilla color names are rejected by a build test so the palette stays in one place. Values arrive as `{name}` placeholders and are escaped before substitution. `placeholders.*` is the exception: those values are plain legacy text for PlaceholderAPI and carry no tags.

### anti-esp

| Key | Default | Description |
|---|---|---|
| `anti-esp.enabled` | `true` | Master switch for the visibility layer |
| `anti-esp.max-distance` | `48` | Beyond this distance targets are not evaluated (clients cannot see that far anyway) |
| `anti-esp.check-interval-ticks` | `10` | Base evaluation cadence per viewer; movement may trigger earlier re-evaluation |
| `anti-esp.sneak-hides` | `true` | Players currently sneaking resolve to `HIDDEN` for observers without line of sight |
| `anti-esp.disguise.enabled` | `true` | Replace hidden players with a per-viewer synthetic entity instead of removing them outright |
| `anti-esp.disguise.mobs` | `zombie, skeleton, spider, cow, pig, sheep` | Candidate disguise types, chosen randomly per pair. Comma-separated Bukkit entity type names; unknown names are dropped with a log line, and an empty result falls back to the default set (a YAML list is ignored by the loader) |
| `anti-esp.cache.ttl-ticks` | `40` | How long a line-of-sight result is reused before re-evaluation |

### spec (hidden observation)

| Key | Default | Description |
|---|---|---|
| `spec.enabled` | `true` | Master switch for `/spec` |
| `spec.live-inventory` | `true` | Real-time read-only inventory viewer while observing |
| `spec.live-inventory-push-milliseconds` | `500` | Minimum gap between live inventory view updates |
| `spec.live-inventory-full-resync-seconds` | `5` | Periodic full re-sync of the live view; catches changes no event reported (e.g. plugin-side inventory edits) |
| `spec.log-to-audit` | `true` | Every session start/end is written to `audit_logs` (backend) and the local audit trail |
| `spec.permission.use-luckperms` | `true` | LuckPerms is the permission backend when installed, vanilla checks otherwise |
| `spec.permission.levels` | `[owner, admin, moderator, helper]` | Access levels, highest first: membership in a listed group grants that level |
| `spec.permission.spec-permission` | `servercore.spec` | The standalone node that also grants access |
| `spec.permission.fallback-level` | `helper` | Level index granted by `spec-permission` alone when no listed group matches |
| `spec.audit.directory` | `audit` | Local append-only audit trail (jsonl), relative to `plugins/ServerCore/`; files rotate daily as `spec-audit-YYYY-MM-DD.jsonl` |
| `spec.audit.retention-days` | `30` | Rotated files older than this are deleted |
| `spec.audit.max-entries-per-session` | `64` | Audit rows allowed per single session, overflow is dropped |
| `spec.audit.queue-capacity` | `1024` | Bounded write buffer; a full buffer drops entries instead of blocking |
| `spec.hidden.trace-radius` | `1.5` | How a trace packet (particle/sound aimed at the observed player) gets attributed to the watching admin, by proximity. Keep small — a bigger radius starts eating the target's own effects |
| `spec.hidden.suppress-action-bar` | `true` | Drop every actionbar line toward an observed player during a session. Actionbar packets carry no origin, so this is all-or-nothing per session |

### protection

| Key | Default | Description |
|---|---|---|
| `protection.enabled` | `true` | Master switch for the whole stack: command denial, gamemode guard, command block audit |
| `protection.blocked-commands` | built-in list | Command names players may never run, op or not. The list ships with the full baseline in `config.yml`; if the key is deleted entirely the built-in baseline applies, an explicitly empty list disables the block. Aliases are resolved against the live command tree (one name covers its whole alias group), namespace prefixes are ignored when matching |
| `protection.blocked-datapack-commands` | `function, schedule, datapack` | Commands that hand execution to datapack code — a function can carry any blocked command inside, so its invocation paths are denied for players too |
| `protection.enforce-gamemode` | `true` | Non-survival gamemodes are only allowed through sanctioned ServerCore flows (guest sessions, `/spec`, the override registry); everything else is cancelled and drifted modes are reverted by a periodic sweep |
| `protection.gamemode-sweep-interval-seconds` | `30` | How often the drift sweep runs; `0` = off |
| `protection.command-block-audit-interval-seconds` | `600` | How often loaded chunks are scanned for command blocks carrying a denied command (no execution event exists for blocks); `0` = audit off. Chunk load events are always audited |
| `protection.command-block-neutralize` | `false` | Clear a denied command from the block itself, not only log it |
| `protection.hide-from-tab-complete` | `true` | Strip denied commands from the client command list (tab completion) — display only, enforcement happens at execution time |

### luckperms.fallback

Permissions live in LuckPerms (soft-depend). When LuckPerms is installed on the server, everything in this section is ignored — groups and nodes are managed through LuckPerms itself. The section is only read without LuckPerms: a static, config-driven stand-in so the plugin still boots and admins keep access. It is never a replacement for real group management.

| Key | Default | Description |
|---|---|---|
| `luckperms.fallback.default-group` | `default` | Group handed to every player the static map knows nothing about |
| `luckperms.fallback.groups` | — | Static group overrides, `uuid: group name` (rank ordering only) |
| `luckperms.fallback.permissions.everyone` | `[]` | Nodes granted to every player |
| `luckperms.fallback.permissions.players` | — | Per-player nodes: exact nodes or prefix wildcards (`servercore.*`) |

### flectonepulse

FlectonePulse is the moderation and chat authority on this server; ServerCore never issues bans/mutes/warns/kicks, never formats chat and never ships prefixes — it only reads the public FlectonePulse API and consults punishment state when a decision needs it. FlectonePulse must be installed for any of this to be active.

| Key | Default | Description |
|---|---|---|
| `flectonepulse.disable-tg` | `true` | De-duplication: FlectonePulse ships its own Telegram module, and when that module is active it is the one announcing events — ServerCore then keeps its ops notifications out of Telegram so nothing is announced twice. `false` = ServerCore announces regardless |
| `flectonepulse.punishment-cache-ttl-seconds` | `30` | How long a punishment lookup answer stays cached; invalidated early by FlectonePulse player events, so this is just the safety net |

### ops

Ops knobs: self-update from GitHub releases, scheduled restarts, and the Pterodactyl panel integration. Secrets never go in this file — the panel API key and the GitHub token are read from the environment variables named below.

| Key | Default | Description |
|---|---|---|
| `ops.update.enabled` | `true` | Self-update from GitHub releases (tags `build-*`) |
| `ops.update.repo` | `bejiihiu/ServerCore` | Owner/repo to poll for the latest `build-*` release |
| `ops.update.check-interval-minutes` | `30` | How often to check |
| `ops.update.auto-restart` | `true` | Restart automatically after a verified swap; `false` = install and wait for a manual/scheduled restart |
| `ops.update.github-token-env` | `GITHUB_TOKEN` | Env var holding a GitHub token (optional, lifts the API rate limit) |
| `ops.restart.pterodactyl.panel-url` | — | Base url, e.g. `https://panel.example.com`. All three pterodactyl values empty = feature off; restarts then fall back to a console `stop` (which only acts like a restart when the panel has AUTO-RESTART enabled) |
| `ops.restart.pterodactyl.server-id` | — | Identifier of this server in the panel (the short id from the url) |
| `ops.restart.pterodactyl.api-key-env` | `PTERO_API_KEY` | Env var with a Pterodactyl **client** API key (`ptlc_…`) |
| `ops.restart.daily-times` | `["05:00"]` | Daily restart times, wall clock `hh:mm` |
| `ops.restart.grace-seconds` | `60` | Seconds of in-game warning (title + actionbar countdown) before kick |
| `ops.restart.locale` | `ru` | Player-facing texts: `ru` / `en` |
| `ops.restart.timezone` | `Europe/Moscow` | Timezone for `daily-times` and logs (any Java zone id) |

### placeholders

| Key | Default | Description |
|---|---|---|
| `placeholders.cache-ttl-milliseconds` | `2000` | How long a rendered placeholder value is memoized before it is recomputed. Values are always served from the in-memory state cache — no synchronous database access |
| `placeholders.unknown-value` | `unknown` | String served for a player this server has never seen |
| `placeholders.stale-after-minutes` | `10` | While the backend link is down, backend-derived facts older than this are flagged as stale |
| `placeholders.stale-suffix` | ` (stale)` | Suffix appended to stale backend-derived values |

Provided placeholders (read-only, fed by websocket events and player lifecycle):

| Placeholder | Meaning |
|---|---|
| `%servercore_status%` | Account state: active, banned or guest |
| `%servercore_authenticated%` | Whether the backend confirmed the Telegram login |
| `%servercore_application_status%` | Last known admission verdict (voting, approved, rejected) |
| `%servercore_telegram_linked%` | Whether the account is bound to a Telegram identity |
| `%servercore_last_seen%` | Relative time of the last join/quit |
| `%servercore_session_duration%` | Live session time of an online player |
| `%servercore_bar_ram%` | Heap fill percent from the RAM bar's last sample |
| `%servercore_bar_tps%` | TPS from the last bar sample: the asking player's region, else the server aggregate |
| `%servercore_bar_mspt%` | Region tick time from the last bar sample |
| `%servercore_bar_players%` | Online player count from the players bar |
| `%servercore_bar_net%` | Answering/known servers from the network bar, e.g. `3/3` |

Bar placeholders read the same snapshot layer the bars render from and answer with `placeholders.unknown-value` when nothing has been sampled yet. Details in [server info bars](bars.md#placeholderapi).

### bars

Live server health as a per-player toggle. Full reference (commands, Folia scope, placeholder examples) in [server info bars](bars.md).

| Key | Default | Description |
|---|---|---|
| `bars.enabled` | `true` | Master switch: off = no rendering, no sampling, no `/bar` commands |
| `bars.update-interval-ticks` | `20` | Refresh cadence in ticks (`20` = 1 s), clamped `1–200` |
| `bars.display` | `bossbar` | Render surface: `bossbar` or `actionbar`; an unknown value falls back to `bossbar` |
| `bars.ram.enabled` | `true` | `/rambar` on |
| `bars.ram.good` / `bars.ram.medium` | `60` / `80` | Heap fill percent edges for healthy / critical |
| `bars.tps.enabled` | `true` | `/tpsbar` on |
| `bars.tps.good` / `bars.tps.medium` | `18.0` / `15.0` | TPS edges (higher is better) |
| `bars.mspt.enabled` | `true` | `/msptbar` on |
| `bars.mspt.good` / `bars.mspt.medium` | `40.0` / `50.0` | Region tick time edges in ms (lower is better) |
| `bars.players.enabled` | `true` | `/playersbar` on |
| `bars.world.enabled` | `true` | `/worldbar` on |
| `bars.world.show-weather` | `true` | `false` hides the weather word, keeps world and clock |
| `bars.net.enabled` | `true` | `/netbar` on (needs the backend link for the fleet view) |
| `bars.net.stale-after-ms` | three heartbeat intervals | Heartbeat age that counts a server as offline, clamped `5000–600000` |

Threshold pairs are put back in the right order with a log line when an admin swaps them; the config is never refused for a bad threshold.

### Validation and clamping

The config is validated once at boot. These problems abort the boot with a `config error: <what>` log line and disable the plugin (fix the value, restart):

- `backend.url` empty or not `ws://` / `wss://`; `backend.server-id` outside `a-z 0-9 . _ -`; `backend.secret` shorter than 32 characters
- `backend.heartbeat-interval-seconds`, `ack-timeout-milliseconds`, `liveness-timeout-milliseconds`, `dedupe-ttl-milliseconds`, `outbox-max-entries` not positive; `ack-max-retries` negative
- `spec.live-inventory-push-milliseconds` outside `50–10000`; `spec.live-inventory-full-resync-seconds` below the push interval
- `spec.audit.directory` empty or containing `..`

Everything else degrades instead of failing the boot:

- **Clamped** to a safe band: `anti-esp.max-distance` `8–128`, `check-interval-ticks` `1–200`, `cache.ttl-ticks` `1–2000`, `spec.hidden.trace-radius` `0.5–16`, `guest.resolve-timeout-milliseconds` `250–30000`, `session-limit` `1–100`, `reminder-interval-seconds` `10–3600`, `gamemode-sweep-seconds` `1–60`, `link-ttl-milliseconds` `5000–600000`, `bars.update-interval-ticks` `1–200`, `bars.net.stale-after-ms` `5000–600000`, audit `retention-days`/`max-entries-per-session`/`queue-capacity` (below 1 / 1 / 16), `flectonepulse.punishment-cache-ttl-seconds` `1–3600`, `ops.restart.grace-seconds` `0–600`, `ops.update.check-interval-minutes` at least `1`
- **Dropped with a log line**: unknown `anti-esp.disguise.mobs` names, malformed `ops.restart.daily-times` entries, non-UUID keys in `guest.local-accounts` / `luckperms.fallback.groups` / `luckperms.fallback.permissions.players`, blank group names
- **Fallen back**: an unknown `ops.restart.timezone` becomes `Europe/Moscow`; a `spec.permission.fallback-level` that is not one of `spec.permission.levels` becomes the lowest listed level; an empty `guest.command-whitelist` becomes `help, rules, link`; a missing `ops.restart.daily-times` becomes `["05:00"]` (an explicit `[]` still means no scheduled restart); an unknown `bars.display` becomes `bossbar`; a missing `bars.net.stale-after-ms` becomes three heartbeat intervals
- **Ignored without side effects**: every optional plugin section (`luckperms`, `placeholders`, `flectonepulse`) may be missing entirely; `anti-esp.enabled: false` parks both the visibility engine and the packet layer, and `spec.enabled: false` simply skips the `/spec` stack. The plugin still boots.

## Plugin commands

| Command | Access | Description |
|---|---|---|
| `/spec` | `spec.permission.*` policy (LuckPerms groups or the standalone node) | Leave the current observation session |
| `/spec <player>` | same | Start a hidden observation session on a player; tab completion suggests online names |
| `/servercore groups <player>` | `servercore.admin` (console always allowed) | Debug output listing a player's groups from the active permission source |
| `/servercore deop-all` | `servercore.admin` (console always allowed) | Remove operator status from every account holding it |
| `/servercore diag [--json]` | `servercore.admin` (console always allowed) | Operator self-check: build, module matrix, backend link, live counts, integration versions, verdict and up to three hints. `--json` prints one machine-readable document instead of the card |
| `/rambar`, `/tpsbar`, `/msptbar`, `/playersbar`, `/worldbar`, `/netbar` | `servercore.bars.<bar>` for yourself, `…​.other` to target others | Toggle a server info bar; see [server info bars](bars.md) |
| `/link` | guests | Not a registered command — a guest typing `/link` gets the registration prompt in chat instead of "unknown command" |

The Telegram bot's command set is separate and lives entirely in the backend: `/start`, `/status`, `/application`, `/myapplication`, `/cancel`, `/language`, `/help`, plus the admin-only `/stats` (restricted to `ADMIN_TELEGRAM_IDS`).

## Configuration principles

- **Fail closed.** If the backend is unreachable, new logins are refused — never auto-approved.
- **Secrets stay out of the repo.** The `backend.secret` and all tokens exist only on the hosts that need them.
- **Least privilege.** Plugin permissions mirror LuckPerms groups; nothing is granted "just in case".
- **Restart-safe.** Guest snapshots are persisted by the plugin locally (and mirrored to the backend), and session state lives in MongoDB — both survive restarts and backend outages.
