# Deployment kit

Three supported ways to get from an empty host to a linked network: containers, a native Bun service, or
a Pterodactyl panel. Every path ends in the same place - a backend answering `/health`, and a Folia server
whose plugin is attached to the gateway.

Everything here is written against this repository as it is: the endpoints, ports, env names, log lines and
release asset names are the ones in `backend/src`, `plugin/src` and `.github/workflows/release.yml`. There is
no semantic version; releases are tagged `build-<commitCount>-<shortSha>` and that tag is the version.

| File | What it is |
|---|---|
| `docker-compose.yml` | backend container, opt-in Caddy in front of the gateway, opt-in single-node MongoDB |
| `Caddyfile` | WSS termination for `/ws` only |
| `backend.env.example` | production env, copied from the backend schema |
| `systemd/servercore-backend.service` | hardened native service unit |
| `pterodactyl-egg.json` | PTDL_v2 egg: Folia + PacketEvents + plugin, pre-wired to the backend |

Shared facts worth knowing before any path:

- The plugin opens a WebSocket to `<backend.url>` and the gateway only accepts the path **`/ws`** - anything
  else is a `404`. The handshake carries `server`, `ts`, `nonce`, `sig` in the query string (HMAC over
  `server|ts|nonce`), so a proxy must never strip or rewrite the query.
- `SERVERCORE_SERVER_CREDENTIALS` on the backend is a JSON object `{"<server-id>": "<secret>"}`. The key is
  the plugin's `backend.server-id`, the value its `backend.secret` (32+ characters). This pair is the only
  trust anchor between the two sides.
- The backend treats a dead MongoDB as *degraded, not dead*: `/health` answers `503` while the process stays
  up and retries. A `503` is never a crash.
- The two hosts must agree on time within `WS_REPLAY_WINDOW_MS` (30 s by default) or every handshake is
  rejected with `timestamp outside replay window`.

---

## Path A - Docker Compose

Requires Docker Engine with the Compose v2 plugin. The image is built from `backend/Dockerfile` (Bun 1.4,
non-root user, `bun run src/app.ts`).

1. **Create the env file.** From the repository root:

   ```bash
   cp deploy/backend.env.example deploy/backend.env
   ```

   Fill in at least `MONGODB_URI`, `BOT_TOKEN`, `GROUP_CHAT_ID`, `TELEGRAM_GROUP_CHAT_ID` and
   `SERVERCORE_SERVER_CREDENTIALS`. Generate the per-server secret with `openssl rand -hex 32`; the
   placeholder in the file is deliberately shorter than 32 characters, so the backend refuses to boot until
   you replace it.

2. **Bring the backend up.**

   ```bash
   docker compose -f deploy/docker-compose.yml up -d --build
   ```

   With no MongoDB on the host yet, add the bundled single-node database for the first bring-up:
   `docker compose -f deploy/docker-compose.yml --profile local-mongo up -d --build`, and set
   `MONGODB_URI=mongodb://mongo:27017/servercore` in `deploy/backend.env`.

3. **Terminate TLS in front of the gateway.** The plugin should never send the HMAC secret over plain `ws://`
   across an untrusted network. Set the domain and an ACME contact and start the proxy profile:

   ```bash
   export SERVERCORE_DOMAIN=mc-api.example.com
   export ACME_EMAIL=admin@example.com
   docker compose -f deploy/docker-compose.yml --profile proxy up -d
   ```

   Caddy obtains a certificate, forwards only `/ws` to `backend:8081`, keeps the query string, and answers
   `404` to everything else. Ports 80 and 443 must be reachable from the internet for the ACME challenge.

4. **Point the plugin at it.** In `plugins/ServerCore/config.yml` on the Folia server:

   ```yaml
   backend:
     url: "wss://mc-api.example.com/ws"
     server-id: "game-1"
     secret: "<the same 32+ character secret>"
   ```

**Checkpoints**

```bash
docker compose -f deploy/docker-compose.yml ps         # backend: (healthy)
curl -s http://127.0.0.1:8080/health                  # {"status":"ok", ...}
curl -s -o /dev/null -w '%{http_code}\n' https://mc-api.example.com/ws   # 401
curl -s -o /dev/null -w '%{http_code}\n' https://mc-api.example.com/      # 404
```

- `401` on `/ws` is the correct answer to an unsigned request: the gateway checks the HMAC before upgrading,
  so an unauthenticated client never becomes a socket. It proves the proxy path, port and query handling.
- `200` on `/health` means Mongo is reachable; `503` with `"status":"degraded"` means the process is fine and
  the database is not.
