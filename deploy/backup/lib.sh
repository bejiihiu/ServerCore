#!/usr/bin/env bash
# shared helpers for the ServerCore backup/restore scripts.
# sourced, never executed directly.

log() {
  printf '[%s] %s\n' "${LOG_TAG:-backup}" "$*"
}

warn() {
  printf '[%s] warning: %s\n' "${LOG_TAG:-backup}" "$*" >&2
}

die() {
  printf '[%s] error: %s\n' "${LOG_TAG:-backup}" "$*" >&2
  exit 1
}

have() {
  command -v "$1" >/dev/null 2>&1
}

# 1/true/yes/on -> success. anything else (including empty) -> failure.
is_true() {
  case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
    1 | true | yes | on) return 0 ;;
    *) return 1 ;;
  esac
}

# sha256 of a file, whichever tool the host has. prints only the hex digest.
hash_file() {
  local file=$1 out
  if have sha256sum; then
    out=$(sha256sum "$file")
  elif have shasum; then
    out=$(shasum -a 256 "$file")
  else
    die "need sha256sum or shasum to hash $file"
  fi
  printf '%s\n' "${out%% *}"
}

# strips credentials from a mongodb uri before it can land in a log or manifest.
redact_uri() {
  printf '%s' "$1" | sed -E 's#(://)[^@/]*@#\1***@#'
}

# reads KEY=value out of a dotenv file without eval'ing the file. handles a
# trailing CR, leading whitespace and surrounding single/double quotes.
# returns 1 when the key is absent or the file is unreadable.
read_env_value() {
  local file=$1 key=$2 line value
  [ -r "$file" ] || return 1
  line=$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$file" | tail -n 1 || true)
  [ -n "$line" ] || return 1
  value=${line#*=}
  value=${value%$'\r'}
  value=${value#"${value%%[![:space:]]*}"}
  case "$value" in
    \"*\") value=${value#\"}; value=${value%\"} ;;
    \'*\') value=${value#\'}; value=${value%\'} ;;
  esac
  printf '%s' "$value"
}

# loads MONGODB_URI into the environment unless it is already exported.
# explicit --env-file wins over the backend directory's .env.
load_mongo_uri() {
  local env_file=$1 backend_dir=$2
  if [ -z "${MONGODB_URI:-}" ] && [ -n "$env_file" ] && [ -r "$env_file" ]; then
    MONGODB_URI=$(read_env_value "$env_file" MONGODB_URI || true)
  fi
  if [ -z "${MONGODB_URI:-}" ] && [ -r "$backend_dir/.env" ]; then
    MONGODB_URI=$(read_env_value "$backend_dir/.env" MONGODB_URI || true)
  fi
  export MONGODB_URI
}

# durable plugin files that hold state. keep this list in sync with the docs;
# any other *.json in the data folder is picked up automatically (custom
# guest.snapshot-storage-path, future state files).
SC_STATE_FILES=(
  guest-snapshots.json
  guest-session-counts.json
  account-cache.json
  ws-outbox.json
  bars.json
  spec-restore-journal.json
)

SC_AUDIT_DIR=audit

# prints the plugin state files present in $1, one per line: the known list
# first, then any extra top-level *.json. config.yml and locales/ are not state.
list_plugin_state_files() {
  local dir=$1 name base path known
  for name in "${SC_STATE_FILES[@]}"; do
    [ -f "$dir/$name" ] && printf '%s\n' "$name"
  done
  shopt -s nullglob
  for path in "$dir"/*.json; do
    base=${path##*/}
    known=0
    for name in "${SC_STATE_FILES[@]}"; do
      if [ "$base" = "$name" ]; then
        known=1
        break
      fi
    done
    [ "$known" = 1 ] || printf '%s\n' "$base"
  done
  shopt -u nullglob
}

# finds a live-server lock file under a server root. prints the path and
# returns 0 on the first hit. advisory only: java can be killed and leave a
# stale lock behind, and a server started with a custom world layout can miss
# all of these, so the runbook still says "stop the server".
find_server_lock() {
  local root=$1 candidate
  for candidate in \
    "$root/session.lock" \
    "$root/world/session.lock" \
    "$root/world_nether/session.lock" \
    "$root/world_the_end/session.lock"; do
    if [ -e "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

# writes <archive>.sha256 atomically, prints the digest.
write_checksum() {
  local archive=$1 sum
  sum=$(hash_file "$archive")
  printf '%s  %s\n' "$sum" "${archive##*/}" >"$archive.sha256.partial"
  chmod 600 "$archive.sha256.partial" 2>/dev/null || true
  mv -f "$archive.sha256.partial" "$archive.sha256"
  printf '%s\n' "$sum"
}
