# ServerCore in-game UI design system

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

Rules:

- Text-bearing roles (`PRIMARY`, `SECONDARY`, `GOLD`, semantics) are unit-tested to clear 4.5:1 against a near-black chat surface; `MUTED` clears 3:1.
- One accent per surface. Gold marks the thing that matters — a code, a number, a header — never whole paragraphs, never as a background wash.
- Semantic colors carry meaning only. No decorative green.

MiniMessage tag aliases: `<primary>`, `<secondary>`, `<muted>`, `<hairline>`, `<gold>`, `<brand>` (= gold), `<ok>`, `<warn>`, `<danger>`, `<info>`.

## Typography and geometry

Minecraft's glyph set is fixed, so "type" here means weight through color, density, and marker discipline:

- **Titles:** one or two words in the accent or primary, sentence case, no trailing punctuation. Subtitle carries the actual sentence in `SECONDARY`.
- **Chat blocks:** content lines sit behind the `┃ ` rail; `─` rules open and close the block (`FRAME_WIDTH = 40` glyphs). One thought per line. Blocks never exceed ~6 lines — anything longer belongs in an item or a command.
- **Actionbar:** one line, ≤60 glyphs, re-sent on a schedule when it expresses continuous state (the pulse alternates `▸`/`▹` markers).
- **Bossbar:** timed processes only. `PROGRESS` overlay, yellow while calm, red past the last quarter.
- **Tab list:** one header, one footer, ambient identity, no stats.
- **Holograms (`TextDisplay`):** world-anchored information only, slate translucent backing, non-persistent, auto-expiring.

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
- World placement (hologram spawn/despawn) runs on the `RegionScheduler` owning the location; entity removal on the entity's own scheduler.
- Locale pushes arrive on the WS thread: parse off the hot path, swap the store atomically, then persist to disk.

## Copy rules (anti-slop)

- No exclamation marks. No trailing periods on short lines. No "Добро пожаловать на сервер".
- Headers lowercase in templates (the first glyph may capitalize when it starts a sentence-like line — RU copy here uses lowercase headers for system blocks).
- Name the thing, not the feeling: "код: 123-456 — подтверди в Telegram-боте", not "Вы почти у цели!".
- Numbers come from callers as placeholders, never hardcoded in templates.

## Localization

- Canonical locale: RU (`locale/LocaleService.kt`). The bundled seed is `plugin/src/main/resources/locales/ru.yml`.
- The backend hosts locale files (GitHub), pushes the **full file** over WS as `LOCALE_PUSH` (see `docs/websocket-protocol.md`); the plugin acknowledges with `LOCALE_ACK` and persists the file to `plugins/ServerCore/locales/ru.yml`, which wins over the seed on boot.
- Placeholder values are MiniMessage-escaped at injection; pushed templates are trusted only as far as their yaml parses.

## Toolkit map

| Piece | File | Purpose |
|---|---|---|
| Theme tokens + tag resolver | `ui/Theme.kt` | palette, decorative glyphs, frame width |
| Placeholder engine | `ui/Placeholders.kt` | `{name}` substitution with escaping |
| Surfaces | `ui/UiFx.kt` | title/actionbar/chat frame/tab list primitives |
| Countdown formatting | `ui/CountdownRenderer.kt` | pure, unit-tested bar/clock logic |
| Countdown runtime | `ui/Countdown.kt` | bossbar + title ticks on EntityScheduler |
| Components | `ui/Components.kt` | `UiKit`: statusCard, banner, guestWelcome, authPrompt |
| Holograms | `ui/Holograms.kt` | expiring `TextDisplay`, Folia-safe |
| Scheduling | `ui/UiScheduler.kt` | entity-scheduler wrappers |
| Locale | `locale/*` | store, service, WS DTOs |
| Demo wiring | `boot/JoinFlowListener.kt` | join flow proof; to be replaced by real state |
