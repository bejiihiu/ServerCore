# ServerCore

🇷🇺 [Русский](README.ru.md)

> Closed-community Survival server infrastructure — Telegram-driven admission, passwordless login, and a hardened, Folia-native runtime.

**Project status:** unstable API, no semantic versioning. Every build is identified as
`build-<commitCount>-<shortSha>`, and that tag is the release name. If the Releases page has no tagged
build yet, run the backend from source and build the plugin locally; the steps below work either way. See
[CHANGELOG.md](CHANGELOG.md) for the change history and [docs/first-run.md](docs/first-run.md) for the
short operator checklist.

![CI](https://github.com/bejiihiu/ServerCore/actions/workflows/ci.yml/badge.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)
![Java](https://img.shields.io/badge/Java-25-007396?logo=openjdk&logoColor=white)
![Folia](https://img.shields.io/badge/Folia-26.3%2B-6E4C13?logo=grass&logoColor=white)

## What it is

ServerCore is the infrastructure behind a closed-community Minecraft Survival server. It keeps the gameplay itself untouched — players who are in see an ordinary Survival server — and moves everything else (admission, identity, access control, protection) into an automated layer that lives mostly in Telegram.

- **Telegram is the control interface.** Registration, applications, voting, login confirmations and account management all happen through a bot, not in-game commands.
- **Admission is community-driven.** New players fill out an application in Telegram, the existing members vote, and a quorum decides.
- **Login is passwordless.** Every session is confirmed with a single tap in Telegram. No passwords, no `/login`.
- **Strangers can still look around.** Unregistered players get a restricted Guest Mode in spectator flight, with their original location snapshotted and restored.
- **Cheat clients get less.** An anti-ESP layer controls what each individual client sees about every other player, down to per-viewer disguises and synthetic attributes.
- **Admins can watch without being seen.** A hidden observation command lets staff follow a player invisibly, with a read-only live inventory view, fully audit-logged.
- **The server is locked down.** Dangerous commands are blocked for everyone, OP is never used for day-to-day administration, and gamemode changes outside sanctioned scenarios are reverted.
- **Server upkeep is built in.** The plugin self-updates from GitHub releases, schedules daily restarts (optionally through the Pterodactyl panel), and reports its live state — players, TPS, active observation sessions — to the backend and the bot's `/stats`.

It is deliberately *not* a generic "everything-core" plugin. Moderation and chat stay with [FlectonePulse](https://github.com/FlectonePulse/FlectonePulse), permissions stay with [LuckPerms](https://luckperms.net/), and ServerCore does the rest without duplicating either.

## Architecture overview

Two independent applications — a Bun/TypeScript backend with a Telegram bot, and a Kotlin plugin inside a Folia server — talk to each other over a single secured WebSocket channel.

```text
                 TELEGRAM
                    |
                grammY bot
                    |
             Bun backend
                    |
          application services
                    |
                 Mongoose
                    |
                 MongoDB
                    |
                 WebSocket  (the only plugin <-> backend link)
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

## Components

| Component | Stack | Responsibility |
|---|---|---|
| Backend | Bun, TypeScript, Fastify, grammY, Zod, Pino | Telegram bot, admission, voting, account lifecycle, auth tokens, notifications, data storage |
| Plugin | Kotlin, Java 25, PacketEvents | Guest mode, auth enforcement, anti-ESP, hidden observation, command protection, session tracking, self-update and restarts |
| Database | MongoDB + Mongoose | All persistent data: accounts, applications, votes, sessions, snapshots, audit logs |
| Transport | WebSocket (WSS) | The single channel between backend and plugin, signed and replay-protected |
| Integrations | FlectonePulse, LuckPerms, PlaceholderAPI | Chat & moderation, permissions, public status placeholders |

## Requirements

- **Java 25** (plugin runtime and build)
- **Folia 26.3 or newer** — Paper is not supported
- **Bun** as the backend runtime (Node.js is not used)
- **MongoDB** 7.x or newer
- A **Telegram bot token** from [@BotFather](https://t.me/BotFather)
- A **private Telegram group** the bot can administer

## Installation

Installation is done from Releases only — do not build from source unless you intend to develop on the project.

1. **Download the plugin jar** — `ServerCore-<tag>.jar` from [GitHub Releases](https://github.com/bejiihiu/ServerCore/releases) — and drop it into your Folia server's `plugins/` folder. Verify it against the release's `SHA256SUMS.txt`.
2. **Download the backend** — either the prebuilt `servercore-backend-<tag>.tar.gz` bundle or the source if you prefer to run it directly with Bun.
3. **Configure the backend environment** — copy the provided example env file next to the backend and fill in the values. Every variable is documented in [docs/configuration.md](docs/configuration.md).
4. **Set up MongoDB.** Provision a standalone or replica-set instance, create a database for ServerCore, and put its connection string into the backend environment. Recommended indexes are created automatically on first start.
5. **Create the Telegram bot** via [@BotFather](https://t.me/BotFather): `/newbot`, save the token, and disable privacy mode (`/setprivacy` → Disable) so the bot can confirm group joins.
6. **Prepare the Folia server.** Install [PacketEvents](https://packetevents.com/) (required — the packet layer depends on it), plus [FlectonePulse](https://github.com/FlectonePulse/FlectonePulse) and [LuckPerms](https://luckperms.net/) if you want the full feature set. Make sure no game accounts hold OP, and start the server once so the plugin generates its configuration.
7. **Place the plugin configuration.** Edit `plugins/ServerCore/config.yml` on the server — backend URL, server secret, guest mode and anti-ESP tuning live there. All keys are documented in [docs/configuration.md](docs/configuration.md).

A detailed, step-by-step walkthrough with real configuration examples lives in [docs/installation.md](docs/installation.md).

## Documentation

| Document | Contents |
|---|---|
| [Architecture](docs/architecture.md) | Components, threading model, data flow |
| [Installation](docs/installation.md) | Full setup guide with real examples |
| [First run](docs/first-run.md) | Short operator checklist: bring-up order, expected log lines, where state lives |
| [Configuration](docs/configuration.md) | Every env var and config key |
| [WebSocket protocol](docs/websocket-protocol.md) | Message envelope, types, auth, reconnect |
| [Admission](docs/admission.md) | Linking, applications, voting, quorum, invites |
| [Authentication](docs/authentication.md) | Passwordless login flow |
| [Security](docs/security.md) | Threat model, tokens, command protection |
| [Dependency audit](docs/dependency-audit.md) | Vulnerability, license and supply-chain status of every dependency |
| [UI design system](docs/ui-design-system.md) | In-game theme tokens, typography, layout rules |
| [FAQ](docs/faq.md) | Common questions |
| [Russian docs](docs/ru/README.md) | Russian translations of the first-run, installation, configuration and FAQ pages |
| [Changelog](CHANGELOG.md) | Unreleased change history and versioning scheme |

## License

[Proprietary](LICENSE) — copyright 2026 bejiihiu. All rights reserved.

Third-party components keep their own licenses; the full list, split by what ships and what is
provided by the server, is in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
