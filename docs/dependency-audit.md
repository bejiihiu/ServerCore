# Dependency and supply-chain audit

Record of the recurring dependency, license and supply-chain pass over this repository. It is a
snapshot: re-run the commands below after any dependency change and update the findings.

**Last run:** 2026-11-25 · backend Bun 1.4.2 · plugin Kotlin 2.4.21 / Gradle 9.9.2 / JDK 25

## How it is run

```bash
# backend
cd backend && bun install --frozen-lockfile && bun audit && bun outdated

# plugin — every resolved coordinate of the configurations that matter
./gradlew :plugin:dependencies --configuration runtimeClasspath
./gradlew :plugin:dependencies --configuration compileClasspath
./gradlew :plugin:dependencies --configuration testClasspath

# advisories: OSV batch query, Maven ecosystem, one query per resolved coordinate
curl -s https://api.osv.dev/v1/querybatch -H 'content-type: application/json' -d @queries.json
```

## Backend

`bun audit` reports **no advisories** across the 94 packages it checks. `bun outdated` lists one safe
patch and one major:

| Package | Pinned | Available | Action |
|---|---|---|---|
| `mongoose` | 9.13.0 | 9.13.1 | bumped to 9.13.1 (patch, lockfile updated) |
| `typescript` (dev) | 5.9.3 | 7.0.2 | left alone — major version, out of scope for a security pass |

The lockfile is committed (`backend/bun.lock`) and `bun install --frozen-lockfile` succeeds, so CI and
the release workflow install exactly the tree that was audited.

## Plugin

113 coordinates resolve across the compile and test classpaths. An OSV batch query returned 68
distinct advisories over 19 of them — all of them reached through `compileOnly` APIs, i.e. artifacts
the server provides and the jar never contains. The runtime classpath (the four artifacts that are
actually shaded in) is clean.

| Component | Version | Reached from | Advisories | Shipped | Verdict |
|---|---|---|---|---|---|
| `io.netty` (15 artifacts) | 4.1.72.Final | `packetevents-spigot:2.14.0` POM | 65 (1 critical, 32 high, 30 moderate, 2 low) | no | server-provided netty; only PacketEvents' own POM pin can move it |
| `com.google.guava:guava` | 33.6.0-jre | `folia-api` | 1 high (CVE-2026-102554, Java deserialization) | no | we never deserialize untrusted Java objects; fixed upstream in 33.7.2 |
| `org.apache.logging.log4j:log4j-api` | 2.26.0 | `folia-api` | 1 moderate (CVE-2026-49844, `MapMessage` JSON encoding) | no | needs `JsonTemplateLayout` + attacker-controlled floats; fixed in 2.26.1 |
| `org.apache.commons:commons-lang3` | 3.12.0 | `folia-api` → `maven-resolver-provider` | 1 moderate (CVE-2025-48924, recursion) | no | build classpath only |
| `org.codehaus.plexus:plexus-utils` | 3.5.1 | `folia-api` → `maven-resolver-provider` | 1 high (CVE-2025-67030, `extractFile` traversal) | no | build classpath only, `extractFile` is never called |

No version bumps were made here on purpose. Forcing a newer netty or guava onto a `compileOnly`
configuration would change a number in the build report without changing a single byte of the shipped
jar or of the deployed server — the runtime classpath for those libraries belongs to Folia and
PacketEvents. What the plugin controls is what it compiles against, and that is pinned in
`gradle/libs.versions.toml` and reviewed as a whole.

The license side of the same graph is in [../THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md).

## Secrets

| Check | Result |
|---|---|
| tracked files (489) × 13 credential patterns | 21 hits, all test fixtures or documentation placeholders (`"test-secret-0123456789abcdef…"`, `CHANGE_ME_STRONG_PASSWORD`, `"the-hex-secret-you-generated"`) |
| full git history (3.5 MB of patches across all refs) | 2 hits, both the same `CHANGE_ME_STRONG_PASSWORD` placeholder in an install example |
| `.env` committed at any point | never |
| live GitHub secret-scanning alerts | none reported |

