#!/usr/bin/env bash
#
# Verifies a published ServerCore release the way it is actually consumed: the
# checksums the in-plugin updater parses, the jar the server loads, and the
# backend bundle an operator unpacks. One command, one table, non-zero exit if
# anything is wrong.
#
# usage: deploy/verify-release.sh [tag] [--keep]
#
#   tag      build-<commitCount>-<shortSha>; defaults to the latest release
#   --keep   leave the downloaded assets on disk and print the path
#
# requires: bash, gh (authenticated), unzip, tar and twice a sha-256 tool
# (sha256sum, or shasum/openssl as fallback). nothing else.
#
# SERVERCORE_REPO overrides the repository (default: bejiihiu/ServerCore).

set -euo pipefail

DEFAULT_REPO="bejiihiu/ServerCore"
REPO="${SERVERCORE_REPO:-$DEFAULT_REPO}"
SUMS_ASSET="SHA256SUMS.txt"
KEEP=0
TAG=""

usage() {
    cat <<'EOF'
Verifies a published ServerCore release: downloads the assets, checks
SHA256SUMS.txt the way the in-plugin updater parses it, inspects the plugin jar
and the backend bundle, prints a PASS/FAIL table and exits non-zero on failure.

usage: deploy/verify-release.sh [tag] [--keep]

  tag      build-<commitCount>-<shortSha>; defaults to the latest release
  --keep   leave the downloaded assets on disk and print the path

environment:
  SERVERCORE_REPO   owner/name to read releases from (default: bejiihiu/ServerCore)
  NO_COLOR          set to disable coloured output

requires bash, gh (authenticated), unzip, tar and one of sha256sum / shasum /
openssl. nothing else.
EOF
}

die() {
    printf '%s\n' "$*" >&2
    exit 2
}

while [ "$#" -gt 0 ]; do
    case "$1" in
    -k | --keep)
        KEEP=1
        shift
        ;;
    -h | --help)
        usage
        exit 0
        ;;
    -*)
        die "unknown option: $1 (try --help)"
        ;;
    *)
        [ -z "$TAG" ] || die "only one tag can be verified at a time"
        TAG="$1"
        shift
        ;;
    esac
done

for tool in gh unzip tar; do
    command -v "$tool" >/dev/null 2>&1 || die "missing required tool: $tool"
done

# sha-256 in whatever form the host has; the artifacts are large, so stream
if command -v sha256sum >/dev/null 2>&1; then
    sha256_of() { sha256sum -- "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
    sha256_of() { shasum -a 256 -- "$1" | awk '{print $1}'; }
elif command -v openssl >/dev/null 2>&1; then
    sha256_of() { openssl dgst -sha256 -r -- "$1" | awk '{print $1}'; }
else
    die "missing a sha-256 tool: install sha256sum, shasum or openssl"
fi

if [ -z "$TAG" ]; then
    TAG=$(gh api "repos/${REPO}/releases/latest" --jq .tag_name 2>/dev/null) ||
        die "cannot resolve the latest release of ${REPO} (gh auth status?)"
fi
[ -n "$TAG" ] || die "cannot resolve the latest release of ${REPO}"

if ! [[ "$TAG" =~ ^build-[0-9]+-[0-9a-fA-F]{7,40}$ ]]; then
    die "tag '$TAG' is not build-<commitCount>-<shortSha>; the updater only orders build-* builds"
fi

JAR="ServerCore-${TAG}.jar"
BACKEND="servercore-backend-${TAG}.tar.gz"

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    c_pass=$'\033[32m'
    c_fail=$'\033[31m'
    c_skip=$'\033[33m'
    c_bold=$'\033[1m'
    c_off=$'\033[0m'
else
    c_pass=''
    c_fail=''
    c_skip=''
    c_bold=''
    c_off=''
fi

# every finding is one row of the final table
sections=()
checks=()
statuses=()
details=()
n_pass=0
n_fail=0
n_skip=0

record() { # section, check, status, detail
    sections+=("$1")
    checks+=("$2")
    statuses+=("$3")
    details+=("${4:-}")
    case "$3" in
    PASS) n_pass=$((n_pass + 1)) ;;
    FAIL) n_fail=$((n_fail + 1)) ;;
    *) n_skip=$((n_skip + 1)) ;;
    esac
}

WORK=$(mktemp -d "${TMPDIR:-/tmp}/servercore-verify.XXXXXX") ||
    die "cannot create a temp dir"

cleanup() {
    if [ "$KEEP" -eq 1 ]; then
        printf '\nassets kept in %s\n' "$WORK"
    else
        rm -rf "$WORK"
    fi
}
trap cleanup EXIT

