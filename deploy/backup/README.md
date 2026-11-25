# Backup tooling

Two scripts that snapshot and restore the whole ServerCore stack:

| File | What it does |
|---|---|
| `backup.sh` | `mongodump` + the plugin's durable state files + optional backend `.env` → one timestamped `.tar.gz` and a `.sha256` |
| `restore.sh` | verifies the checksum, refuses to run against a live server unless `--force`, `mongorestore --drop`, unpacks the plugin files, prints the post-restore checks |
| `lib.sh` | shared helpers, sourced by both - keep all three files together |

Policy, RPO/RTO and the full disaster runbook: **[docs/backup-restore.md](../../docs/backup-restore.md)**.

## Requirements

- `bash` (tested with 5.3 on Linux and Git Bash; `lib.sh` sticks to portable constructs)
- [MongoDB Database Tools](https://www.mongodb.com/docs/database-tools/installation/) - `mongodump` for
  backups, `mongorestore` for restores
- `tar`, `gzip`, and `sha256sum` (or `shasum` on macOS)
- a readable `MONGODB_URI` with the database name in it, e.g.
  `mongodb+srv://user:pass@cluster/servercore`

The backend container does not ship the tools. Run the backup from a host that can reach MongoDB.

## Quickstart

From the repository root on a host that sees both MongoDB and `plugins/ServerCore`:

```bash
export MONGODB_URI='mongodb+srv://user:pass@cluster/servercore'

./deploy/backup/backup.sh --dry-run       # plan only, no mongodump needed
./deploy/backup/backup.sh --keep 14       # real run
```

The output is `backups/servercore-backup-<UTC timestamp>.tar.gz` plus `.sha256`. Verify it:

```bash
cd backups && sha256sum -c servercore-backup-*.tar.gz.sha256
```

Restore (stop the server and the backend first):

```bash
./deploy/backup/restore.sh backups/servercore-backup-20261125-041700.tar.gz \
  --dry-run --env-file /etc/servercore/backend.env
./deploy/backup/restore.sh backups/servercore-backup-20261125-041700.tar.gz \
  --env-file /etc/servercore/backend.env
```

## Common options

| Option | Backup | Restore | Meaning |
|---|---|---|---|
| `--dry-run` | yes | yes | print the plan and touch nothing |
| `--env-file FILE` | yes | yes | read `MONGODB_URI` from a dotenv file without sourcing it |
| `--backend-dir DIR` | yes | yes | default `./backend`; where `.env` is read from / written to |
| `--plugin-dir DIR` | yes | yes | default `./plugins/ServerCore` |
| `--out DIR` | yes | - | archive directory, default `./backups` |
| `--keep N` | yes | - | keep the N newest archives, delete older ones |
| `--include-env` | yes | - | archive the env file too (ENV_FILE if set, else `BACKEND_DIR/.env`; secrets!) |
| `--skip-mongo` | yes | yes | skip the database half (split-host run) |
| `--skip-plugin` | yes | yes | skip the plugin half (split-host run) |
| `--checksum FILE` | - | yes | checksum path if it is not `<archive>.sha256` |
| `--force` | - | yes | proceed despite a server lock or a `200` from `HEALTH_URL` |
| `--restore-env` | - | yes | overwrite `BACKEND_DIR/.env`, keeping the old one as `.env.pre-restore-<ts>` |
| `--env-out FILE` | - | yes | where to write the env when not applying it |

## What goes into the archive

```text
servercore-backup-20261125-041700.tar.gz
├── MANIFEST.txt          timestamp, redacted URI, host, file list and sizes
├── mongo/                mongodump --gzip BSON tree
├── plugin-data/
│   ├── guest-snapshots.json
│   ├── guest-session-counts.json
│   ├── account-cache.json
│   ├── ws-outbox.json
│   ├── bars.json
│   ├── spec-restore-journal.json
│   ├── audit/            spec-audit-YYYY-MM-DD.jsonl
│   └── *.json            any other top-level JSON state file (custom snapshot path)
└── backend/.env          only with --include-env
```

Not included on purpose: `config.yml` (contains `backend.secret`), `locales/` (pushed by the backend),
`.updates/`/`.backup/` (jars), world data, other plugins.

`mongodump` exports the database named in `MONGODB_URI`; if the URI has no database, it exports every
database on the server.

## Scheduling

Cron on the backend host, daily at 04:17 local time (archive names are UTC). `flock` keeps runs from
overlapping; the URI stays in a `0600` env file, not in the crontab:

```cron
17 4 * * * cd /srv/servercore && flock -n /tmp/servercore-backup.lock ./deploy/backup/backup.sh --env-file /etc/servercore/backend.env --keep 14 >> /var/log/servercore-backup.log 2>&1
```

Every six hours instead: `17 */6 * * *`. Then sync `backups/` off the host and encrypt it - see the
runbook.

## Split hosts

MongoDB on the backend host and the plugin on the game host is the normal topology. Take two archives in
the same window:

```bash
# backend host
./deploy/backup/backup.sh --skip-plugin --env-file /etc/servercore/backend.env

# game host
./deploy/backup/backup.sh --skip-mongo --plugin-dir /srv/mc/plugins/ServerCore
```

Restore in the same split: `--skip-plugin` on the backend host, `--skip-mongo --plugin-dir ...` on the game
host. The two halves are not one transaction; MongoDB is authoritative and the plugin files are a fallback,
but stop both sides for a consistent pair before an upgrade.

## Safety notes

- `restore.sh` verifies SHA-256 before extracting and rejects absolute paths, `..` and links inside the
  archive. A missing `.sha256` is a hard stop; use `--checksum` if the file has another name.
- The live-server guard checks `session.lock`, `world/session.lock`, `world_nether/session.lock` and
  `world_the_end/session.lock` under the server root, and optionally `HEALTH_URL`. It is advisory - a
  stale lock from a crash also blocks, and a custom world layout can have no lock at all. Confirm no
  player is online before `--force`.
- `mongorestore` runs with `--drop`: collections in the dump are replaced, and writes newer than the dump
  are lost. That is the intended disaster semantics, not a merge.
- Current plugin state is copied to `<plugin-dir>.pre-restore-<timestamp>/` before it is overwritten. Run
  the restore as the same user that owns the server files, or `chown -R` afterwards - the plugin must be
  able to write its own state.
- The archive and checksum are created mode `0600`. They contain personal data; `--include-env` adds
  secrets. Encrypt before storing off-host.
- Archives under `backups/` are git-ignored. Never commit one.

## Exit codes

`0` success, `1` runtime failure (missing tool, mongodump/mongorestore failed, checksum mismatch, live
server without `--force`), `2` usage error (no archive argument). Every failure prints a `[backup]` or
`[restore]` error line; cron captures it in the log.
