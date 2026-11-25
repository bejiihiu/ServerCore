# Third-party licenses

ServerCore is proprietary software — see [LICENSE](LICENSE). Copyright 2026 bejiihiu, all rights reserved.
That license covers the code in this repository only. Every third-party component that the build
resolves, bundles or requires at runtime stays under its own license, listed below.

This file was generated from package metadata on 2026-11-25: the backend graph comes from the installed
tree in `backend/node_modules` (as pinned by `backend/bun.lock`), the plugin graph from the
`compileClasspath`/`testClasspath` configurations resolved by Gradle. License identifiers are the ones
the published `package.json` or Maven POM declares; where a POM carries no `<licenses>` block the
upstream repository LICENSE file was read instead and the row says so.

## What actually ships

| Artifact | Third-party code inside it |
|---|---|
| `ServerCore-<tag>.jar` (plugin) | Kotlin stdlib, kotlinx-serialization (json + core), JetBrains annotations. All Apache-2.0. Everything else is `compileOnly` and supplied by the server. |
| `servercore-backend-<tag>.tar.gz` | No vendored code. Dependencies are installed from the npm registry on the target host, from the committed lockfile. |

Four of the compile-only APIs are GPL-3.0 (Folia, PacketEvents, PlaceholderAPI, FlectonePulse). No code
from them is compiled into, linked into, or redistributed with either artifact — the plugin only
compiles against their interfaces and expects the server to provide the implementations. Consumers who
install those plugins on the same server take on their licenses separately.

## Backend — runtime dependencies

Direct and transitive, resolved from `backend/package.json` + `backend/bun.lock` (73 packages).

| Package | Version | License | Direct |
|---|---|---|---|
| @fastify/ajv-compiler | 4.0.8 | MIT |  |
| @fastify/deepmerge | 3.2.4 | MIT |  |
| @fastify/error | 4.3.0 | MIT |  |
| @fastify/fast-json-stringify-compiler | 5.2.0 | MIT |  |
| @fastify/forwarded | 3.1.2 | MIT |  |
| @fastify/merge-json-schemas | 0.2.2 | MIT |  |
| @fastify/proxy-addr | 5.1.3 | MIT |  |
| @grammyjs/conversations | 2.1.1 | MIT | yes |
| @grammyjs/types | 5.2.0 | MIT |  |
| @mongodb-js/saslprep | 1.6.3 | MIT |  |
| @pinojs/redact | 0.4.0 | MIT |  |
| @standard-schema/spec | 1.1.0 | MIT |  |
| @types/webidl-conversions | 7.0.3 | MIT |  |
| @types/whatwg-url | 13.0.0 | MIT |  |
| abort-controller | 3.0.0 | MIT |  |
| abstract-logging | 2.0.1 | MIT |  |
| ajv | 8.20.0 | MIT |  |
| ajv-formats | 3.0.1 | MIT |  |
| atomic-sleep | 1.0.0 | MIT |  |
| avvio | 9.3.1 | MIT |  |
| bson | 7.3.4 | Apache-2.0 |  |
| cookie | 2.0.2 | MIT |  |
| debug | 4.4.3 | MIT |  |
| dequal | 2.0.3 | MIT |  |
| event-target-shim | 5.0.1 | MIT |  |
| fast-decode-uri-component | 1.0.1 | MIT |  |
| fast-deep-equal | 3.1.3 | MIT |  |
| fast-json-stringify | 7.3.10 | MIT |  |
| fast-querystring | 1.1.2 | MIT |  |
| fast-uri | 4.2.3 | BSD-3-Clause |  |
| fastify | 5.12.8 | MIT | yes |
| fastq | 1.20.3 | ISC |  |
| find-my-way | 9.10.2 | MIT |  |
| grammy | 1.48.0 | MIT | yes |
| ipaddr.js | 2.5.0 | MIT |  |
| json-schema-ref-resolver | 3.0.1 | MIT |  |
| json-schema-traverse | 1.0.0 | MIT |  |
| kareem | 3.4.1 | Apache-2.0 |  |
| light-my-request | 6.8.1 | BSD-3-Clause |  |
| memory-pager | 1.5.0 | MIT |  |
| mongodb | 7.9.1 | Apache-2.0 |  |
| mongodb-connection-string-url | 7.0.3 | Apache-2.0 |  |
| mongoose | 9.13.1 | MIT | yes |
| mpath | 0.9.0 | MIT |  |
| mquery | 6.0.1 | MIT |  |
| ms | 2.1.3 | MIT |  |
| node-fetch | 2.7.0 | MIT |  |
| on-exit-leak-free | 2.1.2 | MIT |  |
| pino | 10.4.0 | MIT | yes |
| pino-abstract-transport | 3.0.0 | MIT |  |
| pino-std-serializers | 7.1.0 | MIT |  |
| process-warning | 5.1.1 | MIT |  |
| quick-format-unescaped | 4.0.4 | MIT |  |
| real-require | 1.0.0 | MIT |  |
| require-from-string | 2.0.2 | MIT |  |
| ret | 0.5.1 | MIT |  |
| reusify | 1.1.0 | MIT |  |
| rfdc | 1.4.1 | MIT |  |
| safe-regex2 | 5.1.2 | MIT |  |
| safe-stable-stringify | 2.5.0 | MIT |  |
| secure-json-parse | 4.1.1 | BSD-3-Clause |  |
| semver | 7.8.6 | ISC |  |
| set-cookie-parser | 3.2.3 | MIT |  |
| sift | 17.1.4 | MIT |  |
| sonic-boom | 5.0.2 | MIT |  |
| sparse-bitfield | 3.0.3 | MIT |  |
| split2 | 4.2.0 | ISC |  |
| thread-stream | 4.2.1 | MIT |  |
| toad-cache | 3.7.4 | MIT |  |
| tr46 | 0.0.3 | MIT |  |
| webidl-conversions | 3.0.1 | BSD-2-Clause |  |
| whatwg-url | 5.0.0 | MIT |  |
| zod | 4.6.7 | MIT | yes |

