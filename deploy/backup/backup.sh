#!/usr/bin/env bash
# ServerCore backup: one timestamped archive holding a MongoDB dump, the
# plugin's durable state files and (optionally) the backend .env, with a
# SHA-256 file next to it.
#
# safe defaults: secrets are excluded unless --include-env is passed, the
# archive is mode 0600, and a half-written archive never gets the final name.
#
# run `deploy/backup/backup.sh --help` for every option.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

LOG_TAG=backup
BACKUP_FORMAT=1

DRY_RUN=${DRY_RUN:-0}
INCLUDE_ENV=${INCLUDE_ENV:-0}
SKIP_MONGO=${SKIP_MONGO:-0}
SKIP_PLUGIN=${SKIP_PLUGIN:-0}
KEEP=${KEEP:-0}
OUT_DIR=${BACKUP_DIR:-./backups}
PLUGIN_DIR=${PLUGIN_DATA_DIR:-./plugins/ServerCore}
BACKEND_DIR=${BACKEND_DIR:-./backend}
ENV_FILE=${ENV_FILE:-}

usage() {
  cat <<'EOF'
usage: deploy/backup/backup.sh [options]

Dumps MongoDB with mongodump, copies the plugin's durable state files and
packs everything into backups/servercore-backup-<UTC timestamp>.tar.gz.
A .sha256 file is written next to the archive.

Options:
  --dry-run            print the plan, touch nothing, need no mongodump
  --include-env        also archive the env file (ENV_FILE when set, else
                       BACKEND_DIR/.env). it contains secrets.
  --env-file FILE      read MONGODB_URI from FILE (dotenv syntax, mode 0600)
  --backend-dir DIR    backend directory, default ./backend
  --plugin-dir DIR     plugin data directory, default ./plugins/ServerCore
  --out DIR            archive directory, default ./backups
  --keep N             keep the N newest archives, delete older ones
  --skip-mongo         do not dump MongoDB (split-host / game-host-only run)
  --skip-plugin        do not archive plugin state (backend-host-only run)
  -h, --help           this text

Environment:
  MONGODB_URI          connection string, required unless --skip-mongo.
                       include the database name: mongodump backs up that
                       database only. never printed, only logged redacted.
  DRY_RUN, INCLUDE_ENV, SKIP_MONGO, SKIP_PLUGIN, KEEP, BACKUP_DIR,
  PLUGIN_DATA_DIR, BACKEND_DIR, ENV_FILE  same as the flags.

Examples:
  ./deploy/backup/backup.sh --dry-run
  ./deploy/backup/backup.sh --keep 14
  ./deploy/backup/backup.sh --env-file /etc/servercore/backend.env --keep 7
  ./deploy/backup/backup.sh --skip-mongo --plugin-dir /srv/mc/plugins/ServerCore

Requires MongoDB Database Tools (mongodump), tar, gzip and sha256sum/shasum.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --include-env) INCLUDE_ENV=1 ;;
    --skip-mongo) SKIP_MONGO=1 ;;
    --skip-plugin) SKIP_PLUGIN=1 ;;
    --env-file)
      [ $# -ge 2 ] || die "--env-file needs a path"
      ENV_FILE=$2
      shift
      ;;
    --env-file=*) ENV_FILE=${1#*=} ;;
    --backend-dir)
      [ $# -ge 2 ] || die "--backend-dir needs a path"
      BACKEND_DIR=$2
      shift
      ;;
    --backend-dir=*) BACKEND_DIR=${1#*=} ;;
    --plugin-dir)
      [ $# -ge 2 ] || die "--plugin-dir needs a path"
      PLUGIN_DIR=$2
      shift
      ;;
    --plugin-dir=*) PLUGIN_DIR=${1#*=} ;;
    --out)
      [ $# -ge 2 ] || die "--out needs a directory"
      OUT_DIR=$2
      shift
      ;;
    --out=*) OUT_DIR=${1#*=} ;;
    --keep)
      [ $# -ge 2 ] || die "--keep needs a number"
      KEEP=$2
      shift
      ;;
    --keep=*) KEEP=${1#*=} ;;
    -h | --help)
      usage
      exit 0
      ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
  shift
done

is_true "$DRY_RUN" && DRY_RUN=1 || DRY_RUN=0
is_true "$INCLUDE_ENV" && INCLUDE_ENV=1 || INCLUDE_ENV=0
is_true "$SKIP_MONGO" && SKIP_MONGO=1 || SKIP_MONGO=0
is_true "$SKIP_PLUGIN" && SKIP_PLUGIN=1 || SKIP_PLUGIN=0

case "$KEEP" in
  '' | *[!0-9]*) die "--keep must be a non-negative integer, got: $KEEP" ;;
esac

if [ "$SKIP_MONGO" = 1 ] && [ "$SKIP_PLUGIN" = 1 ]; then
  die "--skip-mongo and --skip-plugin together leave nothing to back up"
fi

if [ -n "$ENV_FILE" ] && [ ! -r "$ENV_FILE" ]; then
  die "--env-file is not readable: $ENV_FILE"
