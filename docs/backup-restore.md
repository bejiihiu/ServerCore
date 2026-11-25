# Backup and restore

ServerCore keeps its state in two places: MongoDB on the backend host and a handful of durable JSON files
next to the plugin. Losing either half is recoverable only if you have a copy - and on the free MongoDB
Atlas tier there is no backup you can rely on. This page explains what to copy, how often, and how to put
it back after a disaster.

The scripts live in [`deploy/backup/`](../deploy/backup/); the quickstart is
[`deploy/backup/README.md`](../deploy/backup/README.md). This page is the policy and the runbook.

## What to back up, and what breaks if you don't

### MongoDB

`mongodump` backs up the database named in `MONGODB_URI`. On a normal install that is the whole backend.

| Collection | Holds | If lost |
|---|---|---|
| `users` | Minecraft UUID ↔ Telegram link, lifecycle status | Nobody can log in; every account is unknown |
| `applications`, `application_drafts`, `votes`, `voting_messages` | Admission history and in-flight votes | Applications and their outcomes are gone; open votes cannot be finished |
| `telegram_links` | Linked identities | The bot can no longer map a Telegram user to a player |
| `sessions`, `auth_requests` | Session history and pending confirmations | In-flight logins die; session history is lost (pending tokens are short-lived anyway) |
| `group_invites` | Single-use invite state | Invites issued before the restore can no longer be validated |
| `guest_snapshots` | The **authoritative** copy of a guest's original state | Guests who were in guest mode cannot be returned to where they were; they need manual help |
| `audit_logs`, `server_events` | Administrative and observation audit trail, raw event stream | Compliance evidence and incident history are gone; 90-day retention means the database is usually the only copy |
| `conversation_states`, `punishments_external` | grammY conversation state; read-only view over the punishment source | Conversations restart; punishment lookups repopulate from the external source |

The database is the single source of truth for identity, admission and audit. A plugin state file can be
rebuilt or worked around; a lost `users` collection cannot.

### Plugin data (`plugins/ServerCore/`)

These files are the plugin's durability layer - they exist so the game server survives its own restarts and
backend outages.

| File | Holds | If lost |
|---|---|---|
| `guest-snapshots.json` | Original world, coordinates, angles, gamemode and speeds for players currently in guest mode (the `PlayerSnapshot` shape; inventory is not part of it) | The local fallback is gone. The backend's `guest_snapshots` collection is the primary copy, so this only strands a guest if the backend also missed the `GUEST_STARTED` event - then the player must be moved back by hand |
| `guest-session-counts.json` | Durable guest-session budget per player | Counts reset to zero: a player who used up the guest budget can start over. This is a limit bypass, not data loss |
| `account-cache.json` | Last known account verdicts (`ACTIVE`/`BANNED`) | While the backend is reachable it repopulates. During a backend outage, known players are treated as unregistered and fail closed into guest mode |
| `ws-outbox.json` | Frames parked while the WebSocket link was down, keyed for idempotent replay | Guest lifecycle events that had not reached the backend never arrive. The next join re-syncs current state, but the event history and any audit derived from it is lost |
| `spec-restore-journal.json` | The original state of a staff member whose observation session could not be restored live | An admin who was mid-`/spec` when the server died can stay stranded in spectator mode and has to be fixed manually |
| `bars.json` | Per-player info-bar choices | Cosmetic: bars fall back to defaults |
| `audit/spec-audit-YYYY-MM-DD.jsonl` | The local observation audit trail, rotated daily and kept 30 days | No record of who observed whom beyond the retention window. This is the compliance copy; MongoDB has its own audit trail, but not the same detail |

### Backend environment

`backend/.env` (or `deploy/backend.env` for the Compose path) holds `MONGODB_URI`, `BOT_TOKEN`,
`SERVERCORE_SERVER_CREDENTIALS` and every tuning value. Losing it is survivable - the values are documented
and the HMAC secret can be regenerated - but the plugin's `backend.secret` must then be changed to match.
Because it is a secret, the backup script only includes it with `--include-env`; store that archive
encrypted.

### Deliberately not in the archive

- `plugins/ServerCore/config.yml` - it contains `backend.secret`. Keep it in your secret store or a
  configuration repository; recreating it from this documentation is part of the runbook.
- `locales/` - pushed by the backend on every connect.
- `.updates/`, `.backup/` - updater staging and old jars, reproducible from Releases.
- Server worlds, other plugins, jars, proxy/TLS configuration, systemd units, Docker volumes. Back those up
  with the tools that own them; ServerCore's archive covers ServerCore only.

## RPO and RTO

- **RPO** (recovery point objective) - how much data you can afford to lose. A daily dump means up to 24
  hours. That is usually acceptable for a small community, but not during an admission wave or a staff
  incident. Run an extra backup before upgrades, migrations and manual database work.
- **RTO** (recovery time objective) - how long until the stack is back. For a database of tens to a few
  hundred MB on M0, restoring the dump, copying the plugin files and completing the checks below typically
  takes 15-30 minutes. Measure it in a drill instead of trusting the estimate.