## Backend — development dependencies

Not shipped and not part of the runtime image (`bun install --production` in `backend/Dockerfile`).

| Package | Version | License |
|---|---|---|
| @types/bun | 1.4.5 | MIT |
| fast-check | 4.11.0 | MIT |
| pino-pretty | 13.2.0 | MIT |
| typescript | 5.9.3 | Apache-2.0 |

## Plugin — bundled into the shaded jar

These are relocated under `kz.bejiihui.servercore.libs` by the shadow plugin and are the only
third-party code distributed inside `ServerCore-<tag>.jar`.

| Artifact | Version | License |
|---|---|---|
| org.jetbrains:annotations | 13.0 | Apache-2.0 |
| org.jetbrains.kotlin:kotlin-stdlib | 2.4.21 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-serialization-core | 1.13.0 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.13.0 | Apache-2.0 |

## Plugin — compile-only, provided by the server

Compiled against, never bundled. The server (or the admin installing it) supplies each of these.

| Artifact | Version | License |
|---|---|---|
| dev.folia:folia-api | 26.3.build.27-stable | GPL-3.0 |
| net.flectone.pulse:api | 1.15.2 | GPL-3.0 |
| com.github.retrooper:packetevents-spigot | 2.14.0 | GPL-3.0 |
| net.luckperms:api | 5.5 | MIT |
| org.jetbrains.kotlinx:kotlinx-coroutines-core | 1.11.0 | Apache-2.0 |
| me.clip:placeholderapi | 2.12.3 | GPL-3.0 |

## Plugin — test only

Test source set, never shipped.

