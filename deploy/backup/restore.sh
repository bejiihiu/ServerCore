#!/usr/bin/env bash
# ServerCore restore: verifies the archive checksum, refuses to touch a live
# server unless forced, restores MongoDB with mongorestore and unpacks the
# plugin state files. the backend .env is never overwritten unless asked.
#
# run `deploy/backup/restore.sh --help` for every option.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# source-path keeps the lint working from any cwd: the helper sits next to us
# shellcheck source-path=SCRIPTDIR
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

LOG_TAG=restore

ARCHIVE=""
SUM_FILE=""
FORCE=0
DRY_RUN=0
RESTORE_ENV=0
SKIP_MONGO=0
SKIP_PLUGIN=0
PLUGIN_DIR=${PLUGIN_DATA_DIR:-./plugins/ServerCore}
BACKEND_DIR=${BACKEND_DIR:-./backend}
SERVER_ROOT=${SERVER_ROOT:-}
ENV_FILE=${ENV_FILE:-}
ENV_OUT=${ENV_OUT:-}

usage() {
  cat <<'EOF'
usage: deploy/backup/restore.sh ARCHIVE [options]

Restores a backup made by backup.sh. in order: verify SHA-256, refuse if a
server lock file is present, extract to a temp dir, mongorestore --drop, copy
the plugin state files back, then print the post-restore checks.

Options:
  --checksum FILE      checksum file, default ARCHIVE.sha256 (required)
  --force              proceed even when a server lock file is present
  --dry-run            verify and print the plan, change nothing
  --skip-mongo         do not touch MongoDB
  --skip-plugin        do not touch the plugin data directory
  --plugin-dir DIR     plugin data directory, default ./plugins/ServerCore
  --backend-dir DIR    backend directory, default ./backend
  --server-root DIR    server root used for the lock check (default: derived
                       from --plugin-dir, i.e. the folder above plugins/)
  --env-file FILE      read MONGODB_URI from FILE (dotenv syntax, mode 0600)
  --restore-env        overwrite BACKEND_DIR/.env (the old one is kept as
                       .env.pre-restore-<timestamp>); default writes a side file
  --env-out FILE       where to write the extracted env when not applied
  -h, --help           this text

Environment:
  MONGODB_URI          connection string, required when restoring mongodb
  HEALTH_URL           optional, e.g. http://127.0.0.1:8080/health. a 200
                       answer blocks the restore unless --force is passed
  SERVER_LOCK_FILE     explicit lock file to check instead of the usual
                       world/session.lock candidates
  PLUGIN_DATA_DIR, BACKEND_DIR, SERVER_ROOT, ENV_FILE, ENV_OUT  same as flags

Stop the Folia server AND the backend before a real restore. The lock check
is an advisory guard: a crashed server can leave a stale session.lock, and a
custom world layout can have none, so confirm by hand.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --checksum)
      [ $# -ge 2 ] || die "--checksum needs a path"
      SUM_FILE=$2
      shift
      ;;
    --checksum=*) SUM_FILE=${1#*=} ;;
    --force) FORCE=1 ;;
    --dry-run) DRY_RUN=1 ;;
    --restore-env) RESTORE_ENV=1 ;;
    --skip-mongo) SKIP_MONGO=1 ;;
    --skip-plugin) SKIP_PLUGIN=1 ;;
    --plugin-dir)
      [ $# -ge 2 ] || die "--plugin-dir needs a path"
      PLUGIN_DIR=$2
      shift
      ;;
    --plugin-dir=*) PLUGIN_DIR=${1#*=} ;;
    --backend-dir)
      [ $# -ge 2 ] || die "--backend-dir needs a path"
      BACKEND_DIR=$2
      shift
      ;;
    --backend-dir=*) BACKEND_DIR=${1#*=} ;;
    --server-root)
      [ $# -ge 2 ] || die "--server-root needs a path"
      SERVER_ROOT=$2
      shift
      ;;
    --server-root=*) SERVER_ROOT=${1#*=} ;;
    --env-file)
      [ $# -ge 2 ] || die "--env-file needs a path"
      ENV_FILE=$2
      shift
      ;;
    --env-file=*) ENV_FILE=${1#*=} ;;
    --env-out)
      [ $# -ge 2 ] || die "--env-out needs a path"
      ENV_OUT=$2
      shift
      ;;
    --env-out=*) ENV_OUT=${1#*=} ;;
    -h | --help)
      usage
      exit 0
      ;;
    -*) die "unknown option: $1 (try --help)" ;;
    *)
      [ -z "$ARCHIVE" ] || die "unexpected argument: $1"
      ARCHIVE=$1
      ;;
  esac
  shift
