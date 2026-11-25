# First run

**Languages:** English | [Русский](ru/first-run.md)

A short operator checklist for bringing ServerCore up for the first time: one backend, one Folia server,
one plugin. The long version with every option is [installation.md](installation.md); every key is in
[configuration.md](configuration.md).

## 0. What you need

| Thing | Required | Version / note |
|---|---|---|
| Folia server | **yes** | **26.3 or newer** — the plugin declares `api-version: 26.3` and `folia-supported: true`. Paper is not supported |
| Java | **yes** | **25** — the plugin is compiled for a Java 25 toolchain and will not load on an older runtime |
| PacketEvents | **yes** | **2.14.x** — a hard plugin dependency (`required: true`); the whole packet layer (anti-ESP, disguises, live inventory) rides on it and the plugin refuses to enable without it |
| Bun + MongoDB | **yes** (backend) | Bun 1.4+ (`package.json` engines), MongoDB 7.x or newer |
| Telegram bot | recommended | A token from [@BotFather](https://t.me/BotFather) with privacy mode **disabled**; without a token the backend runs bot-less (`bot is disabled`) |
| LuckPerms | recommended | Permissions authority. Without it the plugin falls back to the static `luckperms.fallback.*` map — it boots, but that is not a group manager |
| PlaceholderAPI | optional | Only exposes `%servercore_…%` to other plugins; absent = placeholders absent |
| FlectonePulse | optional | Chat/moderation authority, 1.15.x API. Absent = no punishment lookups |

## 1. Put the files in place

| Release asset | Goes to |
|---|---|
| `ServerCore-<tag>.jar` | the server's `plugins/` directory |
| `servercore-backend-<tag>.tar.gz` | any directory on the backend host — unpack it (`tar -xzf …`) |
| `SHA256SUMS.txt` | keep next to the two above and run `sha256sum -c SHA256SUMS.txt` first |

`<tag>` is the build id, `build-<commitCount>-<shortSha>`. There is no semantic version.

## 2. Backend

Minimum that actually works (defaults cover the rest — see [configuration.md](configuration.md)):

```env
MONGODB_URI=mongodb://127.0.0.1:27017/servercore
BOT_TOKEN=123456789:AA…                 # from BotFather
GROUP_CHAT_ID=-1001234567890            # closed community group
SERVERCORE_SERVER_CREDENTIALS={"game-1":"<openssl rand -hex 32>"}
```

- The key in `SERVERCORE_SERVER_CREDENTIALS` must be the plugin's `backend.server-id`; the secret must be the
  plugin's `backend.secret` and at least 32 characters. This pair is the only trust anchor between the two.
- Leave `WS_PORT` at `8081` unless something else already listens there. The plugin talks to
  `<scheme>://<host>:<WS_PORT>/ws` — the gateway returns `404` on every other path.
- Start it: `bun install --frozen-lockfile --production && bun run start`. Collections and indexes are
  created on first start.

Expected log lines, in order (`bot polling started` is asynchronous and may land a moment later):

```text
mongodb connected
websocket gateway listening     { port: 8081 }
admission indexes synced        (or a warning if mongo refused index creation)
http listening                  { port: 8080, host: '0.0.0.0' }
bot polling started             { username: '…' }
servercore backend started
```

Check the health endpoint — `200` with `"status":"ok"` means Mongo is up and `connectedServers` is the list
of game servers currently linked:

```bash
curl -s http://127.0.0.1:8080/health
```

`503` / `"degraded"` means Mongo is not reachable: the process stays up on purpose, but nothing will work
until it is.

## 3. Plugin

1. Drop `ServerCore-<tag>.jar` and PacketEvents into `plugins/`, then start the server **once**.
2. The plugin writes `plugins/ServerCore/config.yml` and disables itself. That is the expected first boot:
   `backend.url` and `backend.secret` are empty and an unusable backend block is a boot blocker. Look for:

   ```text
   config error: backend.url is empty: set the backend gateway websocket url (ws:// or wss://)
   ```

3. Edit only the `backend` block to start with:

   ```yaml
   backend:
     url: "wss://mc-api.example.com:8081/ws"   # ws:// for a local backend on the same host
     server-id: "game-1"                       # matches the SERVERCORE_SERVER_CREDENTIALS key
     secret: "<the same 32+ char secret>"
   ```

   `guest.enabled`, `anti-esp.enabled`, `spec.enabled` and `protection.enabled` all default to `true`, so a
   backend-only edit gives the full stack.
4. Restart. The log should show, in this order:

   ```text
   backend gateway connected as game-1
   servercore build-<n>-<sha> enabled (guest mode: on, flectonepulse: not_present)
   ```

   `flectonepulse:` reads `not_present`, `incompatible`, `connecting` or `connected` — it is a read-only
   integration state, not an error.

## 4. Verify the link

Both sides must agree; check both.

| Where | Signal |
|---|---|
| Backend log | `game server connected, awaiting hello`, then `server state changed { state: 'STARTING' \| 'RUNNING' }` |
| Backend `/health` | the server id appears in `connectedServers` |
| Plugin log | `backend gateway connected as game-1`; after a player joins, `player joined` appears on the backend |

If the plugin instead logs `backend gateway disconnected, reconnect scheduled` on a loop, the handshake is
being rejected before the socket exists. The backend log has the reason right next to
`gateway handshake rejected`:

| Reason | What is wrong |
|---|---|
| `unknown server id` | `backend.server-id` has no matching key in `SERVERCORE_SERVER_CREDENTIALS` |
| `signature mismatch` | the secrets differ between plugin and backend |
| `timestamp outside replay window` | the two hosts' clocks are more than 30 s apart (NTP) |
| `missing handshake fields` | `backend.url` does not point at the gateway path, or a proxy stripped the query string |

### Ask the plugin before the log

`/servercore diag` answers "why isn't it working?" in one card:

- **build** — plugin version, the jar it was loaded from, server version, Folia yes/no, Java version;
- **modules** — every subsystem as *enabled*, *disabled by config*, *absent dependency* or *failed*:
  guest, packet layer, anti-ESP, protection, spec, bars, LuckPerms, PlaceholderAPI, FlectonePulse;
- **backend link** — connected or not, age of the last heartbeat, outbox depth, the last disconnect
  reason and the reconnect attempt count;
- **counts** — active guests, live spec sessions, enabled bars, online players;
- **versions** — PacketEvents, LuckPerms, PlaceholderAPI and FlectonePulse;
- **verdict** — `OK`, `WARN` or `FAIL`, the findings behind it and the top three things to do next.

Console gets plain lines, a player gets the framed card. `/servercore diag --json` prints the same report
as one machine-readable document (stable keys, module statuses and finding codes as constant names) — paste
it into a ticket instead of screenshots.

## 5. Verify end to end

1. Join with a fresh account → spectator guest mode, plus a personal Telegram deep link in chat.
2. Open the link, `/start` the bot, confirm the linkage (`link token issued for <name>` on the plugin side).
3. Fill out the application → the group receives the vote (`player joined`, `guest session started` are the
   expected background lines).
4. After quorum, accept the single-use group invite, reconnect, and confirm the login prompt in Telegram.
5. Backend `audit_logs` and `server_events` start filling in; `/stats` in Telegram answers for admins listed
   in `ADMIN_TELEGRAM_IDS`.

## 6. Where state and logs live

| What | Where |
|---|---|
| Plugin config | `plugins/ServerCore/config.yml` |
| Plugin state | `plugins/ServerCore/` — `guest-snapshots.json` (original player state), `guest-session-counts.json` (guest budget), `account-cache.json` (last known account verdicts), `ws-outbox.json` (frames parked while the link is down), `spec-restore-journal.json` (staff state to restore), `locales/ru.yml` (the locale bundle the backend pushed) |
| Observation audit trail (read-only `/spec` sessions) | `plugins/ServerCore/audit/spec-audit-YYYY-MM-DD.jsonl`, rotated daily, 30-day retention (`spec.audit.*`) |
| Plugin logs | the server's own `logs/latest.log` — the plugin logs through the server logger |
| Updater staging / backups | `plugins/ServerCore/.updates/`, `plugins/ServerCore/.backup/` (last three jars) |
| Backend audit + events | MongoDB collections `audit_logs` and `server_events` (90-day retention by default) |
| Backend logs | stdout of the process, Pino JSON with Europe/Moscow timestamps (`LOG_LEVEL`, `LOG_TIMEZONE`) |
| Health | `GET /health` on `PORT` (8080) |

## 7. Known first-run traps

- **Something is wrong and the log is long.** Run `/servercore diag` first: it names the module that is off
  (`disabled by config`, `absent dependency` or `failed`), the link state with its outbox depth, and the top
  three hints. `/servercore diag --json` is the same report for a ticket.
- **No OP anywhere.** Operator status is not an administration mechanism here; permissions come from
  LuckPerms (`servercore.admin`, `servercore.spec`, …). `/servercore deop-all` and `/servercore groups` are
  console-allowed.
- **`guest.enabled: false`** does not let people in — it kicks unauthenticated joins with the register
  message instead of giving them spectator guest mode.
- **Protected commands are blocked for everyone**, operators included (`protection.blocked-commands`).
- **The updater is on by default** (`ops.update.enabled: true`, `ops.update.repo` pointing at this
  repository). A fresh install therefore polls GitHub releases every 30 minutes; it only installs a build
  whose commit count is higher than the running one.
- **`.env` never travels with a bundle.** Every unpack needs its own `cp .env.example .env` and edit.
