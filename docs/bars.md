# Server info bars

Live server health as a per-player toggle: a bossbar (or an actionbar line) that a player turns on for themselves. Every bar is opt-in — nobody is forced to watch metrics — and the choice is remembered across relogs.

```
◆ RAM 3.2 GB / 8.0 GB (40%)
◆ TPS 19.9 · region 6,-1 · server 20.0
◆ MSPT 12.5 ms · p95 18.0 ms
◆ Online 7/60 (12%)
◆ world 14:32 · 68% · clear
◆ Network 3/3 servers · 7 online · 1 offline
```

## Commands

| Command | Permission | Description |
|---|---|---|
| `/rambar` | `servercore.bars.ram` | Heap used vs the JVM's max heap |
| `/tpsbar` | `servercore.bars.tps` | TPS of the region the player stands in |
| `/msptbar` | `servercore.bars.mspt` | Tick time of that same region |
| `/playersbar` | `servercore.bars.players` | Online players vs the slot limit |
| `/worldbar` | `servercore.bars.world` | The player's world, its clock and weather |
| `/netbar` | `servercore.bars.net` | The whole network: servers answering, players, dead servers |

Bare form toggles the bar for yourself. Every command also takes a target:

```
/rambar              toggles your own ram bar
/rambar Steve        toggles Steve's bar (needs servercore.bars.ram.other)
/rambar all          toggles every online player (needs the .other node)
```

The `.other` node is required for any target form, `all` included. Player names are tab-completed; the literal `all` is never treated as a player name. Console may drive the target form.

Enabling a bar replaces whatever bar that player had on the same surface — one bossbar per player, so `/rambar` then `/tpsbar` leaves the TPS bar. Bars persist per player in `plugins/ServerCore/bars.json`; deleting that file simply resets everyone to "off".

## The network bar

`/netbar` is the only bar whose data does not come from this process. The backend already tracks every game server's heartbeat; it builds an aggregate and pushes it back to all connected servers as a `NETWORK_SNAPSHOT` right after a heartbeat refreshes its directory (no extra timer, at most one push per second). The plugin caches the aggregate and the bar renders it, so it works on any server in the network without polling.

Three states, all honest:

- **fresh aggregate** — `3/3 servers · 7 online`. Fill = answering servers / known servers, green when all answer.
- **a server went quiet** — the line grows `· 1 offline` and the color drops. A server is "quiet" once its last heartbeat is older than `bars.net.stale-after-ms`, measured from the moment the frame arrived plus the age it already had.
- **no aggregate yet** — the backend is down, was never reachable, or runs a build without the push. The bar falls back to this server alone: `1/1 servers` with the local count. It never shows a dash and never invents a fleet.

Two things worth knowing:

- **"total" means servers the backend has seen.** A server that has been down since the last backend restart is not in the directory, so it is not counted until it connects once.
- **players** is the backend's sum over all known servers, so a quiet server contributes its last reported count. The `offline` segment tells you the number is not fully live.

## Configuration

```yaml
bars:
  enabled: true                  # master switch: off = no rendering, no /bar commands
  update-interval-ticks: 20      # refresh cadence, 20 ticks = 1s, clamped 1..200
  display: bossbar               # bossbar | actionbar
  ram:
    enabled: true
    good: 60                     # heap fill percent at or below this is healthy
    medium: 80                   # above this is critical
  tps:
    enabled: true
    good: 18.0                   # TPS at or above this is healthy
    medium: 15.0                 # below this is critical
  mspt:
    enabled: true
    good: 40.0                   # tick time in ms at or below this is healthy
    medium: 50.0                 # above this is the 20 TPS budget
  players:
    enabled: true
  world:
    enabled: true
    show-weather: true           # false = world and clock only
  net:
    enabled: true
    stale-after-ms: 45000        # heartbeat age that counts as offline
```

Notes:

- `update-interval-ticks` is clamped to `1..200`; a swapped `good`/`medium` pair is put back in order with a warning instead of silently inverting the colors.
- `net.stale-after-ms` defaults to **three heartbeat intervals** taken from `backend.heartbeat-interval-seconds` (15 s × 3 = 45 s). Setting it below one interval makes servers flicker offline between heartbeats; the loader warns and clamps it into `5000..600000`.
- Every bar has its own `enabled`. A bar switched off loses its command too, so players never see a command that cannot work.
- The surface is global, not per bar: `bossbar` draws the progress bar above the hotbar, `actionbar` writes one line that fades and is re-sent on the next refresh.