done

is_true "$FORCE" && FORCE=1 || FORCE=0
is_true "$DRY_RUN" && DRY_RUN=1 || DRY_RUN=0
is_true "$RESTORE_ENV" && RESTORE_ENV=1 || RESTORE_ENV=0
is_true "$SKIP_MONGO" && SKIP_MONGO=1 || SKIP_MONGO=0
is_true "$SKIP_PLUGIN" && SKIP_PLUGIN=1 || SKIP_PLUGIN=0

if [ -z "$ARCHIVE" ]; then
  usage >&2
  exit 2
fi
[ -f "$ARCHIVE" ] || die "archive not found: $ARCHIVE"

if [ -z "$SUM_FILE" ]; then
  SUM_FILE="$ARCHIVE.sha256"
fi
[ -f "$SUM_FILE" ] || die "checksum file not found: $SUM_FILE (refusing an unverified archive; pass --checksum if it has another name)"

if [ "$SKIP_MONGO" = 1 ] && [ "$SKIP_PLUGIN" = 1 ]; then
  die "--skip-mongo and --skip-plugin together leave nothing to restore"
fi

if [ -n "$ENV_FILE" ] && [ ! -r "$ENV_FILE" ]; then
  die "--env-file is not readable: $ENV_FILE"
fi

# ---- checksum --------------------------------------------------------------

EXPECTED=$(awk 'NR==1 {print $1}' "$SUM_FILE")
case "$EXPECTED" in
  '' | *[!0-9a-fA-F]*) die "malformed checksum file: $SUM_FILE" ;;