| Scenario | Suggested schedule | Worst-case RPO |
|---|---|---|
| Small community, low churn | daily at a quiet hour | 24 h |
| Active admission/voting, or no tolerance for a lost day | every 6 hours | 6 h |
| Before a plugin/backend upgrade, DB migration or manual edit | one ad-hoc run | minutes |

Retention: keep at least 7 daily archives, plus one monthly copy off-host. `--keep N` prunes older archives
in the output directory; it does not copy anything off the host.

## The Atlas M0 caveat

The free Atlas tier (M0) does **not** include backups by default. MongoDB's documentation is explicit:
backups are not available for Free clusters, and the recommended path is `mongodump`/`mongorestore`. Atlas
has an optional, billed Free-cluster backup subscription in private preview (one daily snapshot, four
retained, no point-in-time restore, no on-demand snapshots, a payment method required, and it may not be
rolled out to your organization). Treat it as a bonus, never as the plan.

Two consequences:

1. **This script is the backup plan on M0.** If you do not run it, you have no recoverable copy.
2. **M0 storage is capped (512 MB).** A dump of a nearly full cluster can be large; compressed BSON is
   smaller but plan for disk space and keep archives off the Atlas host anyway. M0 also pauses after 30 days
   without connections - export before a long pause.

## Runbook: taking a backup

### One-time setup