## Permissions

| Node | Meaning |
|---|---|
| `servercore.bars.ram` / `.tps` / `.mspt` / `.players` / `.world` / `.net` | Toggle that bar on yourself |
| any of those plus `.other` (e.g. `servercore.bars.ram.other`) | Toggle that bar for another player or for `all` |

LuckPerms is the permission source; operator status is never consulted. A reasonable split: `/netbar` and `/rambar` for moderators, everything for admins, `/playersbar` and `/worldbar` for everyone.

## Folia notes — what these numbers actually mean

Folia has no server-wide tick. There is no single "server TPS", and a bar that claims one would be lying, so the plugin is explicit about scope:

- **`/tpsbar` shows the player's region**, read with `Server.getRegionTPS(location)` on that player's own entity scheduler — the task that refreshes the bar already runs on the region thread that owns them. The title prints the chunk pair (`region 6,-1`) and the 1-minute window. The server-wide aggregate rides along as `server <tps>` for comparison. When no region owns the spot (unloaded chunk, a region mid-split) the region value is `—` and the aggregate is used for color.
- **`/msptbar` shows that region's 5-second tick report.** Folia has no per-location MSPT API (the upstream PR is still open), but `getAverageTickTime()` is region-scoped on Folia: from a region thread it returns that region's report, from anywhere else it throws. The bar task is a region thread by construction, so the read is legal and needs no reflection. `p95` is taken over the last minute of those 5-second averages — it says "the region's 5-second average was at least x in 5% of the last minute", not "5% of individual ticks took x".
- **Pacing fallback.** If the region report is unavailable, the bar measures the gap between its own refresh runs instead. That is a pacing/overrun signal with a 50 ms floor, not CPU time; the plugin logs one warning when it switches.
- **`/rambar` and `/worldbar` are process/world facts**, not region facts. Heap is read by one async sampler and every bar reads the snapshot; the world clock and weather are read on the player's region thread.
- **Two players in different regions see different TPS/MSPT.** That is correct, not a bug.

## PlaceholderAPI

When PlaceholderAPI is installed, `%servercore_…%` exposes the bar values so scoreboards and tab lists can reuse them. Values are read from the same snapshot layer the bars render from — never computed on the asking thread, never blocking, and at most one refresh interval old plus the placeholder memo window (`placeholders.cache-ttl-milliseconds`, 2 s by default).

| Placeholder | Value |
|---|---|
| `%servercore_bar_ram%` | Heap fill percent, e.g. `42` |
| `%servercore_bar_tps%` | TPS with one decimal, e.g. `19.8` |
| `%servercore_bar_mspt%` | Milliseconds with one decimal, e.g. `38.4` |
| `%servercore_bar_players%` | Online player count, e.g. `7` |
| `%servercore_bar_net%` | Answering servers / known servers, e.g. `3/3` |

Which reading a placeholder shows:

- `ram`, `players`, `net` are the same for everyone. They are sampled every refresh interval even when no bar is toggled, so a scoreboard never depends on somebody wearing the bar.
- `tps` answers with the asking player's own region reading while they have the TPS bar on, otherwise with the server-wide aggregate. `mspt` answers with the asking player's own region reading, otherwise the last region sample this server took — there is no server-wide MSPT to fall back on.
- With no sample at all (plugin just started, Folia just recycled the region) the value is the configured `placeholders.unknown-value` (default `unknown`). Distinguish it from a real zero in your layout.

Example scoreboard line (any scoreboard plugin that speaks PlaceholderAPI; the trailing `%` after the ram placeholder is the percent sign):

```text
&7tps &f%servercore_bar_tps%  &7ram &f%servercore_bar_ram%%  &7players &f%servercore_bar_players%
```

Example TAB header or footer:

```text
%servercore_bar_net% servers · %servercore_bar_players% online
```

Keep the refresh interval of the consuming plugin at or above `bars.update-interval-ticks` (1 s). Asking faster only re-reads the same cached value.

## Turning it off

- `bars.enabled: false` — no rendering, no commands, no sampling. The `%servercore_bar_*%` placeholders answer with the unknown value.
- `bars.display: actionbar` — the same bars without occupying the bossbar slot (useful when another plugin owns bossbars).
- `bars.<bar>.enabled: false` — drop a single bar and its command.