printf '%srelease verification%s  %s\n' "$c_bold" "$c_off" "$TAG"
printf '%-12s %s\n' "repository" "$REPO"
printf '%-12s %s\n\n' "workdir" "$WORK"

# --- assets -----------------------------------------------------------------

download_log="$WORK/gh-download.log"
download_ok=1
if ! gh release download "$TAG" --repo "$REPO" --dir "$WORK" --clobber \
    --pattern "$JAR" --pattern "$BACKEND" --pattern "$SUMS_ASSET" \
    >/dev/null 2>"$download_log"; then
    download_ok=0
fi

missing_detail() {
    if [ "$download_ok" -eq 0 ] && [ -s "$download_log" ]; then
        tr '\n' ' ' <"$download_log" | cut -c1-160
    else
        printf 'no such file in release %s' "$TAG"
    fi
}

asset_row() { # file, role
    local file="$1" role="$2" size
    if [ -f "$WORK/$file" ]; then
        size=$(wc -c <"$WORK/$file" | tr -d ' ')
        record assets "$file ($role)" PASS "${size} bytes"
    else
        record assets "$file ($role)" FAIL "$(missing_detail)"
    fi
}

asset_row "$JAR" "plugin"
asset_row "$BACKEND" "backend"
asset_row "$SUMS_ASSET" "checksums"

have_jar=0
have_backend=0
have_sums=0
[ -f "$WORK/$JAR" ] && have_jar=1
[ -f "$WORK/$BACKEND" ] && have_backend=1
[ -f "$WORK/$SUMS_ASSET" ] && have_sums=1

# --- checksums --------------------------------------------------------------
# the updater splits every line on whitespace (limit 2), requires a 64-char hex
# hash and a bare file name that equals the asset name character for
# character. "./x.jar", "hash *x.jar" or one space all read as "no entry".

if [ "$have_sums" -eq 1 ]; then
    sums_problem=""
    sums_entries=0
    while IFS= read -r line || [ -n "$line" ]; do
        line=${line%$'\r'}
        [ -n "$line" ] || continue
        sums_entries=$((sums_entries + 1))
        if ! [[ "$line" =~ ^[0-9a-fA-F]{64}\ \ [A-Za-z0-9][^[:space:]/]*$ ]]; then
            sums_problem="line $sums_entries is not '<sha256>  <name>': $line"
            break
        fi
    done <"$WORK/$SUMS_ASSET"

    if [ -n "$sums_problem" ]; then
        record checksums "two-space format, bare names" FAIL "$sums_problem"
    elif [ "$sums_entries" -eq 0 ]; then
        record checksums "two-space format, bare names" FAIL "the file is empty"
    else
        record checksums "two-space format, bare names" PASS "$sums_entries entries"
    fi

    sum_for() { # file name -> hash from the checksums file
        awk -v want="$1" '
            length($1) == 64 && NF >= 2 {
                name = $0
                sub(/^[^[:space:]]+[[:space:]]+/, "", name)
                if (tolower(name) == tolower(want)) {
                    print tolower($1)
                    exit
                }
            }
        ' "$WORK/$SUMS_ASSET"
    }

    digest_row() { # file
        local file="$1" expected actual
        expected=$(sum_for "$file")
        if [ -z "$expected" ]; then
            record checksums "$file sha-256" FAIL "no line for this asset; the updater refuses it"
            return
        fi
        if ! actual=$(sha256_of "$WORK/$file" 2>/dev/null); then
            record checksums "$file sha-256" FAIL "cannot hash the downloaded file"
            return
        fi
        if [ "$expected" = "$actual" ]; then
            record checksums "$file sha-256" PASS "${actual:0:16}..."
        else
            record checksums "$file sha-256" FAIL "expected $expected, got $actual"
        fi
    }

    if [ "$have_jar" -eq 1 ]; then
        digest_row "$JAR"
    else
        record checksums "$JAR sha-256" SKIP "asset not downloaded"
    fi
    if [ "$have_backend" -eq 1 ]; then
        digest_row "$BACKEND"
    else
        record checksums "$BACKEND sha-256" SKIP "asset not downloaded"
    fi
else
    record checksums "two-space format, bare names" SKIP "no checksums asset"
    record checksums "$JAR sha-256" SKIP "no checksums asset"
    record checksums "$BACKEND sha-256" SKIP "no checksums asset"
fi

# --- plugin jar -------------------------------------------------------------

manifest="$WORK/paper-plugin.yml"

