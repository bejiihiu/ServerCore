# Extending ServerCore

**Languages:** English | [Русский](ru/extending.md)

This page is the map for changing the codebase: where a new piece belongs, which
files move together, and what the build will refuse to accept. It assumes you
have read [architecture](architecture.md) and the ground rules in
[CONTRIBUTING](../CONTRIBUTING.md).

Three invariants decide most of the design:

- **Folia only.** Pick the scheduler that owns the resource — entity, region,
  global or async — and never assume a main thread.
- **One transport.** Plugin ↔ backend traffic is WebSocket frames only. No HTTP
  between them.
- **Fail closed.** When a check cannot be completed, deny. Degrade optional
  integrations with a warning, never with a silent allow.

Gates before you call it done:

```bash
cd backend && bun run typecheck && bun test
./gradlew :plugin:build
```

`:plugin:build` runs the plugin tests; the backend suite runs the repository
tests against `mongodb-memory-server` (or `MONGODB_TEST_URI` when set) and skips
them with a message when no mongod can be started.

---

## 1. A new plugin feature module

### Shape

One package per feature under
`plugin/src/main/kotlin/kz/bejiihiu/servercore/<module>/`. Split the code so the
decision logic has no server dependency:

- **seams** — small interfaces the logic talks to (`ViewStateApplier`,
  `ResyncExecutor`, `SpecWorld`, `BarMetric` are the existing examples);
- **pure decision code** — testable without Folia or PacketEvents;
- **production implementation** — the class that actually touches `Player`,
  packets or the filesystem;
- **scheduler glue** — the only place that calls `entityScheduler`,
  `regionScheduler`, `globalRegionScheduler` or `asyncScheduler`.

This split is not decoration: most plugin tests run without a server, and they
only can because the seams exist.

### Installer pattern

Boot wiring lives in `boot/`, one `<Feature>Installer` per subsystem. An
installer:

- takes `PluginContext` (leaf services: plugin, `io`, locale, UI kit) plus any
  installers it depends on — never the plugin singleton directly;
- `install()`s its objects once, on the enable path. Guard on the config flag
  and return early when the feature is off;
- optionally `start()`s deferred work that must wait for the rest of the graph
  (listener registration, transport connect, session recovery);
- `stop()`s everything it built, tolerating a partial install, without
  throwing. Cancel tasks before closing stores, and finish before
  `ctx.io.shutdown()`.

`PluginContext` carries the full contract; read it before adding a phase.

### Where to hook

| Need | Hook |
|---|---|
| A command | `LifecycleEvents.COMMANDS` (see `bars/BarCommands`, `boot/AdminCommandInstaller`) |
| A Bukkit event | `pluginManager.registerEvents(..., plugin)`, MONITOR when you only observe |
| A packet-level reaction | `packet/` listener + a `ViewStateApplier` or `sync` executor seam |
| A wire frame from the backend | `GatewayListener` typed callback, or `onWireMessage` raw fan-out |
| A player-facing surface | `ui/`, and the entity scheduler for every frame |
| A placeholder | `placeholderapi/PlaceholderResolver` fed by a cache, never a metric read |

### Boot and teardown order

`ServerCorePlugin.onEnable` is the single visible order:

```
flectonepulse -> ops -> config (boot blocker) -> gateway -> spec reporting
-> i18n -> integrations -> guest -> packet -> sync -> viewer -> observer
-> protection -> spec -> esp -> admin commands
-> guest.start -> ui -> bars -> gateway.start -> papi -> announce
```

The ordering rules that matter when inserting a module:

- config before anything that reads settings; a bad backend config disables the
  plugin instead of booting half-connected;
- i18n before any player-facing surface — everything renders through it;
- packet/sync before their consumers (`viewer`, `observer`, `esp`, `spec`);
- transport (`gateway.start`) after the surfaces that do not need it, so a
  defect in a bar or the UI can never delay the WebSocket link;
- `papi` last, because it reads the live caches.

`onDisable` is **dependency order, not the reverse of boot**: client views are
restored while the packet pipeline is still attached, sessions unwind before the
packet service goes, and the io pool outlives every store that flushes through
it. Add your `stop()` at the point that keeps those guarantees, not simply at
the mirrored index.

### Folia checklist