| Artifact | Version | License |
|---|---|---|
| org.jetbrains.kotlin:kotlin-test-junit5 | 2.4.21 | Apache-2.0 |
| org.jetbrains.kotlin:kotlin-test | 2.4.21 | Apache-2.0 |
| org.junit.jupiter:junit-jupiter | 6.1.3 | EPL-2.0 |
| org.junit:junit-bom | 6.1.3 | EPL-2.0 |
| org.junit.jupiter:junit-jupiter-api | 6.1.3 | EPL-2.0 |
| org.junit.jupiter:junit-jupiter-engine | 6.1.3 | EPL-2.0 |
| org.junit.jupiter:junit-jupiter-params | 6.1.3 | EPL-2.0 |
| org.junit.platform:junit-platform-commons | 6.1.3 | EPL-2.0 |
| org.junit.platform:junit-platform-engine | 6.1.3 | EPL-2.0 |
| org.junit.platform:junit-platform-launcher | 6.1.3 | EPL-2.0 |
| org.opentest4j:opentest4j | 1.3.0 | Apache-2.0 |

## Plugin — notable transitive dependencies

Pulled in by the compile-only APIs above, so they reach the build classpath but never the jar. Grouped
by Maven groupId; the counts are distinct artifacts, not distinct versions.

| Group | Artifacts | Versions | License |
|---|---|---|---|
| `biz.aQute.bnd` | 1 | 7.1.0 | Apache-2.0 |
| `com.github.retrooper` | 2 | 2.14.0 | GPL-3.0 |
| `com.google.auto.service` | 1 | 1.1.1 | Apache-2.0 |
| `com.google.code.gson` | 1 | 2.14.0 | Apache-2.0 |
| `com.google.errorprone` | 1 | 2.48.0 | Apache-2.0 |
| `com.google.guava` | 3 | 1.0.3, 33.6.0-jre, 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 |
| `com.google.j2objc` | 1 | 3.1 | Apache-2.0 |
| `com.mojang` | 1 | 1.3.11 | MIT |
| `commons-codec` | 1 | 1.16.0 | Apache-2.0 |
| `io.netty` | 28 | 2.0.46.Final, 4.1.72.Final | Apache-2.0 |
| `it.unimi.dsi` | 1 | 8.5.18 | Apache-2.0 |
| `javax.inject` | 1 | 1 | Apache-2.0 |
| `net.kyori` | 14 | 1.1.0, 1.3.0, 5.2.0 | MIT |
| `net.md-5` | 1 | 1.21-R0.2-deprecated+build.21 | BSD-3-Clause |
| `org.apache.commons` | 1 | 3.12.0 | Apache-2.0 |
| `org.apache.httpcomponents` | 2 | 4.4.16, 4.5.14 | Apache-2.0 |
| `org.apache.logging.log4j` | 1 | 2.26.0 | Apache-2.0 |
| `org.apache.maven` | 6 | 3.9.6 | Apache-2.0 |
| `org.apache.maven.resolver` | 7 | 1.9.18 | Apache-2.0 |
| `org.checkerframework` | 1 | 4.2.3 | MIT |
| `org.codehaus.plexus` | 2 | 1.26, 3.5.1 | Apache-2.0 |
| `org.eclipse.sisu` | 1 | 0.9.0.M2 | EPL-2.0 |
| `org.jetbrains.kotlinx` | 5 | 1.11.0, 1.13.0 | Apache-2.0 |
| `org.joml` | 1 | 1.10.9 | MIT |
| `org.jspecify` | 1 | 1.0.0 | Apache-2.0 |
| `org.osgi` | 4 | 1.0.0, 1.1.2, 2.0.0 | Apache-2.0 |
| `org.slf4j` | 2 | 1.7.36, 2.0.17 | MIT / Apache-2.0 |
| `org.yaml` | 1 | 2.2 | Apache-2.0 |

## License summary

| License | Components |
|---|---|
| MIT | 84 |
| Apache-2.0 | 83 |
| EPL-2.0 | 9 |
| GPL-3.0 | 6 |
| BSD-3-Clause | 4 |
| ISC | 3 |
| BSD-2-Clause | 1 |

Counts cover every component listed above, with transitive duplicates counted once per artifact.

## Regenerating

```bash
cd backend && bun install --frozen-lockfile && bun pm ls --all   # backend tree
./gradlew :plugin:dependencies --configuration compileClasspath  # plugin tree
```

The license for each node is the one its own metadata declares; re-run the collection after any
dependency change so this file does not drift from `bun.lock` and `gradle/libs.versions.toml`.
