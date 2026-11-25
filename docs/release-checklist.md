# Release checklist

How a release is cut, what CI proves about it, and how to check the published artifacts before anyone
installs them. If a build turns out to be wrong, [the rollback section](#6-when-an-asset-is-wrong) is the
only correct way out — read it before tagging anything.

## 1. The tag is the version

There is no semantic version and no `v1.0.0` anywhere. A build is identified by

```text
build-<commitCount>-<shortSha>
```

— the number of commits reachable from the tagged commit and the first seven characters of that commit's
SHA. The tag is baked into the jar (`paper-plugin.yml` → `version:`), into both asset file names, and it
is what the in-plugin updater compares. Ordering is by `commitCount` only; the SHA identifies the commit
and breaks ties for display, never for update decisions.

Two consequences that drive everything below:

- **A published tag is immutable.** Moving or reusing it either lies about the commit (CI refuses it) or
  keeps the same `commitCount` (installed servers never see the build as newer).
- **A fix is a new commit, not a new upload.** Republishing the same commit gives the same version.

## 2. Before tagging

- [ ] You are on `main`, up to date, clean: `git fetch --tags && git pull --ff-only && git status`
      — a stale clone computes a `commitCount` CI will reject.
- [ ] CI on `main` is green (plugin build + tests, backend typecheck + tests).
- [ ] `CHANGELOG.md` under `[Unreleased]` describes the notable changes — GitHub generates the release
      notes from the commits, the changelog is the curated list operators read.
- [ ] Every user-visible change has its docs updated (`docs/` and `README.md` tables).
- [ ] No local version bumps: `gradle.properties` stays at the development version, the release build
      overrides it with `-Pversion=<tag>`.

Optional local dry run of the same artifact CI will ship:

```bash
./gradlew :plugin:build --no-daemon -Pversion="build-$(git rev-list --count HEAD)-$(git rev-parse --short=7 HEAD)"
```

## 3. Cut the release

```bash
count="$(git rev-list --count HEAD)"
sha="$(git rev-parse --short=7 HEAD)"
git tag "build-${count}-${sha}"
git push origin "build-${count}-${sha}"
```

Pushing a `build-*` tag is the release trigger. Nothing else publishes.

## 4. What CI verifies

`.github/workflows/release.yml`, in order. Any failure aborts before a release exists.

| Step | Refuses to continue when |
|---|---|
| Tag matches the commit | the tag is not `build-<n>-<sha>`, or `<n>` / `<sha>` do not describe the pushed commit (the error prints the tag you should have used) |
| Build the plugin | `:plugin:build` fails with `-Pversion=<tag>` |
| Backend checks | `bun install --frozen-lockfile`, `bun run typecheck` or `bun test` fails |
| Stage assets | `plugin/build/libs/*.jar` is not exactly one jar (a second jar makes the copy fail) |
| Stage the jar | the build did not produce `ServerCore-<tag>.jar` |
| Package the backend | the tarball is built from `package.json bun.lock tsconfig.json src tests .env.example Dockerfile .dockerignore`, with `node_modules` and `.env` excluded |
| Verify the staged jar | the jar is thin (`kz/bejiihiu/servercore/libs/kotlin/collections/CollectionsKt.class` missing) or `paper-plugin.yml` does not carry exactly `version: <tag>` |
| Generate checksums | `sha256sum --text *.jar *.tar.gz > SHA256SUMS.txt` — bare file names, two-space separator, no `./` prefix |
| Publish | an expected asset is missing from the upload (`fail_on_unmatched_files`) |

What CI does **not** prove: that the shaded jar loads on a real Folia server, that the backend actually
boots, and that the checksums parse the way the updater parses them. That is what the verifier below is
for, and it runs after publication — which is the only place the real URLs and asset names exist.

## 5. Verify the published release

```bash
# latest release
deploy/verify-release.sh

# a specific build
deploy/verify-release.sh build-194-dc8a896

# keep the downloaded assets (they are removed otherwise)
deploy/verify-release.sh build-194-dc8a896 --keep
```

Requirements: `bash`, an authenticated `gh`, `unzip`, `tar`, and `sha256sum` (or `shasum` / `openssl`).
No other dependency. `SERVERCORE_REPO=owner/name` points it at a fork; `NO_COLOR=1` turns the colours off.

Exit codes: `0` every check passed, `1` at least one check failed, `2` usage or environment problem (wrong
tag shape, missing tool).

It downloads the three assets into a temp directory and prints one row per check:

| Section | Rows | What a FAIL means |
|---|---|---|
| assets | the jar, the backend tarball and `SHA256SUMS.txt` exist in the release | the release is incomplete — the updater skips a release with no jar |
| checksums | every line is `<64 hex>  <bare name>`; the jar's and the tarball's SHA-256 match | the bytes on the release are not the bytes that were checksummed; the updater refuses such a jar |
| plugin jar | `paper-plugin.yml` is present and parses, `version:` equals the tag, `folia-supported: true`, `packetevents` is a `required: true` server dependency, kotlin stdlib is shaded under `kz/bejiihiu/servercore/libs/kotlin`, no top-level `kotlin/` | the wrong jar was published, or the shading/relocation broke; either way the server will not load it cleanly |
| backend bundle | `src/app.ts`, `package.json`, `Dockerfile` present; no `node_modules`; no `.env` | the bundle cannot be started by an operator, or it leaks a real environment file |

The digest rows use the updater's own rule: split the line on whitespace, require a 64-character hex hash,
and compare the file name with the asset name character for character (case-insensitively). That is why a
`./name` prefix or a `*name` binary marker reads as "no entry" and the update is refused. The format row is
stricter on purpose: the pipeline writes `<hash><two spaces><bare name>`, so anything else means the file
was edited by hand after `sha256sum` produced it.

## 6. When an asset is wrong

Do not announce, do not "fix it in place". Diagnose, then roll back and re-cut.

| Symptom | Likely cause | Action |
|---|---|---|
| `sha-256` mismatch | asset replaced after publication, or checksums generated before the final rename | roll back, find out how the upload diverged from the build |
| `version:` ≠ tag | a locally built or older jar was uploaded; the staged jar was not the CI one | roll back, fix the workflow, re-cut |
| thin jar / `no top-level kotlin/` | shading or relocation config changed | roll back, fix the Gradle config, re-cut |
| `no node_modules` / `no .env` failed | the tar excludes were dropped | **rotate every secret the leaked `.env` held first**, then roll back and re-cut |
| an extra jar on the release | a second asset was uploaded by hand | the updater takes the first `*.jar` it sees; delete the release and re-cut |

### Rollback

1. **Delete the release and its tag.** Nothing installed yet? This is the whole rollback.

   ```bash
   gh release delete build-<n>-<sha> --yes --cleanup-tag
   ```

   `--cleanup-tag` deletes the tag on the remote as well. Without it, delete the tag explicitly:
   `git tag -d build-<n>-<sha> && git push origin :refs/tags/build-<n>-<sha>`.

2. **Fix the cause in a new commit on `main`.** Never `git tag -f`, never force-push `main` to reuse the
   old commit count: a build that keeps its `commitCount` is not "newer" to any installed server, so the
   fix would never be picked up.

3. **Re-cut from the new commit** (section 3). The commit count grows, so the replacement build sorts
   above the broken one.

4. **Re-verify** with `deploy/verify-release.sh <new-tag>` and only then announce.

A deleted release downgrades nobody. Servers that already installed the bad build keep running it until a
newer commit count appears, so the forward build is the actual recovery — deletion only stops new
installs. If the bad release leaked a secret, rotate it regardless of what the release page says now.

## 7. After the release

- [ ] `deploy/verify-release.sh` exits `0` on the new tag.
- [ ] The release page shows exactly three assets and the generated notes.
- [ ] Operators are pointed at the release page, never at a build from source.
- [ ] Installed servers: the updater polls every 30 minutes, installs only a strictly higher commit count,
      verifies the jar against `SHA256SUMS.txt`, and keeps the last three jars in
      `plugins/ServerCore/.backup/`.