- `Player`, `World`, `Entity`, `Inventory` — entity/region scheduler only.
- Global counters and server-wide tasks — global region scheduler.
- Background periodic work — async scheduler.
- File and socket I/O — virtual threads (`ctx.io`).
- WebSocket callbacks never touch game objects: receive → validate → resolve
  UUID → hop to the owning scheduler → execute.
- Entity tasks are cancelled when the entity goes away; `forget(uuid)` style
  cleanup keeps per-player maps from outliving the session.

### Do not forget

1. **Config** — key in `plugin/src/main/resources/config.yml`, loaded in a
   `*Config` object with shipped defaults and clamping. `ConfigLoaderTest`
   asserts every key the loaders read exists in the shipped file, and that
   defaults match.
2. **Locale** — user-visible text goes through `locale/`, never into a Kotlin
   string. Add the key to both `locales/ru.yml` and `locales/en.yml`;
   `MessagesResourcesTest` fails on a one-sided key.
3. **Permissions** — a narrow node per capability in `protection/Permissions`.
   Do not reuse `servercore.admin` for a player-facing action.
4. **Docs** — extend the relevant page under `docs/` if behaviour is visible to
   an operator.
5. **Tests** — pure logic against the seams. Server-bound paths stay thin and
   explicitly untested rather than mocked into a lie.

### Minimal skeleton

```kotlin
class WidgetInstaller(private val ctx: PluginContext) {
    private var service: WidgetService? = null

    fun install() {
        val config = WidgetConfig.load(ctx.plugin.config, ctx.logger)
        if (!config.enabled) return

        val service = WidgetService(config, ctx.locale, ctx.io)
        service.start()
        this.service = service
    }

    fun stop() {
        service?.stop()
        service = null
    }
}
```

Add it to `onEnable` at the point its dependencies exist, keep the field on the
plugin, and call `stop()` from `onDisable` in dependency order.

---

## 2. A new wire message

`protocol/messages.json` is the **single cross-language contract**. The backend
Zod schemas, the plugin codec, both test suites and the protocol page are kept
in sync with it, so a message is one change across those places — never a
backend-only or plugin-only edit.

### The manifest entry

```jsonc
{
  "type": "WIDGET_OPENED",
  "direction": "plugin_to_backend",       // | backend_to_plugin | both
  "status": "active",                     // | reserved (documented, not on the wire)
  "pluginTransport": "typed",             // | raw | absent
  "pluginDto": "WidgetOpenedPayload",     // when the plugin has a DTO
  "summary": "one sentence on what triggers it and what consumes it",
  "fields": [
    { "name": "minecraftUuid", "type": "uuid", "required": true },
    { "name": "reason", "type": "string", "required": false }
  ],
  "example": {
    "minecraftUuid": "0f4d2f3e-8a44-4c21-9d0e-3b1f2a4c5d60",
    "reason": "manual"
  }
}
```

Field types are `uuid`, `objectId`, `string`, `integer`, `number`, `boolean`,
`enum` (with `values[]`), `object` (with `fields[]`) and `array` (with `items`).
`required: false` means the sender may omit the key; the other side must still
handle its absence. `pluginTransport` says how the plugin carries it: `typed`
(`MessageType` + a `Payload` branch), `raw` (known type, decoded to
`Payload.Raw` and fanned out through `GatewayListener.onWireMessage`) or
`absent` (not carried by this plugin build at all).

### Backend side

1. Add the type to `MESSAGE_TYPES` in
   `backend/src/websocket/protocol/message-types.ts`.
2. Add the Zod schema to `payloadSchemas` in the same file. Incoming frames are
   already validated by the envelope schema; the payload schema is the second
   half of that boundary.
3. If the backend pushes it, add the type to `BACKEND_TO_PLUGIN_TYPES` and to
   `NO_ACK_TYPES` only when no ACK should be awaited (handshake/ack/error
   frames). Everything else is acked on receipt.
4. Register a handler exactly once from
   `backend/src/websocket/handlers/wiring.ts` (or from the module's
   `register*Handlers` function that `wiring.ts` calls). `HandlerRegistry`
   rejects a second registration on purpose.
5. Keep handlers thin: validate/normalise, call a service, let a rejection
   propagate. The connection answers a failed handler with `ERROR` carrying
   `inReplyTo` and keeps the socket — do not fake success, and do not close.
