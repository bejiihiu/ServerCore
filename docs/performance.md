# Performance

**Languages:** English | [Русский](ru/performance.md)

Measured hot paths of the plugin: anti-ESP visibility (ray march, spatial grid,
decision core), the packet view-state layer, the packet-state sync executor, the
info bars and the WebSocket envelope codec. The goal of this page is not to
claim a capacity number — it is to keep a reproducible baseline so a change that
makes a hot path slower is visible before it ships.

## Methodology

The suite is a plain Kotlin microbenchmark runner, not JMH.

- **Why not JMH.** JMH wants its own source set, an annotation processor and a
  fork per benchmark. Everything measured here is pure Kotlin on a warm JVM with
  no static state outside the instances the suite creates, so the parts of JMH
  that actually matter (dead-code defeat, calibration, warmup, sample
  distribution) are reimplemented in about 120 lines. That keeps the suite
  dependency-free and the build time at seconds.
- **Protocol per benchmark.** Calibrate a batch size so one sample is roughly
  2 ms, warm up for 1 s of wall clock so C2 has compiled the path, then collect
  samples for another 1.5 s. One sample is one batch; `ns/op` is batch time over
  batch size. `p50`/`p99` are percentiles over the sample distribution and
  `ops/s` is derived from `p50`. Every result is folded into a volatile sink so
  the JIT cannot delete the work.
- **Allocation column.** `B/op` comes from the JVM thread allocation counter,
  averaged over the measured window. It includes garbage the benchmark itself
  creates (lambda results are boxed only if the measured operation returns a
  primitive), so treat it as a relative signal, not an exact figure.
- **Shapes.** Sizes are the ones the engine really produces: 10/30/50-block
  rays with a mix of directions, chunk radius 4 (48-block managed radius on
  16-block chunks), 100/500/2000 tracked players, 200 viewers × 200 targets for
  the packet registry, 64- and 512-target caps for the store (512 is the
  production cap), 100 targets for `resetViewer`.
- **Not covered.** Anything that needs a live server: Folia region scheduling
  and the cross-region raytrace fan-out, PacketEvents wrapper parsing, bossbar
  packet sends, and the WebSocket transport itself. The pure halves measured
  here are the ones that run per packet/per cycle and are the ones a code change
  can break.

### Running it

```bash
./gradlew :plugin:benchmark                     # full suite, ~90 s
./gradlew :plugin:benchmark -Pbench.filter=grid # one group (substring match)
```

The task compiles the `benchmark` source set against the plugin sources (it is
never packaged into the jar and never part of `build`) and runs it in one forked
JVM with `-Xms1g -Xmx1g`. Output is a markdown table on stdout; paste the rows
you care about into this file.

### Hardware

All numbers below come from a **CI-class desktop runner**, not the production
host: OpenJDK 25.0.2 (Temurin), 16 vCPU, Windows 11 x64, 1 GiB heap, no CPU
pinning, background OS noise present. Read them as **relative** numbers — the
ratio between two commits measured on the same machine is meaningful, the
absolute nanoseconds are not a server capacity claim. `p99` on this runner
moves ±30% run to run; decisions in this document were made on `p50`.

## Current numbers

Measured on the commit that added this page (after the fixes below).

