# Installation

**Languages:** English | [Русский](ru/installation.md)

This guide walks through a full deployment: MongoDB, the backend, the Telegram bot, and the Folia server with the plugin. All commands are examples — adjust paths and names to your environment.

> **Version note.** There is no semantic version and no `v1.0.0`-style release: every build is
> `build-<commitCount>-<shortSha>`, and that tag is the release name. If the
> [Releases](https://github.com/bejiihiu/ServerCore/releases) page has no tagged build yet, deploy the
> backend from source and build the plugin jar locally; everything else below is identical.
>
> In a hurry? [First run](first-run.md) is the short operator checklist; this page is the full walkthrough.
>
> Want the plumbing already written? The [deployment kit](../deploy/README.md) ships a Docker Compose stack,
> a hardened systemd unit and a Pterodactyl egg, each with numbered steps and verification checkpoints.

## Prerequisites

- A Linux host (or container) for the backend
- A Folia server, **version 26.3 or newer**
- **Java 25** installed for the Minecraft server
- **[Bun](https://bun.sh)** installed for the backend — Node.js is not used
- **MongoDB 7.x or newer** reachable from the backend
- A Telegram account to create the bot

## 1. MongoDB

Provision a MongoDB instance and a dedicated database. A replica set is recommended on production so transactions and change streams behave predictably.

```js
// in mongosh, once connected
use servercore

// optional dedicated application user
db.createUser({
  user: "servercore",
  pwd: "CHANGE_ME_STRONG_PASSWORD",
  roles: [ { role: "readWrite", db: "servercore" } ]
})
```

Notes:

- Collections and indexes are created automatically on backend first start.
- TTL indexes are used for transient data (auth requests and similar). Do not disable them.
- Back up the database regularly — it is the single source of truth for accounts, votes and audit history.
  On Atlas M0 there is no automated backup you can rely on; the scripts and the restore runbook are in
  [backup-restore.md](backup-restore.md).

## 2. Telegram bot

1. Open [@BotFather](https://t.me/BotFather) and run `/newbot`. Save the token it gives you.
2. Disable privacy mode so the bot can observe group events: `/setprivacy` → select your bot → **Disable**. This is required for join/leave confirmation in the private community group.
3. Create the private community group, add the bot to it, and grant it admin rights (invite management and member removal).
4. Note the group's chat ID (you can obtain it by sending a message to the group and checking the backend log, or via any standard bot API helper).

## 3. Backend

Install from Releases (recommended) or from source.

### From a release bundle

Download the latest backend bundle from [GitHub Releases](https://github.com/bejiihiu/ServerCore/releases) —
`servercore-backend-<tag>.tar.gz`, where `<tag>` is the build id, for example
`servercore-backend-build-1234-a1b2c3d.tar.gz`. Verify it against the release's `SHA256SUMS.txt`, unpack it
and run it with Bun:

```bash
sha256sum -c SHA256SUMS.txt        # run inside the directory holding the downloaded files
mkdir servercore-backend
tar -xzf servercore-backend-*.tar.gz -C servercore-backend
cd servercore-backend
cp .env.example .env
# edit .env — see the table below and docs/configuration.md
bun install --frozen-lockfile --production
bun run start
```

The bundle carries the source, `package.json`, `bun.lock`, `.env.example` and a `Dockerfile`, so the same
archive also builds a container image:

```bash
docker build -t servercore-backend .
docker run --rm -p 8080:8080 -p 8081:8081 --env-file .env servercore-backend
```

### From source

```bash
git clone https://github.com/bejiihiu/ServerCore.git
cd ServerCore/backend
cp .env.example .env
# edit .env
bun install
bun run start
```

### Environment file

Every variable is documented in [configuration.md](configuration.md). A minimal production `.env` looks like this (values are placeholders — generate your own):

```env
MONGODB_URI=mongodb://servercore:CHANGE_ME_STRONG_PASSWORD@127.0.0.1:27017/servercore
BOT_TOKEN=123456789:AA-placeholder-from-botfather
GROUP_CHAT_ID=-1001234567890
WS_PORT=8081
SERVERCORE_SERVER_CREDENTIALS={"game-1":"replace-with-long-random-secret"}
LOG_LEVEL=info
```

Generate the per-server HMAC secret yourself and put it into `SERVERCORE_SERVER_CREDENTIALS` — it is the shared trust anchor between backend and plugin, and the same value goes into the plugin configuration:

```bash
openssl rand -hex 32
```

## 4. Plugin on the Folia server

### Server plugins

| Plugin | Required | Role |
|---|---|---|
| [PacketEvents](https://packetevents.com/) | **yes** | The entire packet layer (anti-ESP, disguises, live inventory view) rides on it; ServerCore refuses to enable without it |
| [LuckPerms](https://luckperms.net/) | recommended | Permissions authority. Without it the plugin falls back to a static, config-driven map (`luckperms.fallback.*`) — enough to boot, not a real group manager |
| [PlaceholderAPI](https://wiki.placeholderapi.com/) | optional | Exposes the `%servercore_…%` placeholders to other plugins; without it they are simply absent |
| [FlectonePulse](https://github.com/FlectonePulse/FlectonePulse) | optional | Chat and moderation authority. Without it ServerCore runs without punishment lookups and announces its own ops events to Telegram |

### Steps

1. Download the plugin jar from [GitHub Releases](https://github.com/bejiihiu/ServerCore/releases) — the
   asset is `ServerCore-<tag>.jar` (for example `ServerCore-build-1234-a1b2c3d.jar`). Verify it against
   `SHA256SUMS.txt` from the same release, then drop it into `plugins/`.
2. Install PacketEvents (and the optional plugins you want) into `plugins/`.
3. Start the server once. The plugin generates `plugins/ServerCore/config.yml` but disables itself on that first boot: `backend.url` and `backend.secret` are empty, and an unusable backend block is a boot blocker (the log shows `config error: …`).
4. Edit the configuration:

```yaml
# plugins/ServerCore/config.yml
backend:
  url: "wss://mc-api.example.com:8081/ws"
  server-id: "game-1"          # must match SERVERCORE_SERVER_CREDENTIALS on the backend
  secret: "the-hex-secret-you-generated"

guest:
  enabled: true

anti-esp:
  enabled: true
  max-distance: 48
  check-interval-ticks: 10
```

Full key reference: [configuration.md](configuration.md).

5. Restart the server. On startup the plugin opens an outbound connection to the backend gateway
   (`<scheme>://<host>:<backend WS_PORT>/ws` — the gateway answers `404` on any other path). The plugin log
   shows `backend gateway connected as game-1`, and the backend log answers with
   `game server connected, awaiting hello`. If the plugin instead keeps logging
   `backend gateway disconnected`, the handshake is being rejected — the backend names the reason
   (`unknown server id`, `signature mismatch`, `timestamp outside replay window`).

`docs/first-run.md` condenses the whole backend + plugin bring-up into a checklist with the exact log lines
to look for.

## 5. Folia server hardening

ServerCore assumes a locked-down server. Before going live:

- Make sure **no game account holds OP**. Operator status is not the administration mechanism — permissions live in LuckPerms.
- Install [LuckPerms](https://luckperms.net/) and create your group structure (for example `default`, `trusted`, `helper`, `moderator`, `admin`, `owner`).
- Grant staff only the specific ServerCore permissions they need:
  - `servercore.admin`
  - `servercore.spec`
  - `servercore.spec.other`
  - `servercore.guest.manage`
  - `servercore.account.manage`
- Install [FlectonePulse](https://github.com/FlectonePulse/FlectonePulse) for chat and moderation. If its Telegram module is enabled, ServerCore detects it and keeps its own ops announcements out of Telegram (`flectonepulse.disable-tg`, on by default) so nothing is announced twice.
- Optionally install PlaceholderAPI to expose ServerCore's placeholders to other plugins.

## 6. Verifying the deployment

1. Join the server with a fresh account. You should be placed into spectator guest mode and receive a personal Telegram deep link in chat.
2. Open the link, complete `/start` in the bot, and confirm the linkage.
3. Fill out the application. Members should receive a vote within seconds.
4. After a successful vote, accept the single-use group invite, then reconnect and confirm the login prompt in Telegram.
5. Check that `audit_logs` and `server_events` are filling in, and that the backend log shows clean heartbeats from the plugin.

## Updating

- Plugin: either replace the jar manually (stop, swap, start — snapshots and sessions survive restarts by
  design) or let the built-in updater do it: `ops.update.*` polls GitHub releases for `build-*` tags, keeps
  the newest one, downloads `ServerCore-<tag>.jar`, verifies it against the release's `SHA256SUMS.txt` and
  swaps it over the running file (`.backup/` keeps the last three). `ops.update.auto-restart` restarts after
  a verified swap. The updater only moves forward — a release with a lower commit count is ignored — and it
  refuses any jar whose SHA-256 is missing from the checksum file or does not match.
- Backend: stop the process, replace the bundle (or `git pull`), keep `.env`, start again. Running instances
  reconnect automatically once the backend returns.