esac
[ ${#EXPECTED} -eq 64 ] || die "checksum in $SUM_FILE is not a sha256 digest"
ACTUAL=$(hash_file "$ARCHIVE")
if [ "$(printf '%s' "$ACTUAL" | tr 'A-F' 'a-f')" != "$(printf '%s' "$EXPECTED" | tr 'A-F' 'a-f')" ]; then
  die "sha256 mismatch for $ARCHIVE: the archive is corrupt or was modified"
fi

# ---- live-server guard -----------------------------------------------------

STAMP=$(date -u +%Y%m%d-%H%M%S)

if [ -z "$SERVER_ROOT" ]; then
  case "$PLUGIN_DIR" in
    */plugins/*) SERVER_ROOT=${PLUGIN_DIR%/plugins/*} ;;
    *)
      parent=$(dirname "$PLUGIN_DIR")
      if [ "$parent" = "." ]; then
        SERVER_ROOT="."
      else
        SERVER_ROOT=$parent/..
      fi
      ;;
  esac
fi

LOCK=""
if [ -n "${SERVER_LOCK_FILE:-}" ]; then
  [ -e "$SERVER_LOCK_FILE" ] && LOCK=$SERVER_LOCK_FILE
else
  LOCK=$(find_server_lock "$SERVER_ROOT" || true)
fi

if [ -n "$LOCK" ] && [ "$FORCE" = 0 ]; then
  if [ "$DRY_RUN" = 1 ]; then
    warn "server looks alive: $LOCK exists; a real run would refuse without --force"
  else
    die "server looks alive: $LOCK exists. stop it, confirm no player is online, then rerun. a stale lock from a crash also trips this - pass --force only after checking by hand"
  fi
fi

if [ -n "${HEALTH_URL:-}" ] && [ "$FORCE" = 0 ] && have curl; then
  status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$HEALTH_URL" || true)
  if [ "$status" = "200" ]; then
    if [ "$DRY_RUN" = 1 ]; then
      warn "the backend answers 200 at $HEALTH_URL; a real run would refuse without --force"
    else
      die "the backend answers 200 at $HEALTH_URL: stop it before restoring, or pass --force after accepting the risk"
    fi
  fi
fi

if [ -n "$LOCK" ] && [ "$FORCE" = 1 ]; then
  warn "forced past the server lock: $LOCK"
fi

# ---- archive validation + extraction --------------------------------------

tar -tzf "$ARCHIVE" >/dev/null 2>&1 || die "not a readable .tar.gz archive: $ARCHIVE"
if tar -tzf "$ARCHIVE" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  die "archive contains absolute or parent-traversal paths; refusing to extract"
fi
if tar -tvzf "$ARCHIVE" | grep -Eq '^[^d-]'; then
  die "archive contains symlinks, hardlinks or device nodes; refusing to extract"
fi

umask 077
STAGING=$(mktemp -d "${TMPDIR:-/tmp}/servercore-restore.XXXXXX")
cleanup() {
  rm -rf "$STAGING"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

tar -xzf "$ARCHIVE" -C "$STAGING"
[ -f "$STAGING/MANIFEST.txt" ] || warn "no MANIFEST.txt in the archive - is this a ServerCore backup?"
CREATED_AT=$(grep '^created_at_utc=' "$STAGING/MANIFEST.txt" 2>/dev/null | cut -d= -f2- || true)
[ -n "$CREATED_AT" ] && log "archive was taken at $CREATED_AT"

HAS_MONGO=0
[ -d "$STAGING/mongo" ] && HAS_MONGO=1
HAS_PLUGIN=0
[ -d "$STAGING/plugin-data" ] && HAS_PLUGIN=1
HAS_ENV=0
[ -f "$STAGING/backend/.env" ] && HAS_ENV=1

if [ "$SKIP_MONGO" = 0 ] && [ "$HAS_MONGO" = 0 ]; then
  die "archive has no mongo/ dump (made with --skip-mongo?)"
fi
if [ "$SKIP_PLUGIN" = 0 ] && [ "$HAS_PLUGIN" = 0 ]; then
  die "archive has no plugin-data/ directory (made with --skip-plugin?)"
fi
if [ "$RESTORE_ENV" = 1 ] && [ "$HAS_ENV" = 0 ]; then
  warn "--restore-env was passed but the archive has no backend/.env (it was taken without --include-env)"
fi

if [ "$SKIP_MONGO" = 0 ]; then
  load_mongo_uri "$ENV_FILE" "$BACKEND_DIR"
  [ -n "${MONGODB_URI:-}" ] ||
    die "MONGODB_URI is not set: export it, pass --env-file, or keep a readable $BACKEND_DIR/.env"
  if [ "$DRY_RUN" = 1 ]; then
    have mongorestore || warn "mongorestore not found; a real run would stop here"
  else
    have mongorestore ||
      die "mongorestore not found: install MongoDB Database Tools (https://www.mongodb.com/docs/database-tools/installation/)"
  fi
fi

# ---- dry run ---------------------------------------------------------------

if [ "$DRY_RUN" = 1 ]; then
  log "dry run - checksum ok, nothing will be restored"
  if [ "$SKIP_MONGO" = 0 ]; then
    printf '  mongo:     mongorestore --gzip --drop (%s)\n' "$(redact_uri "$MONGODB_URI")"
  else
    printf '  mongo:     skipped\n'
  fi
  if [ "$SKIP_PLUGIN" = 0 ]; then
    printf '  plugin:    %s\n' "$PLUGIN_DIR"
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      printf '    + %-28s %s bytes\n' "$name" "$(wc -c <"$STAGING/plugin-data/$name" | awk '{print $1}')"
    done <<EOF
$(list_plugin_state_files "$STAGING/plugin-data")
EOF
    [ -d "$STAGING/plugin-data/$SC_AUDIT_DIR" ] &&
      printf '    + %-28s %s files\n' "$SC_AUDIT_DIR/" "$(find "$STAGING/plugin-data/$SC_AUDIT_DIR" -type f | wc -l | tr -d '[:space:]')"
  else
    printf '  plugin:    skipped\n'
  fi
  if [ "$HAS_ENV" = 1 ]; then
    if [ "$RESTORE_ENV" = 1 ]; then
      printf '  env:       would overwrite %s/.env (old one kept)\n' "$BACKEND_DIR"
    else
      printf '  env:       would be written to a side file, not applied\n'
    fi
  fi
  exit 0
fi

# ---- mongo -----------------------------------------------------------------

if [ "$SKIP_MONGO" = 0 ]; then
  log "restoring mongodb (--drop: collections in the dump are replaced)"
  mongorestore --uri="$MONGODB_URI" --gzip --drop "$STAGING/mongo"
else
  log "mongodb restore skipped"
fi

# ---- plugin data -----------------------------------------------------------

if [ "$SKIP_PLUGIN" = 0 ]; then
  ROLLBACK=""
  if [ -d "$PLUGIN_DIR" ]; then
    previous=$(list_plugin_state_files "$PLUGIN_DIR")
    previous_audit=0
    [ -d "$PLUGIN_DIR/$SC_AUDIT_DIR" ] && previous_audit=1
    if [ -n "$previous" ] || [ "$previous_audit" = 1 ]; then
      ROLLBACK="$PLUGIN_DIR.pre-restore-$STAMP"
      suffix=1
      while [ -e "$ROLLBACK" ]; do
        ROLLBACK="$PLUGIN_DIR.pre-restore-$STAMP-$suffix"
        suffix=$((suffix + 1))
      done
      mkdir -p "$ROLLBACK"
      if [ -n "$previous" ]; then
        while IFS= read -r name; do
          [ -n "$name" ] || continue
          cp -p "$PLUGIN_DIR/$name" "$ROLLBACK/$name"
        done <<EOF
$previous
EOF
      fi
      [ "$previous_audit" = 1 ] && cp -a "$PLUGIN_DIR/$SC_AUDIT_DIR" "$ROLLBACK/$SC_AUDIT_DIR"
      log "previous plugin state kept in $ROLLBACK"
    fi
  fi

  log "restoring plugin state into $PLUGIN_DIR"
  mkdir -p "$PLUGIN_DIR"
  cp -a "$STAGING/plugin-data/." "$PLUGIN_DIR/"
fi

# ---- backend env -----------------------------------------------------------

if [ "$HAS_ENV" = 1 ]; then
  if [ "$RESTORE_ENV" = 1 ]; then
    mkdir -p "$BACKEND_DIR"
    if [ -f "$BACKEND_DIR/.env" ]; then
      cp -p "$BACKEND_DIR/.env" "$BACKEND_DIR/.env.pre-restore-$STAMP"
      chmod 600 "$BACKEND_DIR/.env.pre-restore-$STAMP" 2>/dev/null || true
      log "previous backend env kept as $BACKEND_DIR/.env.pre-restore-$STAMP"
    fi
    cp -p "$STAGING/backend/.env" "$BACKEND_DIR/.env"
    chmod 600 "$BACKEND_DIR/.env" 2>/dev/null || true
    log "backend env restored to $BACKEND_DIR/.env"
  else
    if [ -n "$ENV_OUT" ]; then
      [ ! -e "$ENV_OUT" ] || die "--env-out already exists: $ENV_OUT (choose another path)"
      ENV_DEST=$ENV_OUT
    else
      ENV_DEST="./servercore-restored-$STAMP.env"
      suffix=1
      while [ -e "$ENV_DEST" ]; do
        ENV_DEST="./servercore-restored-$STAMP-$suffix.env"
        suffix=$((suffix + 1))
      done
    fi
    cp -p "$STAGING/backend/.env" "$ENV_DEST"
    chmod 600 "$ENV_DEST" 2>/dev/null || true
    log "backend env extracted to $ENV_DEST (not applied; diff it against the live one, or rerun with --restore-env)"
  fi
fi

# ---- verification checklist ------------------------------------------------

cat <<EOF

restore finished. now verify by hand:

  1. mongo collections and counts (use the same MONGODB_URI):
       mongosh "\$MONGODB_URI" --quiet --eval 'db.getCollectionNames().sort().forEach(print)'
       mongosh "\$MONGODB_URI" --quiet --eval 'printjson({users:db.users.countDocuments(),applications:db.applications.countDocuments(),votes:db.votes.countDocuments(),audit:db.audit_logs.countDocuments()})'
     every collection you had before must be present; counts must be plausible.

  2. starting the backend:
       bun run start
     expect "mongodb connected", "websocket gateway listening", "http listening",
     "servercore backend started", then:
       curl -fsS http://127.0.0.1:8080/health
     must answer 200 with "status":"ok" and list the game server.

  3. starting Folia:
     expect "servercore build-... enabled" and "backend gateway connected as <server-id>".

  4. plugin state, with the server stopped:
       ls -l "$PLUGIN_DIR"
       python3 -m json.tool "$PLUGIN_DIR/guest-snapshots.json" > /dev/null
       python3 -m json.tool "$PLUGIN_DIR/ws-outbox.json" > /dev/null
     replace python3 with any JSON parser you have. a file that fails to parse
     means the wrong archive or a truncation; restore again.

  5. join with a test account:
     - a known active account must get the normal Telegram login prompt;
     - a new account must land in guest mode and be restored on quit;
     - watch the backend log for "player joined" and the outbox draining.

  6. audit trail: $PLUGIN_DIR/$SC_AUDIT_DIR must contain the expected
     spec-audit-YYYY-MM-DD.jsonl files.

if the backend was started before the restore, stop it first: a live backend
writing into the database during or after mongorestore races the restore.
EOF