1. Install the [MongoDB Database Tools](https://www.mongodb.com/docs/database-tools/installation/) on the
   host that will run the backup (`mongodump --version` must work). The backend container does not ship
   them; for the Compose `local-mongo` profile, either publish MongoDB to the host temporarily or run the
   tools inside the `mongo` container.
2. Make sure `MONGODB_URI` includes the database name (`.../servercore`). Without it, `mongodump` exports
   every database on the server.
3. Keep the URI in a root-owned `0600` env file and pass `--env-file`, rather than in the crontab.
4. Run a dry run and read the plan:

   ```bash
   ./deploy/backup/backup.sh --dry-run --env-file /etc/servercore/backend.env
   ```

5. Take one real backup and verify it before trusting the schedule:

   ```bash
   ./deploy/backup/backup.sh --env-file /etc/servercore/backend.env --keep 14
   ```

   The output ends with the archive path, SHA-256, size and a `sha256sum -c` line. Run that line.

### Schedule

Cron on the backend host (adjust paths; the archive name is UTC, cron uses local time). `flock` prevents an
overlapping run if the previous one is still going:

```cron
17 4 * * * cd /srv/servercore && flock -n /tmp/servercore-backup.lock ./deploy/backup/backup.sh --env-file /etc/servercore/backend.env --keep 14 >> /var/log/servercore-backup.log 2>&1
```

For a 6-hour RPO use `17 */6 * * *`. Check `/var/log/servercore-backup.log` after the first runs; a silent
cron job is not a working one.

Then copy archives off the host and encrypt them:

```bash
age -r age1... -o /mnt/offsite/servercore-20261125.tar.gz.age \
  backups/servercore-backup-20261125-041700.tar.gz
```

(or `gpg`, `rclone` to an encrypted bucket, your existing off-site tooling). An archive that only exists on
the machine that just died is not a backup.

### Split hosts

MongoDB usually lives on the backend host and `plugins/ServerCore` on the game host. In that topology:

- run `backup.sh --skip-plugin` on the backend host (MongoDB only) and
  `backup.sh --skip-mongo --plugin-dir /srv/mc/plugins/ServerCore` on the game host;
- keep both archives with the same date/time window, and restore both if the whole stack is lost.

The two halves are not captured in one transaction. That is acceptable because MongoDB is authoritative
and the plugin files are a fallback, but do not promise a consistent point-in-time pair. For an upgrade or a
risky migration, quiesce writes (stop the backend and the server, or put the server in maintenance) before
taking both.

## Runbook: disaster to verified recovery

### 0. Stop and assess

1. Freeze changes: tell staff, stop accepting applications, and do not take a new backup over the only good
   copy.
2. Decide the scope: MongoDB lost, plugin data lost, or both. A failed disk on the game host and an expired
   Atlas cluster are different incidents.
3. Pick the newest archive whose SHA-256 still verifies. If the newest is corrupt, go back one; do not
   "repair" it.

### 1. Stop the stack

```bash
# on the game host
systemctl stop minecraft        # or the Pterodactyl stop button / your wrapper
# on the backend host
systemctl stop servercore-backend
# or: docker compose -f deploy/docker-compose.yml stop backend
```

Confirm no player is online and no backend process is writing. `restore.sh` checks for
`world/session.lock` (and the nether/end equivalents) and refuses unless `--force`; the lock is advisory,
so check `screen`/`systemctl status`/the panel by hand. A crashed server can leave a stale lock; a custom
world layout can have none. If `HEALTH_URL` is set, a `200` also blocks the restore.

### 2. Restore

Copy the archive and its `.sha256` to the host that runs the restore, then:

```bash
./deploy/backup/restore.sh servercore-backup-20261125-041700.tar.gz --dry-run \
  --env-file /etc/servercore/backend.env
```

Read the plan. It shows what would go to MongoDB and which plugin files would be copied. Then run it for
real, on the right host(s):

- MongoDB-only loss: run on the backend host with `--skip-plugin`.
- Plugin-only loss: run on the game host with `--skip-mongo --plugin-dir /srv/mc/plugins/ServerCore`.
- Both: run once where both paths are reachable, or run the two variants in the same order as below.

`mongorestore` runs with `--drop`: every collection present in the dump is replaced, and anything written
after the dump timestamp is gone. That is what a restore means. The plugin step first copies the current
state to `<plugin-dir>.pre-restore-<timestamp>/`, so a wrong archive can be undone.

The backend `.env` is **not** applied by default. It is written to `servercore-restored-<timestamp>.env`;
diff it against the live file, then apply it yourself or rerun with `--restore-env` (which keeps the old file
as `.env.pre-restore-<timestamp>`).

Run the plugin half as the user that owns the server files (or `chown -R` the restored directory
afterwards). The plugin has to be able to rewrite `guest-snapshots.json` and the outbox when it starts.

### 3. Start in dependency order

1. Start MongoDB / confirm it accepts connections.
2. Start the backend. Expect `mongodb connected`, `websocket gateway listening`, `http listening`,
   `servercore backend started`.
3. Check `curl -fsS http://127.0.0.1:8080/health` - `200` and `"status":"ok"`.
4. Start Folia. Expect `servercore build-... enabled` and `backend gateway connected as <server-id>`.
5. Check the backend log for `game server connected, awaiting hello` and the server id in
   `connectedServers`.

### 4. Verify - the checks a human runs

| Check | Command / where | Expected |
|---|---|---|
| SHA-256 | `sha256sum -c <archive>.sha256` next to the archive | `OK` before you extract anything |
| Collections present | `mongosh "$MONGODB_URI" --quiet --eval 'db.getCollectionNames().sort().forEach(print)'` | `users`, `applications`, `votes`, `audit_logs`, `server_events`, `guest_snapshots`, `sessions`, `telegram_links`, `group_invites` are all there |
| Plausible counts | `mongosh "$MONGODB_URI" --quiet --eval 'printjson({users:db.users.countDocuments(),applications:db.applications.countDocuments(),audit:db.audit_logs.countDocuments()})'` | In the same ballpark as before the incident; zero `users` means the wrong database or an empty dump |
| Health | `curl -fsS http://127.0.0.1:8080/health` | `200`, `"mongoConnected":true`, server listed |
| Plugin files parse | `python3 -m json.tool plugins/ServerCore/guest-snapshots.json >/dev/null` (same for `ws-outbox.json`, `account-cache.json`, `guest-session-counts.json`, `bars.json`, `spec-restore-journal.json`) | No traceback |
| Audit trail | `ls plugins/ServerCore/audit/` | The expected `spec-audit-YYYY-MM-DD.jsonl` files |
| Active account login | Join with a known active account | Normal Telegram login prompt, then gameplay |
| Guest flow | Join with a fresh account | Guest mode, then restored to the original position on quit |
| Outbox | Backend log after reconnect | Parked guest events arrive; no repeated-handler errors |
| Updater | Plugin log | Build id is the expected one; the updater has not immediately replaced the jar |

If any check fails, stop and restore again from the same archive (or the previous one). Do not let players
in while `users` is empty or a state file fails to parse - the plugin will treat everyone as a guest and
the cache files will be overwritten.

### 5. Rollback

- Plugin state: copy `<plugin-dir>.pre-restore-<timestamp>/` back over `plugins/ServerCore/` with the server
  stopped.
- Backend env: `backend/.env.pre-restore-<timestamp>` if `--restore-env` was used.
- MongoDB: there is no automatic rollback from `--drop`; restore the previous archive. This is why you keep
  more than one.

## Drill it

Once a quarter, restore the newest archive into a scratch environment:

1. A separate MongoDB database or instance (`MONGODB_URI=.../servercore_drill`).
2. A scratch plugin directory (`--plugin-dir /tmp/restore-drill/plugins/ServerCore`).
3. `restore.sh ... --dry-run`, then the real run, then the verification table.

A backup that has never been restored is a hypothesis. The drill also tells you the real RTO.

## Security of the archives

- The plugin state and MongoDB dump contain personal data: Telegram IDs, Minecraft UUIDs, application
  answers, audit records, IP-adjacent event data.
- `--include-env` adds secrets. The script creates the archive and checksum as mode `0600` and stores the
  env as `backend/.env` inside it, but that only protects the file mode on the host.
- Encrypt before it leaves the host, restrict the bucket/directory, and apply a deletion policy. Do not
  commit archives - the repository ignores `backups/`, but an archive copied elsewhere is your
  responsibility.
- Never paste an archive, a dump, or a `.env` into a ticket, chat or log.

## Related

- [Backup tooling quickstart](../deploy/backup/README.md)
- [Installation](installation.md)
- [First run](first-run.md) - where every state file lives
- [Architecture](architecture.md) - storage model and retention
- [Security](security.md) - what counts as sensitive data
