# Configuration presets

Ready-to-copy starting points for two common server shapes. They exist so a new
install does not begin with a blank file and a 300-line key reference.

> **These are starting points, not supported configurations.** Every value is a
> reasonable choice for the described shape, not a promise about your hardware,
> your community or your moderation model. Read the diff against the shipped
> defaults, keep what fits, change the rest. The authoritative description of
> every key is [configuration.md](../docs/configuration.md).

## What is here

| File | For | Pairs with |
|---|---|---|
| [`plugin/small-server.yml`](plugin/small-server.yml) | 10-30 concurrent players, modest hardware, closed or hand-moderated world | [`backend/dev.env`](backend/dev.env) or [`backend/production.env`](backend/production.env) |
| [`plugin/large-server.yml`](plugin/large-server.yml) | 100+ concurrent players, busy public world | [`backend/production.env`](backend/production.env) |
| [`backend/dev.env`](backend/dev.env) | Local development on one machine. **Never for production** | [`plugin/small-server.yml`](plugin/small-server.yml) |
| [`backend/production.env`](backend/production.env) | A real install: voting, retention, Moscow schedules | either plugin preset |

The plugin presets are complete `config.yml` replacements — same key set as the
shipped file, nothing added, nothing silently dropped. The env presets are
complete `.env` replacements, parsed with the same one-`KEY=value`-per-line rules
as Docker Compose and systemd.

## Plugin presets

Copy the file over `plugins/ServerCore/config.yml`, then change the values
marked `CHANGE_ME` and these:

- `backend.url`, `backend.server-id`, `backend.secret` — the trust anchor shared
  with `SERVERCORE_SERVER_CREDENTIALS` on the backend. Generate the secret with
  `openssl rand -hex 32` and use the **same** value on both sides.
- `guest.bot-username` — your bot's username, used for the fallback deep link.
- `guest.lang`, `ops.restart.locale` — `en` in the presets; set `ru` for a
  Russian-speaking community. Both locales ship with the plugin.
- `ops.restart.daily-times` and `ops.restart.timezone` — the presets assume
  `Europe/Moscow` and a quiet early-morning restart.
- `ops.restart.pterodactyl.*` — empty in the presets, which turns the panel
  integration off. Fill all three (or none) to let ServerCore ask Pterodactyl
  for a real restart instead of a console `stop`.

### small-server.yml

Deliberately calm and tightly moderated:

- anti-ESP evaluates once per second (`check-interval-ticks: 20`) instead of the
  shipped half-second cadence, and stops evaluating at 40 blocks.
- Protection is stricter than the shipped default: the gamemode drift sweep runs
  every 15s, command blocks are scanned every 5 minutes, and a denied command is
  cleared from the block instead of only logged.
- Guests: three sessions, then a register-now kick; chat and every interaction
  stay blocked; `/spec` is on for staff.
- Info bars render once a second on a bossbar.

### large-server.yml

Same security posture, cheaper visual layers. Nothing from the command denial
list, gamemode enforcement or the guest restrictions is relaxed — those are not
performance knobs.

- anti-ESP stays at `check-interval-ticks: 12` (never below 10 at four-figure
  populations) and reuses line-of-sight answers for ~3s instead of ~2s. The
  reasoning and the measured numbers are in
  [performance.md](../docs/performance.md).
- Bars refresh every 2s (`update-interval-ticks: 40`) instead of every second.
- Live inventory view pushes once a second, full re-sync every 10s, audit buffer
  doubled.
- Guest reminder cadence halved, guest gamemode sweep slowed to 5s, outbox cap
  doubled so an outage parks more guest lifecycle frames.
- Placeholder values memoized for 5s, punishment lookups cached for 60s,
  release polling every hour.

## Backend presets

Copy to `backend/.env` (Bun from source), `deploy/backend.env` (the deployment
kit) or `/etc/servercore/backend.env` (systemd). `backend/.env` and
`deploy/backend.env` are git-ignored.

### dev.env

Local loopback stack: `127.0.0.1`, its own `servercore-dev` database, bot
disabled (empty `BOT_TOKEN`, so nothing reaches a real group), `debug` logging.
Windows are shortened so a test round finishes while you wait: 5-minute voting,
60-second auth tokens, one-hour invites, 7-day retention. The HMAC placeholder is
a real 32-character string matching the plugin presets, so a preset pair boots
against each other without editing.

Change before use: nothing, unless you need the bot flows — then add a throwaway
bot token and a test group id.

### production.env

The deployment values the product is built around: 3-hour voting window, 48-hour
invites, 90/60-day retention, `Europe/Moscow` logs and schedules, `ru` ops
locale, `info` logging.

Change before use, all marked `CHANGE_ME`:

- `SERVERCORE_SERVER_CREDENTIALS` — one real secret per game server, 32+
  characters (`openssl rand -hex 32`). The shipped placeholder is public.
- `BOT_TOKEN` — empty means the backend runs bot-less: no admission, no account
  flows. A live server needs a real token.
- `GROUP_CHAT_ID` / `TELEGRAM_GROUP_CHAT_ID` — the closed community group.
- `ADMIN_TELEGRAM_IDS` — who gets event copies and `/stats`.
- `MONGODB_URI` — point it at your cluster (`mongodb+srv://…` for Atlas or a
  replica set connection string on own hardware).

## Keeping the presets honest

Both plugin presets are parsed by the plugin test suite: their leaf key set must
equal the shipped `config.yml` key set exactly, and each file is run through every
config loader. Both env presets are parsed through the backend's real config
schema, must only use documented keys, and the production preset must carry every
documented key. Add a key to the schema and the tests fail until the presets catch
up — so a preset can not drift into "looks fine, does nothing".

To verify locally:

```bash
cd backend && bun test tests/example-configs.test.ts
./gradlew :plugin:test --tests "kz.bejiihiu.servercore.config.ExamplePresetsTest"
```