| benchmark | p50 ns/op | p99 ns/op | ops/s (p50) | B/op | notes |
|---|---:|---:|---:|---:|---|
| **esp / raymarch** | | | | | |
| dda 10b | 87.3 | 173 | 11,459,726 | 462 | ~13 voxels/ray |
| dda 30b | 201 | 436 | 4,965,732 | 1,276 | ~42 voxels/ray |
| dda 50b | 300 | 493 | 3,331,851 | 2,140 | ~73 voxels/ray |
| scan open 30b | 26.2 | 178 | 38,188,277 | 0.02 | no occluder, walks the whole list |
| scan tree line 30b | 11.7 | 41.9 | 85,360,000 | 0.01 | ~1 in 7 voxels solid |
| scan wall@8 30b | 30.0 | 34.6 | 33,367,347 | 0.11 | early break after 8 blocks |
| trace prep 30b | 718 | 2,190 | 1,393,534 | 3,876 | voxels + y-clamp + groupBy chunk |
| **esp / spatial grid (radius 4 cells)** | | | | | |
| nearby 100p | 372 | 962 | 2,684,783 | 2,024 | 6 candidates/query |
| nearby 500p | 1,728 | 3,064 | 578,608 | 2,481 | 34 candidates/query |
| nearby 2000p | 3,306 | 5,246 | 302,492 | 4,004 | 140 candidates/query |
| update unchanged 500p | 25.9 | 53.2 | 38,589,101 | 24 | standing player, one map put |
| update moved 500p | 135 | 318 | 7,390,146 | 351 | remove + add across buckets |
| **esp / pair visibility decider** | | | | | |
| decide matrix (12 shapes) | 9.09 | 15.8 | 110,019,468 | 5.3 | mix of verdict shapes |
| decide disguise off (12 shapes) | 8.36 | 14.1 | 119,638,449 | 0 | |
| needsLineOfSight (12 shapes) | 7.50 | 9.32 | 133,283,174 | 0 | gate chain, no verdict |
| **packet / state registry (200 viewers × 200 targets)** | | | | | |
| targetOf hit | 20.7 | 42.8 | 48,212,373 | 5.8 | 1 outer + 1 inner map lookup |
| targetOf miss (unknown viewer) | 6.43 | 8.96 | 155,505,375 | 0 | |
| state hit | 28.3 | 50.5 | 35,333,478 | 0 | |
| set override | 34.5 | 74.8 | 29,016,907 | 16 | |
| bind + unbind cycle | 100 | 200 | 10,000,000 | 572 | spawn then destroy for one id |
| **packet / cached view state store** | | | | | |
| get hit | 19.2 | 45.7 | 52,003,949 | 0.01 | |
| get miss | 18.3 | 37.0 | 54,694,425 | 0 | |
| set existing pair | 24.6 | 38.6 | 40,626,109 | 16 | 64-target cap, no eviction owed |
| set new pair @64 cap | 504 | 864 | 1,982,493 | 68.9 | scans 64 entries for the stalest |
| set new pair @512 cap | 1,384 | 6,698 | 722,543 | 52.6 | scans 512 entries (production cap) |
| **sync / packet resync executor** | | | | | |
| apply HIDDEN | 62.3 | 98.5 | 16,048,183 | 32 | |
| apply VISIBLE | 43.0 | 105 | 23,261,759 | 16 | clears policy row, registry row stays |
| apply DISGUISED (no disguise layer) | 66.0 | 127 | 15,149,165 | 32 | degrades to HIDDEN |
| resyncTarget | 46.6 | 115 | 21,482,117 | 16 | policy get + execute |
| resetViewer (100 targets) | 3,600 | 11,800 | 277,778 | 6,780 | re-asserts every policy row |
| **bars / formatting and render** | | | | | |
| formats gigabytes | 74.1 | 129 | 13,493,976 | 504 | `String.format` |
| formats percent | 71.2 | 188 | 14,051,512 | 552 | `String.format` |
| formats tps | 93.7 | 223 | 10,670,975 | 552 | `String.format` |
| formats clock | 130 | 408 | 7,714,286 | 624 | `String.format` |
| theme render (MiniMessage) | 5,800 | 10,400 | 172,414 | 18,828 | regex substitute + tag parse |
| presentation line | 7,200 | 22,900 | 138,889 | 19,500 | locale lookup + render + mark |
| locale text (raw string) | 3,101 | 6,039 | 322,438 | 8,144 | |
| **websocket / envelope codec** | | | | | |
| encode SERVER_STATUS | 1,915 | 5,869 | 522,088 | 4,403 | payload + canonical + hmac |
| decode SERVER_STATUS | 1,700 | 2,300 | 588,235 | 4,420 | size check + parse + hmac verify |
| encode LOCALE_PUSH (raw) | 1,908 | 4,005 | 524,093 | 3,792 | raw payload passes through |
| decode LOCALE_PUSH (raw) | 1,781 | 3,929 | 561,367 | 4,304 | |
| canonicalize payload | 307 | 626 | 3,253,654 | 488 | |
| newMessageId | 306 | 703 | 3,270,925 | 320 | SecureRandom + base64url |

## Fixes made

Each of these keeps behaviour identical; tests pin the semantics (eviction order,
wire bytes, raytrace voxel lists, decision matrix).

### 1. View-state store: no boxing per read, no allocation per write, primitive eviction scan

`packet/CachedViewStateStore` is on the path of every entity packet: a viewer
`get` for each metadata/equipment/spawn check. It used to keep two parallel maps
(`viewers` and `viewerAccess`), so a single `get` was two map reads plus a boxed
`Long` write, and a `set` allocated a fresh `Entry` even when re-applying the
same row. Eviction scanned the 512-entry cap with `minByOrNull`, which boxes on
every comparison.