fi

if [ "$DRY_RUN" = 1 ]; then
  if [ -d "$OUT_DIR" ]; then
    OUT_ABS=$(cd "$OUT_DIR" && pwd)
  else
    OUT_ABS=$OUT_DIR
  fi
else
  mkdir -p "$OUT_DIR"
  OUT_ABS=$(cd "$OUT_DIR" && pwd)
fi

# ---- preflight -------------------------------------------------------------

if [ "$SKIP_MONGO" = 0 ]; then
  load_mongo_uri "$ENV_FILE" "$BACKEND_DIR"
  [ -n "${MONGODB_URI:-}" ] ||
    die "MONGODB_URI is not set: export it, pass --env-file, or keep a readable $BACKEND_DIR/.env"
  if [ "$DRY_RUN" = 0 ]; then
    have mongodump ||
      die "mongodump not found: install MongoDB Database Tools (https://www.mongodb.com/docs/database-tools/installation/). On Atlas M0 this script is the backup plan."
  else
    have mongodump || warn "mongodump not found; a real run would stop here"
  fi
fi

if [ "$SKIP_PLUGIN" = 0 ] && [ ! -d "$PLUGIN_DIR" ]; then
  if [ "$DRY_RUN" = 1 ]; then
    warn "plugin data directory not found: $PLUGIN_DIR"
  else
    die "plugin data directory not found: $PLUGIN_DIR (pass --plugin-dir, or --skip-plugin for a mongo-only backup)"
  fi
fi

if [ "$DRY_RUN" = 0 ]; then
  have tar || die "tar not found"
  have gzip || die "gzip not found"
  have mktemp || die "mktemp not found"
  have sha256sum || have shasum || die "need sha256sum or shasum"
fi

ENV_SRC=""
if [ "$INCLUDE_ENV" = 1 ]; then
  if [ -n "$ENV_FILE" ]; then
    ENV_SRC=$ENV_FILE
  else
    ENV_SRC=$BACKEND_DIR/.env
  fi
  [ -r "$ENV_SRC" ] || die "--include-env: cannot read $ENV_SRC"
fi

STATE_COUNT=0
AUDIT_PRESENT=0
if [ "$SKIP_PLUGIN" = 0 ] && [ -d "$PLUGIN_DIR" ]; then
  names=$(list_plugin_state_files "$PLUGIN_DIR")
  if [ -n "$names" ]; then
    while IFS= read -r name; do
      STATE_COUNT=$((STATE_COUNT + 1))
    done <<EOF
$names
EOF
  fi
  for name in "${SC_STATE_FILES[@]}"; do
    [ -f "$PLUGIN_DIR/$name" ] || warn "state file not present, skipping: $name"
  done
  if [ -d "$PLUGIN_DIR/$SC_AUDIT_DIR" ]; then
    AUDIT_PRESENT=1
  fi
  if [ "$STATE_COUNT" -eq 0 ] && [ "$AUDIT_PRESENT" -eq 0 ]; then
    warn "no plugin state files under $PLUGIN_DIR - archive will hold an empty plugin-data/"
  fi
fi

STAMP=$(date -u +%Y%m%d-%H%M%S)
ARCHIVE="$OUT_ABS/servercore-backup-$STAMP.tar.gz"
suffix=1
while [ -e "$ARCHIVE" ]; do
  ARCHIVE="$OUT_ABS/servercore-backup-$STAMP-$suffix.tar.gz"
  suffix=$((suffix + 1))
done

# ---- dry run ---------------------------------------------------------------

if [ "$DRY_RUN" = 1 ]; then
  log "dry run - nothing will be written"
  printf '  archive:   %s\n' "$ARCHIVE"
  if [ "$SKIP_MONGO" = 1 ]; then
    printf '  mongodb:   skipped\n'
  else
    printf '  mongodb:   %s (mongodump --gzip)\n' "$(redact_uri "$MONGODB_URI")"
  fi
  if [ "$SKIP_PLUGIN" = 1 ]; then
    printf '  plugin:    skipped\n'
  else
    printf '  plugin:    %s\n' "$PLUGIN_DIR"
    if [ -n "${names:-}" ]; then
      while IFS= read -r name; do
        [ -n "$name" ] || continue
        printf '    + %-28s %s bytes\n' "$name" "$(wc -c <"$PLUGIN_DIR/$name" | awk '{print $1}')"
      done <<EOF
$names
EOF
    fi
    if [ "$AUDIT_PRESENT" = 1 ]; then
      count=$(find "$PLUGIN_DIR/$SC_AUDIT_DIR" -type f | wc -l | tr -d '[:space:]')
      printf '    + %-28s %s files\n' "$SC_AUDIT_DIR/" "$count"
    fi
  fi
  if [ -n "$ENV_SRC" ]; then
    printf '  env:       %s (stored as backend/.env, mode 0600)\n' "$ENV_SRC"
  else
    printf '  env:       excluded (pass --include-env to include it)\n'
  fi
  printf '  retention: %s\n' "$([ "$KEEP" -gt 0 ] && printf 'keep newest %s archive(s)' "$KEEP" || printf 'disabled')"
  exit 0
