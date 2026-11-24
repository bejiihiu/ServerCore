# Configuration

ServerCore is configured in two places: the backend's environment file and the plugin's `config.yml`. This page documents every option.

## Backend — environment variables

All variables come from `.env` (copy `.env.example` as the starting point). Placeholder names below are the real keys.

### Database

| Variable | Required | Default | Description |
|---|---|---|---|
| `DATABASE_URI` | yes | — | MongoDB connection string, including credentials and database name |
| `DATABASE_NAME` | yes | `servercore` | Database name. Must match the name embedded in `DATABASE_URI` |

### Telegram bot

| Variable | Required | Default | Description |
|---|---|---|---|
| `BOT_TOKEN` | yes | — | Token issued by [@BotFather](https://t.me/BotFather) |
| `TELEGRAM_BOT_USERNAME` | no | `ServerCoreBot` | Bot username used in deep links |
| `GROUP_CHAT_ID` | no | — | Numeric chat ID of the closed community group (voting + membership). Empty disables the group flow |
| `ADMIN_CHAT_ID` | no | — | Chat that receives duplicates of key events (approvals, anomalies, expired votes) |
| `DEFAULT_LANGUAGE` | no | `ru` | Interface language for group and admin messages (`ru` / `en`) |

### WebSocket gateway

| Variable | Required | Default | Description |
|---|---|---|---|
| `WS_HOST` | yes | `0.0.0.0` | Bind address for the gateway |
| `WS_PORT` | yes | `8443` | Port the plugin connects to |
| `WS_PATH` | no | `/gateway` | WebSocket endpoint path |
| `WS_MAX_PAYLOAD_BYTES` | no | `65536` | Hard limit on incoming message size; larger frames are rejected |
| `SERVER_SECRET_<ID>` | yes (per server) | — | Per-server HMAC secret, shared with that server's plugin config. One entry per registered Minecraft server |

### Runtime behaviour

| Variable | Required | Default | Description |
|---|---|---|---|
| `LOG_LEVEL` | no | `info` | Pino level: `trace`, `debug`, `info`, `warn`, `error`, `fatal` |
| `APPLICATION_QUESTIONS_RU` | no | spec defaults | Questionnaire questions (Russian), json array of 1–10 strings |
| `APPLICATION_QUESTIONS_EN` | no | spec defaults | Questionnaire questions (English), must match the RU list length |
| `VOTING_DURATION_MINUTES` | no | `180` | Voting window duration |
| `VOTING_SCAN_INTERVAL_MS` | no | `60000` | How often the scheduler sweeps for expired votings |
| `GROUP_INVITE_TTL_HOURS` | no | `48` | Lifetime of a one-time group invite |
| `VOTE_BUTTON_ICONS_ENABLED` | no | `false` | Custom emoji icons on vote buttons (needs Premium on the bot owner) |
| `VOTE_BUTTON_ICON_ACCEPT_ID` / `VOTE_BUTTON_ICON_REJECT_ID` | no | — | Custom emoji ids for the vote buttons |
| `APPROVAL_MESSAGE_EFFECT_ID` | no | confetti | Message effect played on the approval notification |
| `APPLICATION_START_LIMIT` / `APPLICATION_START_WINDOW_MS` | no | `3` / `60000` | Rate limit on questionnaire starts per account |
| `AUTH_REQUEST_TTL_SECONDS` | no | `75` | One-time login token lifetime (allowed range 60–90) |

Secrets never belong in the repository or in logs — keep them only in the environment.

## Plugin — config.yml

Located at `plugins/ServerCore/config.yml`, generated on first start. The plugin stays inert until the `backend` section is filled in.

### backend

| Key | Default | Description |
|---|---|---|
| `backend.url` | — | Full WebSocket URL of the backend gateway, `wss://…` |
| `backend.secret` | — | The per-server HMAC secret matching `SERVER_SECRET_<ID>` on the backend |
| `backend.reconnect.initial-delay-seconds` | `2` | First reconnect delay after a disconnect |
| `backend.reconnect.max-delay-seconds` | `120` | Cap for exponential backoff |
| `backend.heartbeat-interval-seconds` | `15` | Interval between heartbeat frames |

### guest

| Key | Default | Description |
|---|---|---|
| `guest.enabled` | `true` | Master switch. When disabled, unauthenticated players are kicked with the register message instead of entering guest mode |
| `guest.restore-on-restart` | `true` | Rebuild guest sessions and restore snapshots after a server restart |
| `guest.resolve-timeout-milliseconds` | `4000` | How long the backend-backed account check may take before the plugin falls back to local state (fail closed to guest) |
| `guest.session-limit` | `3` | Maximum guest sessions per player. Every join without authorization burns one; successful authorization resets the budget |
| `guest.kick-after-limit` | `true` | Past the limit: `true` kicks with a register-now screen, `false` lets the player keep exploring with a loud warning |
| `guest.reminder-interval-seconds` | `60` | Cadence of the subtle actionbar reminder shown while a guest waits for authorization |
| `guest.lang` | `ru` | UI language, selects `messages_<lang>.yml` from the plugin data folder (`en` and `ru` are bundled) |
| `guest.bot-username` | `ServerCoreBot` | Telegram bot username, used for the fallback deep link when no personal one-time link has been handed out |
| `guest.link-ttl-milliseconds` | `90000` | Personal deep links carry one-time tokens; they stop being shown after this window |
| `guest.snapshot-storage-path` | `guest-snapshots.json` | Durable snapshot storage file, relative to `plugins/ServerCore/` |
| `guest.command-whitelist` | `help, rules, link` | Command names (no slash) a guest may still run. `/link` is answered by the plugin itself with the registration prompt |
| `guest.local-accounts` | — | Optional `uuid: authorized\|banned` overrides that work while the backend is down. On this offline-mode server a UUID is only a nickname derivative, so these are convenience bookkeeping — the backend Telegram binding is the identity authority |

### guest.restrictions

Per-guest interaction toggles. Each defaults to `true` (restricted):

`guest.restrictions.block-interaction`, `block-break`, `block-place`, `item-pickup`, `item-use`, `container-interaction`, `entity-damage`, `commands`, `gamemode-change`, `portals`, `inventory-edit`, `chat`

`chat` is full isolation: a guest neither sees nor writes chat, and the suppression runs before other chat plugins see the event. Only free flight and world observation are guaranteed to guests; everything above is denied by design.

### guest messages

Player-facing text lives in `messages_en.yml` / `messages_ru.yml` (bundled, then copied to the plugin data folder on first start where admins can edit them). Missing keys fall back to the bundled English file. Keys use the `guest.*`, `auth.*` naming and support MiniMessage tags.

### anti-esp

| Key | Default | Description |
|---|---|---|
| `anti-esp.enabled` | `true` | Master switch for the visibility layer |
| `anti-esp.max-distance` | `48` | Beyond this distance targets are not evaluated (clients cannot see that far anyway) |
| `anti-esp.check-interval-ticks` | `10` | Base evaluation cadence per viewer; movement may trigger earlier re-evaluation |
| `anti-esp.sneak-hides` | `true` | Players currently sneaking resolve to `HIDDEN` for observers without line of sight |
| `anti-esp.disguise.enabled` | `true` | Replace hidden players with a per-viewer synthetic entity instead of removing them outright |
| `anti-esp.disguise.mobs` | `zombie, skeleton, spider, cow, pig, sheep` | Candidate disguise types, chosen randomly per pair |
| `anti-esp.cache.ttl-ticks` | `40` | How long a line-of-sight result is reused before re-evaluation |

### spec (hidden observation)

| Key | Default | Description |
|---|---|---|
| `spec.enabled` | `true` | Master switch for `/spec` |
| `spec.live-inventory` | `true` | Real-time read-only inventory viewer while observing |
| `spec.log-to-audit` | `true` | Every session start/end is written to `audit_logs` |

### protection

| Key | Default | Description |
|---|---|---|
| `protection.blocked-commands` | built-in list | Extra command names to block for all game accounts. The built-in list (`/op`, `/deop`, `/fill`, `/clone`, `/setblock`, `/summon`, `/gamemode`, `/tp`, `/teleport`, `/give`, `/item`, `/execute`, `/effect`, `/attribute`, `/kill`, `/difficulty`, `/time`, `/weather`, `/gamerule`, `/worldborder`, `/stop`, `/reload`) always applies and cannot be removed here |
| `protection.enforce-gamemode` | `true` | Force players back to Survival when a non-sanctioned gamemode change is detected |

### placeholders

| Key | Default | Description |
|---|---|---|
| `placeholders.cache-ttl-milliseconds` | `2000` | Freshness window of the local cache behind PlaceholderAPI expansions. Values are served from cache only — no synchronous database access |

## Configuration principles

- **Fail closed.** If the backend is unreachable, new logins are refused — never auto-approved.
- **Secrets stay out of the repo.** The `backend.secret` and all tokens exist only on the hosts that need them.
- **Least privilege.** Plugin permissions mirror LuckPerms groups; nothing is granted "just in case".
- **Restart-safe.** Guest snapshots and session state are persisted in MongoDB and survive both server restarts and backend outages.
