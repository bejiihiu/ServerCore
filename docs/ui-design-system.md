# ServerCore in-game UI design system

**Languages:** English | [Русский](ru/ui-design-system.md)

Every player-facing surface the plugin renders — titles, actionbars, chat blocks, bossbars, tab list, world-anchored text — is built from one toolkit (`plugin/src/.../ui/`). This document is the source of truth for its tokens and rules. If code and this doc disagree, fix the code.

## Direction

The server is a closed survival season: admission, gates, keys. The UI speaks like a quiet gatekeeper at night — slate stone, brass fittings, signal lamps. Nothing glows, nothing sells.

- **Character:** calm, exact, a little warm.
- **World:** a gatehouse at night — obsidian slate, brass, lamp light.
- **Signature:** the brass `◆` mark and the `┃` rail that opens every system block in chat. If a message has neither, it is not a system message.

## Palette ("obsidian & brass")

All colors live as Kotlin constants in `ui/Theme.kt`. Locale templates never use vanilla color names — only the theme tags registered there. Changing the brand means changing one file.

| Token | Hex | Role |
|---|---|---|
| `PRIMARY` | `#E9E4D8` | parchment — body text |
| `SECONDARY` | `#ADA79A` | supporting text |
| `MUTED` | `#6E6A62` | meta, asides; ≥3:1 on chat background |
| `HAIRLINE` | `#3C3F45` | rules, rails, spent bar segments — never text |
| `GOLD` | `#D9A441` | the accent. brand mark, key numbers, the one bright thing |
| `GOLD_DIM` | `#8A6A2B` | brand-owned rules |
| `OK` | `#7CB874` | success states |
| `WARN` | `#D98F4A` | caution states |
| `DANGER` | `#D2655C` | failure, expiry, denial |
| `INFO` | `#8FA8BF` | informational banners |

One non-text token lives beside the palette: `HOLOGRAM_BACKING_ARGB` (`#90141618`) — the translucent obsidian behind world-anchored `TextDisplay` text.

Rules:

- Text-bearing roles (`PRIMARY`, `SECONDARY`, `GOLD`, semantics) are unit-tested to clear 4.5:1 against a near-black chat surface; `MUTED` clears 3:1.
- One accent per surface. Gold marks the thing that matters — a code, a number, a header — never whole paragraphs, never as a background wash.
- Semantic colors carry meaning only. No decorative green.

MiniMessage tag aliases: `<primary>`, `<secondary>`, `<muted>`, `<hairline>`, `<gold>`, `<brand>` (= gold), `<ok>`, `<warn>`, `<danger>`, `<info>`.

## Typography and geometry

Minecraft's glyph set is fixed, so "type" here means weight through color, density, and marker discipline:

- **Titles:** one or two words in the accent or primary, sentence case, no trailing punctuation. Subtitle carries the actual sentence in `SECONDARY`.
- **Chat blocks:** content lines sit behind the `┃ ` rail; `─` rules open and close the block (`FRAME_WIDTH = 40` glyphs). One thought per line. Blocks never exceed ~6 lines — anything longer belongs in an item or a command.
- **Chat one-liners:** a single system notice keeps the rail and drops the rules (`UiFx.railLine`). If it has no rail and no `◆`, it is not a system message.
- **Actionbar:** one line, ≤60 glyphs, re-sent on a schedule when it expresses continuous state (the pulse alternates `▸`/`▹` markers).
- **Bossbar:** timed processes only. `PROGRESS` overlay, yellow while calm, red past the last quarter.
- **Tab list:** one header, one footer, ambient identity, no stats.
- **Holograms (`TextDisplay`):** world-anchored information only, slate translucent backing, non-persistent, auto-expiring.

Timing is part of the system, not a per-screen choice (`UiFx`): 100 ms fade in, 2600 ms stay, 300 ms fade out. A welcome may hold 3200 ms because it is the one headline a guest gets; a one-second countdown tick holds 950 ms so the fade tail bleeds into the next frame. Kick screens are a single multi-line component — never "print the lines, then kick".

## Surface decision rules

Ask in order:

1. **Does the player need to scroll back to it?** → chat block (card/frame).
2. **Is it a state change that may interrupt?** → title.
3. **Is it continuous transient state?** → actionbar, re-sent on a schedule.
4. **Does time run out?** → bossbar (plus title ticks for the final five seconds).
5. **Does it belong to a place in the world?** → hologram.
6. **Does it belong to the session as a whole?** → tab list.