- `docker compose -f deploy/docker-compose.yml logs -f backend` should show, in this order: `mongodb
  connected`, `websocket gateway listening`, `http listening`, `servercore backend started`.

Notes:

- The backend container runs with a read-only root filesystem; it writes nothing (state is in MongoDB) and
  only `/tmp` is writable. If a future build needs disk, drop `read_only` and add a volume.
- `deploy/backend.env` holds the HMAC secrets and is ignored by git; keep it that way.
- `8081` is not published by default: with the proxy profile the gateway is reachable only through Caddy.
  Uncomment the `8081` port mapping in `docker-compose.yml` if game servers connect to the gateway directly.
- `stop_grace_period: 30s` gives the graceful shutdown room: SIGTERM drains in-flight frames
  (`WS_DRAIN_TIMEOUT_MS`, 5 s), stops the bot, closes Mongo.

---

## Path B - systemd (native Bun)

No container: Bun runs the unpacked release bundle. This is the path the unit file in
`systemd/servercore-backend.service` is written for.

1. **Create the service account.**

   ```bash
   sudo useradd --system --home-dir /var/lib/servercore --shell /usr/sbin/nologin servercore
   ```

2. **Install Bun and unpack the bundle.** Install Bun so that the binary is on a stable path
   (`BUN_INSTALL=/usr/local curl -fsSL https://bun.sh/install | bash`, or the distro package), then confirm
   where it lives - the unit needs the absolute path:

   ```bash
   command -v bun        # e.g. /usr/local/bin/bun
   ```

   Download `servercore-backend-<tag>.tar.gz` and `SHA256SUMS.txt` from the
   [releases page](https://github.com/bejiihiu/ServerCore/releases), verify, unpack:

   ```bash
   sha256sum -c SHA256SUMS.txt
   sudo install -d -o servercore -g servercore /opt/servercore/backend
   sudo tar -xzf servercore-backend-*.tar.gz -C /opt/servercore/backend
   cd /opt/servercore/backend && sudo -u servercore bun install --frozen-lockfile --production
   ```

3. **Install the env file.** It contains the HMAC secrets, so keep it root-owned and unreadable by others:

   ```bash
   sudo install -d -m 0750 /etc/servercore
   sudo install -m 0600 -o root -g root deploy/backend.env.example /etc/servercore/backend.env
   sudoedit /etc/servercore/backend.env
   ```

4. **Install and start the unit.** Copy `deploy/systemd/servercore-backend.service` to
   `/etc/systemd/system/`, set `ExecStart=` to the path from step 2, then:

   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now servercore-backend
   ```

5. **Publish the gateway.** Use the `Caddyfile` here as the front door (it forwards only `/ws` to the
   gateway and terminates TLS), or configure your own proxy the same way: forward `/ws`, keep the query
   string, pass the `Upgrade`/`Connection` headers. Without a proxy the game servers must reach `WS_PORT`
   directly - fine on a private network, not fine on the open internet, because the handshake secret travels
   in the query string.

**Checkpoints**

```bash
systemctl status servercore-backend --no-pager    # active (running), no restart loop
journalctl -u servercore-backend -f               # mongodb connected / websocket gateway listening / http listening
curl -s http://127.0.0.1:8080/health              # {"status":"ok", ...}
systemd-analyze security servercore-backend       # exposure score after the hardening in the unit
```

- `NRestarts` climbing in `systemctl status` means the process dies on boot: run it in the foreground once
  (`sudo -u servercore /usr/local/bin/bun run src/app.ts`) with the same `EnvironmentFile` to see the error.
  If the journal shows an `EPERM` on a syscall, clear `SystemCallFilter=` in the unit - the sandbox is
  stricter than the runtime needs on your kernel.
- The unit sets `ProtectSystem=strict` with a single writable path (`/var/lib/servercore`, provided by
  `StateDirectory=`). A config error inside the app is not the unit's fault - check the journal for
  `[servercore] invalid configuration: ...`.

---

## Path C - Pterodactyl

`pterodactyl-egg.json` is a PTDL_v2 egg and uses only fields the panel's importer reads (`meta`,
`features`, `docker_images`, `startup`, `config.*`, `scripts.installation.*`, `variables[]`).

The install script:

- resolves Folia from the PaperMC **v3** API (`fill.papermc.io`; the v2 API is `410 Gone`), default version
  `26.3`, default build `latest`, and verifies the jar against the SHA-256 the API publishes;
- installs PacketEvents `2.14.0` (`packetevents-spigot-2.14.0.jar` from the `v2.14.0` tag) - the plugin
  declares it `required: true` and will not enable without it;
- downloads the newest plugin release (`ServerCore-build-<count>-<sha>.jar`) and verifies it against that
  release's `SHA256SUMS.txt`;
- writes `plugins/ServerCore/config.yml` from the shipped config of the same tag with `backend.url`,
  `backend.server-id` and `backend.secret` already filled in, so the first boot is a working boot. An
  existing `config.yml` is never overwritten - panel edits survive reinstalls.

1. **Import the egg.** Admin → Nests → create a nest (for example "Minecraft") → **Import Egg** →
   `pterodactyl-egg.json`. The egg provides a single image, `ghcr.io/pterodactyl/yolks:java_25`: Java 25 is
   mandatory, the plugin is compiled for a Java 25 toolchain and will not load on 21.

2. **Create the server.** Use the imported egg, allocate **25565** as the primary allocation (the primary
   allocation is written into `server.properties` as `server-port`), and give it at least 2 GB. Enable
   **EULA** in the startup feature list.

3. **Fill the backend link in the startup variables.**

   | Variable | Example | Notes |
   |---|---|---|
   | `Backend Gateway URL` | `wss://mc-api.example.com/ws` | must end in `/ws`; the install fails otherwise |
   | `Server ID` | `game-1` | `[a-z0-9._-]`, the key in `SERVERCORE_SERVER_CREDENTIALS` |
   | `Server Secret` | `<32+ chars>` | the value for that key, `openssl rand -hex 32` |
   | `Folia Version` / `Folia Build` | `26.3` / `latest` | reinstall to change |
   | `PacketEvents Version` | `2.14.0` | the tag is `v2.14.0`, the file is `packetevents-spigot-2.14.0.jar` |
   | `Plugin Release` | `latest` or `build-194-dc8a896` | reinstall to change |
   | `GitHub Token` | empty | optional, only lifts the anonymous API rate limit during install |

4. **Mirror the pair on the backend.** Add the same `server-id`/`secret` to `SERVERCORE_SERVER_CREDENTIALS`
   and restart the backend - the link only comes up when both sides already know the secret.

5. **Install, then start.** The installer does not start the server; the panel's install task runs first.

**Checkpoints**

- Install log ends with `install complete: folia 26.3 build <n>, packetevents 2.14.0, plugin build-...`.
- Console `Done (...)! For help, type "help"` - the panel uses that exact string as its ready marker.
- `plugins/ServerCore/config.yml` exists and its `backend` block carries your three values.
- Plugin console shows `backend gateway connected as <server-id>`; the backend log answers
  `game server connected, awaiting hello`, and the server id appears in `connectedServers` at `/health`.
- If it loops on `backend gateway disconnected, reconnect scheduled`, the backend log names the reason next
  to `gateway handshake rejected`:

  | Reason | Cause |
  |---|---|
  | `unknown server id` | the `Server ID` has no key in `SERVERCORE_SERVER_CREDENTIALS` |
  | `signature mismatch` | the two secrets differ |
  | `timestamp outside replay window` | clocks are more than 30 s apart (NTP) |
  | `missing handshake fields` | the URL is not the `/ws` gateway path, or a proxy ate the query string |

Optional: the plugin can ask the panel for restarts instead of sending a console `stop`. It reads a Pterodactyl
**client** API key (`ptlc_…`) from the env var named by `ops.restart.pterodactyl.api-key-env`
(`PTERO_API_KEY` by default), and needs `ops.restart.pterodactyl.panel-url` plus `server-id` in
`config.yml`. Adding a `PTERO_API_KEY` variable to the egg passes the key into the container environment;
the panel url and server id still have to be written into the config.

---

## After the link is up

Same for all three paths:

1. Join with a fresh account → you land in spectator guest mode and get a personal Telegram deep link.
2. `/start` the bot, confirm the linkage, fill in the application.
3. Members vote in the group; after quorum the single-use invite arrives, then the login prompt.
4. `audit_logs` and `server_events` start filling in; `/stats` answers for the ids in `ADMIN_TELEGRAM_IDS`.
5. Put a backup schedule in place before real players arrive: [backup tooling](backup/README.md) dumps
   MongoDB, the plugin's durable state files and optionally the backend env into one verified archive,
   with a restore script and runbook ([policy and disaster steps](../docs/backup-restore.md)). This matters
   most on Atlas M0, which has no backups you can rely on.

Full walkthrough and every config key: [installation.md](../docs/installation.md),
[first-run.md](../docs/first-run.md), [configuration.md](../docs/configuration.md).