fi

# ---- staging ---------------------------------------------------------------

umask 077
STAGING=$(mktemp -d "${TMPDIR:-/tmp}/servercore-backup.XXXXXX")
cleanup() {
  rm -rf "$STAGING"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

DUMP_COUNT=0
if [ "$SKIP_MONGO" = 0 ]; then
  log "dumping mongodb"
  mkdir -p "$STAGING/mongo"
  mongodump --uri="$MONGODB_URI" --gzip --out="$STAGING/mongo" --quiet
  DUMP_COUNT=$(find "$STAGING/mongo" -type f -name '*.bson.gz' | wc -l | tr -d '[:space:]')
  [ "$DUMP_COUNT" -gt 0 ] || warn "mongodump wrote no collections - the database may be empty"
fi

if [ "$SKIP_PLUGIN" = 0 ]; then
  log "copying plugin state"
  mkdir -p "$STAGING/plugin-data"
  if [ "$STATE_COUNT" -gt 0 ]; then
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      cp -p "$PLUGIN_DIR/$name" "$STAGING/plugin-data/$name"
    done <<EOF
$names
EOF
  fi
  if [ "$AUDIT_PRESENT" = 1 ]; then
    cp -a "$PLUGIN_DIR/$SC_AUDIT_DIR" "$STAGING/plugin-data/$SC_AUDIT_DIR"
  fi
fi

if [ -n "$ENV_SRC" ]; then
  log "including backend env"
  mkdir -p "$STAGING/backend"
  cp -p "$ENV_SRC" "$STAGING/backend/.env"
  chmod 600 "$STAGING/backend/.env"
fi

# ---- manifest + archive ----------------------------------------------------

CREATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
HOST=$(hostname 2>/dev/null || printf 'unknown')
ENV_INCLUDED=no
[ -n "$ENV_SRC" ] && ENV_INCLUDED=yes
AUDIT_INCLUDED=no
[ "$AUDIT_PRESENT" = 1 ] && [ "$SKIP_PLUGIN" = 0 ] && AUDIT_INCLUDED=yes

{
  printf '# servercore backup manifest\n'
  printf 'format=%s\n' "$BACKUP_FORMAT"
  printf 'created_at_utc=%s\n' "$CREATED_AT"
  printf 'created_by=deploy/backup/backup.sh\n'
  printf 'host=%s\n' "$HOST"
  printf 'mongo_uri=%s\n' "$([ "$SKIP_MONGO" = 1 ] && printf 'skipped' || redact_uri "$MONGODB_URI")"
  printf 'mongo_dump=%s\n' "$([ "$SKIP_MONGO" = 1 ] && printf 'skipped' || printf 'gzip-bson')"
  printf 'mongo_collections=%s\n' "$DUMP_COUNT"
  printf 'plugin_dir=%s\n' "$([ "$SKIP_PLUGIN" = 1 ] && printf 'skipped' || printf '%s' "$PLUGIN_DIR")"
  printf 'plugin_state_files=%s\n' "$STATE_COUNT"
  if [ "$STATE_COUNT" -gt 0 ]; then
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      printf 'file=%s\t%s bytes\n' "$name" "$(wc -c <"$PLUGIN_DIR/$name" | awk '{print $1}')"
    done <<EOF
$names
EOF
  fi
  printf 'audit_included=%s\n' "$AUDIT_INCLUDED"
  printf 'env_included=%s\n' "$ENV_INCLUDED"
} >"$STAGING/MANIFEST.txt"

members=(MANIFEST.txt)
[ "$SKIP_MONGO" = 0 ] && members+=(mongo)
[ "$SKIP_PLUGIN" = 0 ] && members+=(plugin-data)
[ -n "$ENV_SRC" ] && members+=(backend)

log "creating archive"
tar -czf "$ARCHIVE.partial" -C "$STAGING" "${members[@]}"
chmod 600 "$ARCHIVE.partial" 2>/dev/null || true
mv -f "$ARCHIVE.partial" "$ARCHIVE"
SUM=$(write_checksum "$ARCHIVE")

# ---- retention -------------------------------------------------------------

if [ "$KEEP" -gt 0 ]; then
  listing=$(ls -1t "$OUT_ABS"/servercore-backup-*.tar.gz 2>/dev/null || true)
  if [ -n "$listing" ]; then
    index=0
    while IFS= read -r path; do
      [ -n "$path" ] || continue
      index=$((index + 1))
      [ "$index" -le "$KEEP" ] && continue
      log "retention: removing $path"
      rm -f "$path" "$path.sha256"
    done <<EOF
$listing
EOF
  fi
fi

# ---- summary ---------------------------------------------------------------

log "archive: $ARCHIVE"
log "sha256:  $SUM"
log "size:    $(du -h "$ARCHIVE" | awk '{print $1}')"
log "verify:  (cd '$OUT_ABS' && sha256sum -c '${ARCHIVE##*/}.sha256')"
log "restore: deploy/backup/restore.sh '$ARCHIVE'"