Now the recency stamp lives on a per-viewer holder object (one map read, no
boxing), an existing row is mutated in place, and both capacity scans compare
primitive longs.

| benchmark | before p50 | after p50 | before B/op | after B/op |
|---|---:|---:|---:|---:|
| get hit | 35.3 ns | 19.2 ns | 24 | ~0 |
| get miss | 32.6 ns | 18.3 ns | 24 | 0 |
| set existing pair | 53.6 ns | 24.6 ns | 64 | 16 |
| set new pair @64 cap | 669 ns | 504 ns | 94 | 69 |
| set new pair @512 cap | 5,220 ns | 1,384 ns | 85 | 53 |
| state hit (via registry) | 42.6 ns | 28.3 ns | 24 | 0 |
| set override (via registry) | 60.9 ns | 34.5 ns | 64 | 16 |

Run-to-run range on this runner: `set new pair @512 cap` measured 2.2–5.2 µs
before and 1.4–1.9 µs after, so read the direction, not the exact ratio.

### 2. Resync executor follows the store down

The executor is a thin wrapper over the store, so it inherits the win. Nothing
in `PacketResyncExecutor` itself changed.

| benchmark | before p50 | after p50 |
|---|---:|---:|
| apply HIDDEN | 94.6 ns | 62.3 ns |
| apply VISIBLE | 100 ns | 43.0 ns |
| apply DISGUISED (degraded) | 89.0 ns | 66.0 ns |
| resyncTarget | 73.9 ns | 46.6 ns |
| resetViewer (100 targets) | 6,300 ns | 3,600 ns |

### 3. Ray march: right-sized traversal list, no throwaway end voxel

The DDA now sizes the result list from the Manhattan distance between start and
end voxel (capped at the 1024 hard limit) instead of growing from 10, and the
"drop the end voxel" check compares ints instead of constructing a `VoxelPos` to
compare against.

| benchmark | before p50 | after p50 | before B/op | after B/op |
|---|---:|---:|---:|---:|
| dda 10b | 93.1 ns | 87.3 ns | 561 | 462 |
| dda 30b | 257 ns | 201 ns | 1,719 | 1,276 |
| dda 50b | 408 ns | 300 ns | 3,016 | 2,140 |
| trace prep 30b (dda + clamp + groupBy) | 817 ns | 718 ns | 4,294 | 3,876 |

### 4. Decision core: shared verdicts for the two common answers

`PairVisibilityDecider` minted a fresh `PairVerdict` for every visible/hidden
decision — two objects per pair per refresh. The two stateless verdicts are now
shared constants (`PairVerdict.VISIBLE`/`HIDDEN`); `PairVerdict` is a data class,
so equality-based consumers cannot tell the difference.

| benchmark | before p50 | after p50 | before B/op | after B/op |
|---|---:|---:|---:|---:|
| decide matrix (12 shapes) | 10.6 ns | 9.09 ns | 23.3 | 5.3 |
| decide disguise off (12 shapes) | 9.25 ns | 8.36 ns | 22.0 | 0 |

### 5. Envelope codec: one canonical pass, warm MAC, hex without `String.format`

Three separate problems in the per-frame path:

- `hmac` created a `Mac` and initialised a key spec on every frame, then hexed
  the 12-byte digest with `"%02x".format(byte)` — one `Formatter` allocation per
  byte, so ~12 formatters and 24 KB of garbage per signed frame. It now uses a
  thread-local initialised `Mac` and `HexFormat.formatHex`.
- `encode` built the payload object and the meta object twice (once for the
  signature, once for the frame) and canonicalised the meta twice. It builds and
  canonicalises once.
- `canonicalize` recursed through `joinToString`, minting a `String` per key and
  per value at every nesting level. It now appends into one `StringBuilder`, and
  `decode` rejects oversized frames from `length`/`length*3` bounds before
  copying the string to measure UTF-8 bytes.

| benchmark | before p50 | after p50 | before B/op | after B/op |
|---|---:|---:|---:|---:|
| encode SERVER_STATUS | 5,450 ns | 1,915 ns | 24,219 | 4,403 |
| decode SERVER_STATUS | 4,900 ns | 1,700 ns | 20,908 | 4,420 |
| encode LOCALE_PUSH (raw) | 6,076 ns | 1,908 ns | 25,280 | 3,792 |
| decode LOCALE_PUSH (raw) | 5,206 ns | 1,781 ns | 21,817 | 4,304 |
| canonicalize payload | 587 ns | 307 ns | 1,856 | 488 |