The plugin reads its HMAC secret from `config.yml` (empty by default) and the backend reads every
credential from the environment; examples and docs carry placeholders only. GitHub-hosted secret
scanning is not available for this repository (no GitHub Advanced Security), so the sweep above is
pattern-based and runs locally — it catches provider-shaped tokens, private keys and credentialed
connection strings, not an unknown custom format.

## Supply-chain hardening

| Item | State |
|---|---|
| workflow actions pinned to a fixed ref | yes — `actions/checkout@v6`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `oven-sh/setup-bun@v2`, `softprops/action-gh-release@v2`. No `@master`, `@main` or branch refs. Tags are still mutable (see open items). |
| Gradle wrapper integrity | `distributionSha256Sum` set for Gradle 9.9.2 and verified against the downloaded distribution; `validateDistributionUrl=true` |
| `curl … \| bash` or remote script execution in CI | none — the workflows only run `./gradlew`, `bun`, `tar`/`unzip` and the distro's `shellcheck` package |
| release asset integrity | `SHA256SUMS.txt` is published with every release and the in-plugin updater refuses a jar whose hash does not match |
| jar contents verified before release | yes — the workflow fails if the shaded Kotlin stdlib is missing or `paper-plugin.yml` does not carry the tag |
| container build | `backend/Dockerfile` installs with `--frozen-lockfile --production`, runs as a non-root user, has a healthcheck |
| repository protocol for dependencies | HTTPS everywhere (Maven Central, papermc, codemc, helpch, npm) |
| backend toolchain in CI | `bun-version: "1.4.2"` pinned in both `ci.yml` and `release.yml` — a Bun release cannot change what CI measures overnight |
| Docker base image | `oven/bun:1.4-slim` — floats within the 1.4 line |
| deploy scripts are linted | `shellcheck -x deploy/*.sh deploy/backup/*.sh` as a blocking `ci` job (the distro package on `ubuntu-latest`), so backup/restore drift is caught before it ships |
| automated dependency updates | Dependabot version updates enabled for `bun` (`/backend`), `gradle` (`/`) and `github-actions` (`/`): weekly, one PR for all minor+patch per ecosystem, majors one per dependency. Auto-merge is **not** enabled — every PR still has to pass `ci` and be merged by hand. Dependabot alerts do not read `bun.lock`, so backend *transitive* advisories are still only caught by `bun audit` |
| Gradle artifact verification | no `gradle/verification-metadata.xml`; artifacts are resolved by coordinate only |

## Open items

Automated (nothing to do by hand):

| Item | Where |
|---|---|
| version updates for backend, plugin and workflow actions | `.github/dependabot.yml` — grouped weekly PRs, majors in their own PR |
| CI/release toolchain drift | `bun-version` pinned to `1.4.2`, JDK 25 and Gradle come from the wrapper + `setup-java`/`setup-gradle@v6` |
| shell correctness of the operator scripts | blocking `shellcheck` job in `.github/workflows/ci.yml` |
| release artifacts are buildable and self-consistent | `Release` dry run (`workflow_dispatch`) builds and verifies the jar, the bundle and `SHA256SUMS.txt`, then stops before publishing |

Still needs a human:

| Item | Why Dependabot/CI cannot do it |
|---|---|
| **MongoDB backups** | Atlas M0 ships no backups, so there is nothing to schedule server-side: someone has to run `deploy/backup/backup.sh` (or an equivalent cron on the host) and keep the archive off the Atlas cluster. The script is linted, not scheduled — see [backup-restore.md](backup-restore.md) |
| refreshing [../THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md) | license text is curated by hand after a dependency change; a bot bump only updates the version |
| pinning workflow actions to commit SHAs | major-version tags are convenient but mutable; a SHA is not, and Dependabot will happily move a pinned SHA for you once the policy is chosen |
| re-checking netty/guava/log4j on the `compileOnly` classpath | those versions belong to Folia and PacketEvents, not to us; a bump here would only change a build-report number |
| Gradle dependency verification | `--write-verification-metadata sha256` is worth doing once the version catalog settles; that is a one-off decision, not an update |