6. Add the message to `docs/websocket-protocol.md`.

### Plugin side

1. Add the `MessageType` constant in
   `plugin/src/main/kotlin/kz/bejiihiu/servercore/websocket/Protocol.kt` unless
   the manifest marks the type `absent`.
2. For `typed`: add the `Payload` branch and both codec maps
   (`payloadToJson` / `payloadFromJson`) in `websocket/Protocol.kt`. Domain
   payloads that the `ws`/`locale` layers parse as DTOs live in
   `ws/WsProtocol.kt` or `locale/LocaleProtocol.kt`. For `raw`: nothing beyond
   the enum entry — validated JSON reaches
   `GatewayListener.onWireMessage`, and the owning module parses it.
3. When the manifest declares `pluginDto`, add that serializer to the
   `dtoSerializers` table in `ProtocolContractTest`; the suite checks the DTO's
   fields and optionality against the manifest.
4. Outbound frames get a `Durability` class: `DROP` (stale by definition),
   `QUEUE` (survives a reconnect blip) or `DURABLE` (must survive a plugin
   restart, parked in the outbox). Anything unlisted drops with the link.
5. Consume it through a narrow bridge (a `spec`/`sync` listener or a dedicated
   small listener), not by reaching into the transport from a feature module.

### Compatibility rules

- Unknown types are acked and ignored on both sides — a newer peer must never
  kill an older one. This is why new messages can ship before the other side
  upgrades.
- Add optional fields; never rename, retype or remove a required field in place.
  A breaking change is a new type.
- Never reuse a type name for a different meaning, and never move a published
  `status: "reserved"` entry without wiring it on both sides.
- `integer` fields are `z.number().int()`; floats belong in `number` fields with
  explicit bounds. Canonical JSON caps nesting at 64 levels and rejects
  non-finite numbers, so never send a payload that relies on either.
- Idempotency: a retry may arrive under a fresh envelope id with the same
  content. Handlers keyed on natural identity (`sessionId`, `applicationId`,
  `(applicationId, voterUuid)`) are the norm; deterministic audit ids exist for
  this reason.

### Contract tests

- `backend/tests/protocol-contract.test.ts` — manifest ↔ Zod schema (fields,
  required-ness, types, enums, arrays/objects), direction sets, and the example
  payload must validate.
- `plugin/src/test/.../websocket/ProtocolContractTest.kt` — manifest ↔
  `MessageType`, the example decoded through the real codec, and the re-emitted
  payload must still carry every required field.
- `backend/tests/fuzz-protocol.test.ts` — totality and adversarial inputs.

Add the manifest entry and the example first, then make both suites green. If a
suite fails, the message is not done — do not edit the test to accept drift.

---

## 3. A new bar

Bars are the most copy-paste-friendly extension because everything is keyed off
`BarId`. A new bar touches these places in order:

1. **`bars/BarId.kt`** — add an enum entry with two permission nodes. `key`,
   `commandName` (`<key>bar`), `label` and the locale `titleKey`
   (`bars.<key>.title`) derive from it.
2. **`protection/Permissions.kt`** — add `servercore.bars.<key>` and
   `servercore.bars.<key>.other`.
3. **`bars/BarsConfig.kt`** — add a `<Key>BarConfig` (at least `enabled`, plus
   thresholds or options), a field on `BarsConfig`, a branch in `isEnabled`, a
   loader block reading `bars.<key>.*` with shipped defaults, and the defaults
   as constants. The exhaustive `when` forces you to handle it.
4. **`plugin/src/main/resources/config.yml`** — add the `bars.<key>` section.
   Clamp and reorder bad values in the loader; a bad bar config must not block
   boot.