Two surfaces may combine (title announces, card explains), three never overlap at once.

## Threading (Folia)

- All player-owned scheduling goes through the player's `EntityScheduler` (`UiScheduler` helpers). No main-thread assumptions anywhere.
- **Every send hops**, including "harmless" ones: `Player.uiNow` for one-shot writes, `uiDelayed`/`uiRepeat` for staged ones. The callers of a message can be the async chat event, a WS callback or the coordinator's settled hook, so a surface never sends from wherever it happens to be called. Bossbar show/hide is player state and hops too, which makes `Countdown.cancel()` safe from any thread.
- World placement (hologram spawn/despawn) runs on the `RegionScheduler` owning the location; entity removal on the entity's own scheduler.
- Locale pushes arrive on the WS thread: parse off the hot path, swap the store atomically, then persist to disk.

## Copy rules (anti-slop)

- No exclamation marks. No trailing periods on short lines. No "Добро пожаловать на сервер".
- Headers lowercase in templates (the first glyph may capitalize when it starts a sentence-like line — RU copy here uses lowercase headers for system blocks).
- Name the thing, not the feeling: "код: 123-456 — подтверди в Telegram-боте", not "Вы почти у цели!".
- Numbers come from callers as placeholders, never hardcoded in templates.

## Localization

One runtime store, no second layer:

- **Canonical locale store** (`locale/LocaleService.kt`, seeds `plugin/src/main/resources/locales/ru.yml` + `locales/en.yml`). Every player-facing string lives here: toolkit surfaces (tab list, welcome, auth window, countdown) and feature copy (guest lifecycle, observation replies, protection denials, permission debug, `placeholders.*`). `guest.lang` picks the active language; a key missing from it falls back to the English base, and a key missing everywhere renders as the key itself.
- Templates arrive as **full files** pushed by the backend over WS as `LOCALE_PUSH` (see `docs/websocket-protocol.md`); the plugin validates the file, swaps the store for that language atomically, acknowledges with `LOCALE_ACK` and persists it to `plugins/ServerCore/locales/<lang>.yml`, which wins over the seed on boot. The backend serves the canonical `ru` bundle today; `en` ships in the plugin jar and can be pushed through the same channel.
- `util/I18n.kt` is a thin facade over the service, kept so feature call sites still speak `text`/`component`/`components`. It owns no strings and no files.
- Both locales use the same theme tags; vanilla color names are forbidden and a test fails the build on them. `placeholders.*` is the exception: those values are consumed as plain legacy text by PlaceholderAPI, so they carry no markup at all.
- Placeholder values are MiniMessage-escaped at injection (`LocaleService.component` / `Theme.render`); a feature never concatenates a player name into a template. Pushed templates are trusted only as far as their yaml parses.
- `ResourceMarkupTest` parses every bundled template in strict MiniMessage (unknown and unclosed tags fail), rejects vanilla colors, legacy `§`, empty tag pairs and doubled spaces. `MessagesResourcesTest` holds ru/en key parity, the list of keys the code actually renders, and asserts the old flat `messages_*.yml` layer is gone.

## Toolkit map

| Piece | File | Purpose |
|---|---|---|
| Theme tokens + tag resolver | `ui/Theme.kt` | palette, decorative glyphs, frame width, hologram backing |
| Placeholder engine | `ui/Placeholders.kt` | `{name}` substitution with escaping |
| Surfaces | `ui/UiFx.kt` | title/actionbar/chat frame/rail line/tab list primitives |
| Countdown formatting | `ui/CountdownRenderer.kt` | pure, unit-tested bar/clock logic |
| Countdown runtime | `ui/Countdown.kt` | bossbar + title ticks on EntityScheduler |
| Components | `ui/Components.kt` | `UiKit`: title, actionBar, statusCard, banner, tabList, countdown |
| Holograms | `ui/Holograms.kt` | expiring `TextDisplay`, Folia-safe |
| Scheduling | `ui/UiScheduler.kt` | `uiNow`/`uiDelayed`/`uiRepeat` entity-scheduler wrappers |
| Locale | `locale/*` | store, service, WS DTOs |
| Feature copy facade | `util/I18n.kt` | thin `text`/`component` wrapper over the locale service |
| Join identity | `boot/JoinFlowListener.kt` | tab list on arrival |
