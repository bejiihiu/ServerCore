# Installation

This guide walks through a full deployment: MongoDB, the backend, the Telegram bot, and the Folia server with the plugin. All commands are examples — adjust paths and names to your environment.

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

## 2. Telegram bot

1. Open [@BotFather](https://t.me/BotFather) and run `/newbot`. Save the token it gives you.
2. Disable privacy mode so the bot can observe group events: `/setprivacy` → select your bot → **Disable**. This is required for join/leave confirmation in the private community group.
3. Create the private community group, add the bot to it, and grant it admin rights (invite management and member removal).
4. Note the group's chat ID (you can obtain it by sending a message to the group and checking the backend log, or via any standard bot API helper).

## 3. Backend

Install from Releases (recommended) or from source.

### From a release bundle

Download the latest backend bundle from [GitHub Releases](https://github.com/bejiihiu/ServerCore/releases), unpack it, and run it with Bun:

```bash
unzip servercore-backend-*.zip -d servercore-backend
cd servercore-backend
cp .env.example .env
# edit .env — see the table below and docs/configuration.md
bun install --production
bun run start
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
DATABASE_URI=mongodb://servercore:CHANGE_ME_STRONG_PASSWORD@127.0.0.1:27017/servercore
DATABASE_NAME=servercore
BOT_TOKEN=123456789:AA-placeholder-from-botfather
TELEGRAM_GROUP_ID=-1001234567890
WS_HOST=0.0.0.0
WS_PORT=8443
WS_PATH=/gateway
LOG_LEVEL=info
```

Generate the per-server HMAC secret yourself and distribute it to the plugin configuration — it is the shared trust anchor between backend and plugin:

```bash
openssl rand -hex 32
```

## 4. Plugin on the Folia server

1. Download the plugin jar from [GitHub Releases](https://github.com/bejiihiu/ServerCore/releases).
2. Place it into your Folia server's `plugins/` directory.
3. Start the server once. The plugin generates `plugins/ServerCore/config.yml` and disables itself until the backend section is filled in.
4. Edit the configuration:

```yaml
# plugins/ServerCore/config.yml
backend:
  url: "wss://mc-api.example.com:8443/gateway"
  secret: "the-64-hex-char-secret-you-generated"
  reconnect:
    initial-delay-seconds: 2
    max-delay-seconds: 120

guest:
  enabled: true

anti-esp:
  enabled: true
  max-distance: 48
  check-interval-ticks: 10
```

Full key reference: [configuration.md](configuration.md).

5. Restart the server. On startup the plugin opens an outbound WSS connection to the backend; check the backend log for a successful gateway registration.

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
- Install [FlectonePulse](https://github.com/FlectonePulse/FlectonePulse) for chat and moderation. If its Telegram modules are enabled, configure them so notifications do not duplicate the ServerCore bot.
- Optionally install PlaceholderAPI to expose ServerCore's placeholders to other plugins.

## 6. Verifying the deployment

1. Join the server with a fresh account. You should be placed into spectator guest mode and receive a personal Telegram deep link in chat.
2. Open the link, complete `/start` in the bot, and confirm the linkage.
3. Fill out the application. Members should receive a vote within seconds.
4. After a successful vote, accept the single-use group invite, then reconnect and confirm the login prompt in Telegram.
5. Check that `audit_logs` and `server_events` are filling in, and that the backend log shows clean heartbeats from the plugin.

## Updating

- Plugin: stop the server, replace the jar in `plugins/`, start again. Snapshots and sessions survive restarts by design.
- Backend: stop the process, replace the bundle (or `git pull`), keep `.env`, start again. Running instances reconnect automatically once the backend returns.