5. **A `BarMetric` implementation** — `bar`, `read(player)` (runs on that
   player's entity scheduler, so reading their location is legal),
   `readGlobal()` when the value is player-independent (this is what feeds
   PlaceholderAPI with zero bars toggled), `start`/`stop` for a background
   sampler, `forget(uuid)` for per-player caches.
6. **`boot/BarsInstaller.kt`** — add the metric to the list under its config
   flag. The installer owns the toggle store, the renderer and the commands;
   the metric owns only the number.
7. **Locale** — `bars.<key>.title` plus every placeholder used in `values` in
   both `locale/ru.yml` and `locale/en.yml`. The template owns the copy and the
   unit; the metric owns the number.
8. **PlaceholderAPI** — `bars/BarSnapshots.kt` has an exhaustive `valueKey`
   `when`, so the bar will not compile without a branch there. If it should
   expose `%servercore_bar_<key>%`, also add the resolver case and known list in
   `placeholderapi/PlaceholderResolver.kt`. The resolver reads a rendered
   snapshot, never a live metric.
9. **Tests** — `BarMetricsTest` for the reading math, `BarsConfigTest` for
   defaults/clamping, `BarPresentationTest` if the template shape is new,
   `BarCommandsTest` for the command shape. Update `docs/bars.md`.

Rules the framework already enforces:

- one active bar per surface (`bossbar` / `actionbar`) per player; switching
  reuses the same bossbar instance;
- the refresh task runs on the player's entity scheduler, and only that thread
  sends the packet;
- an unreadable or non-finite reading renders empty instead of throwing;
- toggles persist in `bars.json` per player and surface.

---

## 4. A new backend module

### Shape

```
backend/src/modules/<name>/
  types.ts             # domain types + typed errors
  repository.ts        # ports over storage, no mongoose types
  repository-mongo.ts  # mongoose implementation of those ports
  <name>-service.ts    # business logic; constructor takes one deps object
  memory-store.ts      # optional in-memory port impl / test double
  bot-callbacks.ts     # optional telegram glue, kept out of the service
```

Models live in `backend/src/database/models/` and are exported from
`index.ts`. Repositories map documents to plain serialisable records — no
mongoose document leaves the module.

### Rules

- **Ports first.** The service depends on interfaces, never on mongoose or
  grammY. Put a port in `services/ports.ts` only when another module consumes
  it; otherwise keep it module-local.
- **One deps object.** Service constructors take named dependencies, not
  positional arguments. It keeps wiring readable and makes test doubles
  obvious.
- **Services decide, handlers translate.** A WebSocket handler or bot callback
  validates/normalises and calls a service method; it does not run queries.
- **Errors are typed and narrow.** Domain failures are result unions or a
  typed `AppError`/`*Error`; do not throw strings and do not swallow a storage
  failure into a success.
- **Guarded transitions.** Anything that can be pressed twice (a vote, a
  decision, an invite use) is guarded in the store and returns false/"already"
  on a lost race. The caller skips its side effects.
- **Push, don't poll.** State that the plugin needs arrives through a
  `PluginPushPort` call. Delivery is fire-and-forget; a disconnected server
  re-seeds on reconnect.

### Wiring

Construct the module in `backend/src/app.ts` (the composition root) with its
real repositories and ports, start its loops, and add its `stop()` to the
shutdown order. If it owns wire messages, register handlers from
`handlers/wiring.ts`; if it owns bot screens/callbacks, register on the single
existing bot instance — a second `Bot` silently discards the other side's
handlers.

### Text

Two channels, split by format, not by convenience:

- **plain text** (broadcast DMs, group messages, toasts) —
  `backend/src/services/i18n.ts`. Every entry carries `ru` and `en`; the
  `satisfies` clause makes a missing column a compile error.
- **html screens** (private-chat cards, keyboards) —
  `backend/src/i18n/locales/ru.json` + `en.json`, rendered by
  `bot/ui/screens`.

`backend/tests/i18n-parity.test.ts` checks key parity, placeholder drift, tone,
emoji registry and capitalisation across both layers. Add both locales, not one.

### Tests

- **Unit** — service against the in-memory fakes in
  `backend/tests/helpers/in-memory.ts` and `FixedClock`. Time moves by
  `clock.advance(ms)`, never by sleeping.
- **Real Mongo** — index/unique/TTL behaviour against
  `mongodb-memory-server` (or `MONGODB_TEST_URI`) via
  `tests/helpers/mongo.ts`; skip cleanly when no mongod is available.
- **Wire** — drive real `ServerConnection` frames with
  `tests/helpers/ws-harness.ts` (signed envelopes, replay window, ACK/ERROR
  assertions).
- **Bot** — scripted conversation harness for questionnaire/delete/link flows.

A module is done when its ports, its mongo implementation, its service, its
handler/bot glue, its i18n strings and its tests agree — and `bun run typecheck`
plus `bun test` are green.