# not a yaml parser - a structural lint for the failures that actually happen:
# tabs, a key line that is not a key, and the unquoted colon that turns
# paper-plugin.yml into "plugin has no description" garbage
yaml_problem() { # file -> prints the first problem, returns 1 when clean
    local line n=0 val
    while IFS= read -r line || [ -n "$line" ]; do
        n=$((n + 1))
        line=${line%$'\r'}
        case "$line" in
        *$'\t'*)
            printf 'line %d has a tab character (yaml forbids tabs)' "$n"
            return 0
            ;;
        esac
        if [[ "$line" =~ ^[[:space:]]*(#|$) ]]; then
            continue
        fi
        if [[ "$line" =~ ^[[:space:]]*-[[:space:]]+ ]]; then
            continue
        fi
        if [[ ! "$line" =~ ^[[:space:]]*[A-Za-z0-9_.-]+:([[:space:]]+(.*))?$ ]]; then
            printf 'line %d is neither a mapping nor a list item: %s' "$n" "$line"
            return 0
        fi
        val="${BASH_REMATCH[2]:-}"
        val=${val#"${val%%[![:space:]]*}"}
        case "$val" in
        '' | "'"* | '"'* | '['* | '{'* | '|'* | '>'*) continue ;;
        *': '*)
            printf 'line %d has an unquoted colon in its value: %s' "$n" "$val"
            return 0
            ;;
        esac
    done <"$1"
    return 1
}

if [ "$have_jar" -eq 0 ]; then
    for check in \
        "paper-plugin.yml present" \
        "paper-plugin.yml parses" \
        "version equals the tag" \
        "folia-supported: true" \
        "packetevents dependency required" \
        "kotlin stdlib shaded" \
        "no top-level kotlin/"; do
        record "plugin jar" "$check" SKIP "jar not downloaded"
    done
elif ! unzip -p "$WORK/$JAR" paper-plugin.yml >"$manifest" 2>/dev/null || [ ! -s "$manifest" ]; then
    manifest_reason="paper-plugin.yml is missing or unreadable"
    record "plugin jar" "paper-plugin.yml present" FAIL "$manifest_reason"
    for check in \
        "paper-plugin.yml parses" \
        "version equals the tag" \
        "folia-supported: true" \
        "packetevents dependency required" \
        "kotlin stdlib shaded" \
        "no top-level kotlin/"; do
        record "plugin jar" "$check" SKIP "$manifest_reason"
    done
else
    record "plugin jar" "paper-plugin.yml present" PASS "$(wc -c <"$manifest" | tr -d ' ') bytes"

    if problem=$(yaml_problem "$manifest"); then
        record "plugin jar" "paper-plugin.yml parses" FAIL "$problem"
    else
        record "plugin jar" "paper-plugin.yml parses" PASS "structure is valid yaml"
    fi

    declared=$(sed -n 's/^version:[[:space:]]*//p' "$manifest" | head -n 1)
    declared=${declared#[\"\']}
    declared=${declared%[\"\']}
    if [ "$declared" = "$TAG" ]; then
        record "plugin jar" "version equals the tag" PASS "version: $declared"
    elif [ -z "$declared" ]; then
        record "plugin jar" "version equals the tag" FAIL "no top-level version: key"
    else
        record "plugin jar" "version equals the tag" FAIL "paper-plugin.yml says '$declared', tag is '$TAG'"
    fi

    if grep -Eq '^folia-supported:[[:space:]]*true[[:space:]]*$' "$manifest"; then
        record "plugin jar" "folia-supported: true" PASS
    else
        record "plugin jar" "folia-supported: true" FAIL "the server would refuse to load this build"
    fi

    if awk '
        /^[[:space:]]*packetevents:[[:space:]]*$/ {
            match($0, /^[[:space:]]*/)
            depth = RLENGTH
            inblock = 1
            next
        }
        inblock {
            match($0, /^[[:space:]]*/)
            ind = RLENGTH
            if ($0 ~ /^[[:space:]]*(#|$)/) next
            if (ind <= depth) {
                inblock = 0
                next
            }
            if ($0 ~ /^[[:space:]]*required:[[:space:]]*true[[:space:]]*$/) found = 1
        }
        END { exit(found ? 0 : 1) }
    ' "$manifest"; then
        record "plugin jar" "packetevents dependency required" PASS "load: BEFORE, required: true"
    else
        record "plugin jar" "packetevents dependency required" FAIL "no 'packetevents: required: true' under dependencies.server"
    fi

    if unzip -Z1 "$WORK/$JAR" >"$WORK/jar-entries.txt" 2>/dev/null; then
        if grep -q '^kz/bejiihiu/servercore/libs/kotlin/collections/CollectionsKt\.class$' "$WORK/jar-entries.txt"; then
            record "plugin jar" "kotlin stdlib shaded" PASS "relocated under kz/bejiihiu/servercore/libs/kotlin"
        else
            record "plugin jar" "kotlin stdlib shaded" FAIL "no relocated CollectionsKt - this is a thin jar"
        fi
        if grep -q '^kotlin/' "$WORK/jar-entries.txt"; then
            record "plugin jar" "no top-level kotlin/" FAIL "unrelocated kotlin classes would clash with the server"
        else
            record "plugin jar" "no top-level kotlin/" PASS
        fi
    else
        record "plugin jar" "kotlin stdlib shaded" SKIP "cannot list the jar"
        record "plugin jar" "no top-level kotlin/" SKIP "cannot list the jar"
    fi
fi

# --- backend bundle ---------------------------------------------------------

if [ "$have_backend" -eq 0 ]; then
    for check in \
        "src/app.ts present" \
        "package.json present" \
        "Dockerfile present" \
        "no node_modules" \
        "no .env"; do
        record "backend bundle" "$check" SKIP "bundle not downloaded"
    done
elif ! tar -tzf "$WORK/$BACKEND" >"$WORK/tar-entries.txt" 2>/dev/null; then
    bad="tar cannot list the bundle (truncated or not gzip)"
    record "backend bundle" "tar readable" FAIL "$bad"
    for check in \
        "src/app.ts present" \
        "package.json present" \
        "Dockerfile present" \
        "no node_modules" \
        "no .env"; do
        record "backend bundle" "$check" SKIP "$bad"
    done
else
    entries="$WORK/tar-entries.txt"

    if grep -Eq '^(\./)?src/app\.ts$' "$entries"; then
        record "backend bundle" "src/app.ts present" PASS
    else
        record "backend bundle" "src/app.ts present" FAIL "entry point missing from the bundle"
    fi

    if grep -Eq '^(\./)?package\.json$' "$entries"; then
        record "backend bundle" "package.json present" PASS
    else
        record "backend bundle" "package.json present" FAIL "bun install would fail on the unpacked bundle"
    fi

    if grep -Eq '^(\./)?Dockerfile$' "$entries"; then
        record "backend bundle" "Dockerfile present" PASS
    else
        record "backend bundle" "Dockerfile present" FAIL "docker-compose deploy path is broken"
    fi

    if grep -Eq '(^|/)node_modules(/|$)' "$entries"; then
        record "backend bundle" "no node_modules" FAIL "the bundle ships installed dependencies"
    else
        record "backend bundle" "no node_modules" PASS
    fi

    if grep -Eq '(^|/)\.env$' "$entries"; then
        record "backend bundle" "no .env" FAIL "a real .env with secrets would be published"
    else
        record "backend bundle" "no .env" PASS "only .env.example travels"
    fi
fi

# --- report -----------------------------------------------------------------

print_table() {
    [ "${#checks[@]}" -gt 0 ] || return 0
    local i sw=${#sections[0]} cw=${#checks[0]} color rule
    for ((i = 0; i < ${#checks[@]}; i++)); do
        [ "${#sections[i]}" -gt "$sw" ] && sw=${#sections[i]}
        [ "${#checks[i]}" -gt "$cw" ] && cw=${#checks[i]}
    done
    rule=$(printf '%*s' "$((sw + cw + 32))" '')
    rule=${rule// /-}
    printf '%-*s  %-*s  %-4s  %s\n' "$sw" "SECTION" "$cw" "CHECK" "RSLT" "DETAIL"
    printf '%s\n' "$rule"
    for ((i = 0; i < ${#checks[@]}; i++)); do
        case "${statuses[i]}" in
        PASS) color=$c_pass ;;
        FAIL) color=$c_fail ;;
        *) color=$c_skip ;;
        esac
        printf '%-*s  %-*s  %s%-4s%s  %s\n' \
            "$sw" "${sections[i]}" \
            "$cw" "${checks[i]}" \
            "$color" "${statuses[i]}" "$c_off" \
            "${details[i]}"
    done
}

print_table
printf '\n%s%d passed, %d failed, %d skipped%s\n' "$c_bold" "$n_pass" "$n_fail" "$n_skip" "$c_off"

if [ "$n_fail" -gt 0 ]; then
    printf '%srelease %s is NOT installable%s\n' "$c_fail" "$TAG" "$c_off"
    exit 1
fi

printf '%srelease %s looks installable%s\n' "$c_pass" "$TAG" "$c_off"