The wire format is byte-identical: the protocol suite compares the plugin's
output against fixed backend-produced frames and signatures.

## Measured and rejected

**Packed chunk keys in `SpatialGrid`.** Replacing the `Cell` bucket key with a
`(world, cx, cz)` → `long` lookup per world was expected to remove 81 throwaway
`Cell` objects per query. It measured *worse*: `nearby 2000p` went from 3,306 ns
to 4,944 ns and 4,004 B/op to 6,730 B/op. Boxing a `Long` per probe costs more
than the data-class `Cell` (whose string hash is cached and whose `equals` hits
the same `String` reference), and pre-sizing the candidate list to two per bucket
over-allocates at low density. Reverted; the original is kept.

## Deliberately not changed

| Area | Why it stays |
|---|---|
| `trace prep` chunk grouping (3.9 KB, 718 ns per ray) | `RegionSafeRaytracer` groups voxels by `SpatialGrid.Cell` and schedules per chunk. Flattening the filter+group into one pass and grouping by packed coords would cut the allocation roughly in half, but the class needs a live `World` and `RegionScheduler`, so there is no test that could prove behaviour unchanged. Worth a session with a server, not a blind edit. |
| Bar title render (7.2 µs / 19.5 KB per line) | Almost all of it is Adventure MiniMessage tag parsing plus the locale regex substitution. A per-template component cache would be a design change (cache key = template + value set, invalidation on locale push), not a local optimisation. The formatting helpers themselves are 70–130 ns and not worth hand-rolling `String.format` rounding. |
| `String.format` in `BarFormats` | 500 B and ~75 ns per call, and only once per bar per interval. Replacing `%.1f` by hand risks changing rounding at the edges for no measurable win. |
| `decode`'s "envelope minus hmac" copy | ~200 ns of the remaining 1.7 µs; removing it needs a second canonicaliser that could drift from the wire contract. |
| `SpatialGrid.nearby` allocations | See the numbers below — the cost is real but the fix is a caching redesign, not a local change. |
| Time-based expiry in the view-state store | Removed on purpose earlier: the store holds the applied client picture, which cannot be recomputed, so expiry is a fail-open bug, not a performance lever. |

## What the numbers mean at scale

Arithmetic, not measurement — the assumptions are stated so they can be argued
with.

- **Grid query is the per-cycle allocation driver.** `candidatePairs` calls
  `nearby` once per tracked player. At 2000 players that is 2000 × 3.3 µs ≈
  **6.6 ms of global-region time per engine cycle** and 2000 × 4 KB ≈ **8 MB of
  garbage per cycle**. At the default `check-interval-ticks: 10` (2 cycles/s)
  that is ~13 ms/s of CPU and ~16 MB/s of allocation — tolerable. At
  `check-interval-ticks: 1` it would be ~132 ms/s and ~160 MB/s, which is not;
  keep the interval at 10 or higher with four-figure player counts.
- **Ray march is not the bottleneck.** A 30-block ray is ~0.2 µs and the whole
  pure half of a trace (two rays plus chunk grouping) is under 1.5 µs. Even if
  every budgeted pair (2 × players per cycle) needed a fresh trace, 2000 players
  would spend single-digit milliseconds per cycle here.
- **Store writes at capacity** are the per-packet cost that used to be ugly:
  with a viewer at the 512-row cap, every genuinely new target paid a 2–5 µs
  scan. A busy 2000-player server can produce a few thousand new-target rows per
  second; at 5 µs that was 10–20 ms/s of packet-thread time, now 3–5 ms/s.
- **Bars are opt-in and cheap per region.** A bar line costs 7.2 µs and 19.5 KB,
  rendered on the player's own region thread once per interval. A region holding
  20 players pays ~144 µs/s. The garbage is the number to watch: if all 2000
  players enabled a bar at a 20-tick interval, that is ~39 MB/s of short-lived
  components.
- **The WebSocket codec is not a hot path anymore.** ~3.6 µs and ~9 KB for an
  encode+decode pair; at heartbeat/presence rates this is noise.

Nothing measured here looks like a wall before the player counts this server
targets. The remaining unknowns are the ones this harness cannot see: Folia
region hops in the raytrace fan-out, PacketEvents wrapper parsing on every
entity packet, and bossbar packet sends.
