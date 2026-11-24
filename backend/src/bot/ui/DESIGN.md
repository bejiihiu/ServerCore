# ServerCore bot — design system

typography, emoji, keyboard and screen conventions for the telegram bot.
everything here is implemented in this folder (`text/`, `keyboards/`,
`screens/`, `sending.ts`) on top of `src/i18n/`.

## direction

- **product**: access bot for a private minecraft survival server — account
  linking, status, admission, notifications.
- **character**: terse, exact, a little warm. a club doorman, not a concierge.
- **world**: a minecraft server control room — status lights, player counts,
  timestamps.
- **moments**: the status dot opening every screen; that is the one rich
  element. everything else stays quiet.
- **signature**: semantic status dots (green/yellow/red/white) that mean the
  same thing on every screen, rendered from one emoji registry.

## palette (emoji-as-color mapping)

telegram text has no colors, so state is emoji + words, always both:

| state | dot | fallback char | usage |
|---|---|---|---|
| active / ok | `status.active` | 🟢 | linked account, server online, approved |
| pending | `status.pending` | 🟡 | awaiting vote, in progress |
| attention / down | `status.attention` | 🔴 | server down, rejected, destructive confirm |
| neutral / none | `status.neutral` | ⚪ | not linked, no status |

rules:

- a screen opens with exactly one dot — the verdict of that screen. no dots
  sprinkled through the body.
- the dot is never the only carrier of state: the next words say it too
  ("онлайн", "не привязан") — color-blind and text-only safe.
- buttons use `style: "danger" | "success" | "primary"` (bot api 9.4) for the
  same semantics: red = destructive, green = approving, blue = primary
  action. at most one styled button per keyboard.

## typography

all messages are html (`parse_mode: "HTML"`, link previews always disabled —
see `composeText()`).

| element | pattern |
|---|---|
| screen title | `<status dot> <b>Заголовок</b>` — bold only here and in state words |
| field rows | `<b>Метка</b> значение` — `kv(label, value)` |
| section label | `section("...")` — bold, one line, above its block |
| aside / provenance | `quote(...)` blockquote |
| long blocks (rules, logs) | `expandableQuote(...)` collapsed by default |
| sensitive data | `spoiler(...)` |
| loading | italic single line, no fake progress bars |

copy rules (anti-slop, non-negotiable):

- no greetings ("Привет!", "Добро пожаловать"), no exclamation marks, no
  trailing periods on short strings, no hype words.
- buttons are verbs or nouns the user controls: "Статус сервера", not
  "Нажмите здесь".
- errors say what happened and what to do next, never apologize.
- user data (nicknames, ids) is always html-escaped: the bot-side
  `Translator` (createTranslator) escapes every substituted value.

## emoji registry

`src/i18n/emojis.json` — one line per semantic key:

```json
{ "status.active": { "customEmojiId": "", "fallback": "🟢" } }
```

- custom ids come from the `tgiosicons` pack via `getStickerSet("tgiosicons")`
  (`applyEmojiPack`), matched by the fallback char, cached for the process
  lifetime (ids are stable across bots).
- custom emoji entities require **telegram premium on the bot owner** (bot api
  9.4) or a fragment username. feature flag: env `BOT_CUSTOM_EMOJI=true`.
- with the flag off (or an id not filled), everything renders the unicode
  fallback — no screen may depend on custom emoji being available.
- sending still guards the runtime case: `sendScreen`/`editScreen` catch
  `400 PREMIUM_ACCOUNT_REQUIRED` and resend once with tg-emoji tags stripped
  and button icons removed (`withoutCustomEmoji`).
- button icons: `icon_custom_emoji_id` renders before the label; the renderer
  returns `undefined` when custom is off, and the factory then emits a plain
  text button.

## keyboards

all factories are pure: `ActionButton` specs in, plain `InlineKeyboardMarkup`
out. available: `actionRow`, `menuGrid` (fixed column count), `paginationRow`
(disabled arrows at the ends, disabled counter in the middle),
`toggleButton` (state marker inside the label), `confirmKeyboard`
(cancel first, confirm second — thumb path), `cancelButton`, `backButton`.

- callback data scheme: `<ns>:<action>[:<arg>]`, max 64 bytes —
  `callbackData()` throws on overflow, never truncates.
- back navigation: the return target travels in the callback data itself
  (`backButton("menu:back")` → data `menu:back`); keyboards are stateless.
- disabled buttons (bot api 10.3) carry no callback data — used for
  "processing…" states and dead pagination ends.
- `force_reply` (10.3) available via `inlineKeyboard(rows, { forceReply: true })`
  for input flows.

## screen inventory

pure builders in `screens/`, each returns `{ text, markup? }`:

| screen | builder | wired |
|---|---|---|
| main menu | `mainMenuScreen` | yes — `/start` without payload, `menu:back` |
| status | `statusScreen` | yes — `/status`, `menu:status` |
| account card | `accountCardScreen` | consumed by account flows |
| confirm dialog | `confirmDialogScreen` | consumed by destructive flows |
| paginated list | `paginatedListScreen` | consumed by admin/moderation flows |
| loading | `loadingScreen` | consumed by async flows |

screens take view-model params (plain strings/booleans), never database
models — flows map their data into screen params. `mainMenuScreen` takes an
`actions` array with `enabled` flags: features that are not wired yet simply
do not render a button, so the menu never shows a dead end.

## sending layer

`sendScreen` / `editScreen` / `editOrSendScreen` (48h-inaccessible messages
fall back to a fresh send) / `sendEphemeralScreen` / `editEphemeralScreen` /
`deleteEphemeralScreen`.

ephemeral messages (bot api 10.2) — a reply visible to one user inside a
group chat. use for private confirmations inside public flows (e.g. vote
confirmation to the voter in the group). delivery is best-effort.

message effects (`message_effect_id`) are supported on `sendScreen` via
`effectId` — ids come from the official effects list and belong in flow
config (celebration on approvals), not hardcoded in screens.

## i18n

- texts live in `src/i18n/locales/<locale>.json`, keys namespaced
  `menu.*`, `account.*`, `status.*`, `common.*`, `start.*`, `confirm.*`.
- ru is the canonical seed; new locales register at runtime via
  `i18n.addLocale` (github-hosted files later, no call-site changes).
- placeholders `{{name}}`; missing keys and missing params throw — a broken
  string must fail loudly in tests, not ship as "{{nickname}}".
- pluralization per locale rule (`ruPlural`: one/few/many/other, CLDR).
- the bot consumes only `Translator` (escape-on-typing); raw `I18nProvider`
  is for non-html consumers (plugin locale push).
- boundary with `src/services/i18n.ts`: that file is the ops-notification
  key map (flat `{name}` placeholders, ru/en inline, owned by the ops flows).
  interactive screens go through `src/i18n/` (structured dictionaries,
  pluralization, escaping). do not mix the two in one screen; consolidation
  is tracked in POLISH.md.

## layering

```
flows (commands, conversations, service logic)
  → screens/keyboards (pure data: { text, markup })
  → sending.ts (sendScreen/editScreen/ephemeral — interactive path)
  → NotificationService + GrammySender (broadcast path, plain text via
    renderPlainText, rate-limited)
```

screens never import services; services never build markup strings by hand —
they call screen builders. a broadcast cannot carry a keyboard (telegram
limitation of the fan-out path), so the plain-text rendering drops markup
deliberately.
