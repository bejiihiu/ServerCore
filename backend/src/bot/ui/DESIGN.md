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
| pending | `status.pending` | 🟡 | awaiting vote, questionnaire open, login to confirm |
| attention / down | `status.attention` | 🔴 | server down, rejected, destructive confirm |
| neutral / none | `status.neutral` | ⚪ | not linked, nothing known, plain information |

rules:

- a screen opens with exactly one dot — the verdict of that screen. no dots
  sprinkled through the body.
- the dot is never the only carrier of state: the next words say it too
  ("онлайн", "не привязан") — color-blind and text-only safe.
- "no data" is not "down": when the backend has no live link to the server
  the dot is white and the row says so. red is reserved for a server that
  actually reported itself down.
- buttons use `style: "danger" | "success" | "primary"` (bot api 9.4) for the
  same semantics: red = destructive, green = approving, blue = primary
  action. at most one styled button per keyboard; the confirm/cancel pair
  always puts cancel first (thumb path).

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

- the bot says **ты**, never «вы». one voice per product, and it is not a
  corporate one.
- no greetings ("Привет!", "Добро пожаловать"), no exclamation marks, no
  trailing periods on short strings, no hype words, no emphasis in caps.
- no emoji inside message text: state markers come from the emoji registry
  (the dots), everything else is words. no emoji inside button labels except
  the toggle state markers and the cancel "✕".
- buttons are verbs or nouns the user controls: "Статус сервера", not
  "Нажмите здесь".
- errors say what happened and what to do next, never apologize.
- user data (nicknames, ids) is always html-escaped: the bot-side
  `Translator` (createTranslator) escapes every substituted value.

`tests/i18n-parity.test.ts` enforces the mechanical half of this list (ru/en
key parity, placeholder parity, no caps shouting, no «вы», no stray emoji) on
both text layers, so a hand-written string cannot quietly break the tone.

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
(cancel first, confirm second — thumb path), `backButton`/`backRow`.

button labels are always passed in by the caller — no factory carries a
default in a language, so a screen cannot leak Russian to an English user.

- callback data scheme: `<ns>:<action>[:<arg>]`, max 64 bytes —
  `callbackData()` throws on overflow, never truncates.
- back navigation: the return target travels in the callback data itself
  (`backButton("menu:back")` → data `menu:back`); keyboards are stateless.
- disabled buttons (bot api 10.3) carry no callback data — used for
  "processing…" states and dead pagination ends.
- `force_reply` (10.3) available via `inlineKeyboard(rows, { forceReply: true })`
  for input flows.
- every callback id comes from the `CALLBACK` table in `bot/keyboards.ts`, and
  `CALLBACK_NAMESPACES` is the single owner index (`menu`, `account`, `link`,
  `del`, `app`, `lang`, `vote`, `auth`). a test asserts that each constant's
  prefix is registered, so two flows cannot answer each other's buttons. menu
  and account ids are re-exported from the screens that own them.

## screen inventory

pure builders in `screens/`, each returns `{ text, markup? }`:

| screen | builder | wired |
|---|---|---|
| main menu | `mainMenuScreen` | `/start` without payload, `menu:back` |
| server + account status | `statusScreen` | `/status`, `menu:status` |
| account card | `accountCardScreen` | `menu:account`, `account:refresh`, `lang:toggle` |
| notice (success / failure / info) | `noticeScreen` | linking results, deletion results, `/help`, `/stats`, questionnaire nudges |
| application card | `applicationCardScreen` | questionnaire summary, `/myapplication` |
| confirm dialog | `confirmDialogScreen` | linking prompt, account deletion |
| language picker | `languagePickerScreen` | `/language` |
| paginated list | `paginatedListScreen` | consumed by admin/moderation flows |
| loading / hint | `loadingScreen`, `hintScreen` | linking probe, in-flow nudges |

screens take view-model params (plain strings/booleans), never database
models — flows map their data into screen params. `mainMenuScreen` takes an
`actions` array with `enabled` flags: features that are not wired yet simply
do not render a button, so the menu never shows a dead end.

`/stats` and the ops fan-outs are the deliberate exception: `/stats` is an
admin dashboard and the fan-outs are plain-text broadcasts, so they render
from the flat text map (see i18n below) through the same `noticeScreen`
shell.

## sending layer

`sendScreen` / `editScreen` / `editOrSendScreen` (48h-inaccessible messages
fall back to a fresh send) / `sendEphemeralScreen` / `editEphemeralScreen` /
`deleteEphemeralScreen`, plus `replyScreen(ctx, screen)` for the conversation
path — it replies with html + markup while still going through `ctx.reply`, so
the conversations plugin records the call for replay.

navigation always edits the message its button lives on; when telegram
refuses (too old, no message reference) it degrades to a fresh send. a button
pressed twice therefore lands on the same screen instead of stacking copies.
buttons of a conversation that already finished are caught by a namespace
catch-all and answered with one line — no button is left spinning.

ephemeral messages (bot api 10.2) — a reply visible to one user inside a
group chat. use for private confirmations inside public flows (e.g. vote
confirmation to the voter in the group). delivery is best-effort.

message effects (`message_effect_id`) are supported on `sendScreen` via
`effectId` — ids come from the official effects list and belong in flow
config (celebration on approvals), not hardcoded in screens.

## i18n

two layers, split by **channel**, deliberately:

| layer | file | renders | placeholders | escaping |
|---|---|---|---|---|
| structured | `src/i18n/locales/{ru,en}.json` | html screens + keyboards (private chat) | `{{name}}` | every value escaped on substitution |
| flat | `src/services/i18n.ts` | plain-text channel: ops fan-outs, group vote card, callback toasts, account notifications, status labels | `{name}` | none — the text carries no markup |

the split is permanent, not a leftover: a broadcast cannot carry markup (the
fan-out path sends plain text) and a screen cannot be fanned out. what is not
allowed is mixing them inside one screen — a screen's body comes from the
structured layer, and any value the flat map also owns (account status
labels) travels in as a parameter.

- ru is the canonical seed and the fallback; en ships the same key set.
  `tests/i18n-parity.test.ts` fails the build on a key present in one locale
  only, on a placeholder that drifted, on «вы» in a «ты» product, on emoji
  outside the registry and on caps used as emphasis.
- new locales register at runtime via `i18n.addLocale` (github-hosted files
  later, no call-site changes).
- missing keys and missing params throw — a broken string must fail loudly in
  tests, not ship as "{{nickname}}".
- pluralization per locale rule (`ruPlural`: one/few/many/other, `defaultPlural`
  for en).
- the bot consumes only `Translator` (escape-on-typing); raw `I18nProvider`
  is for non-html consumers (plugin locale push).
- per-locale translators and the one emoji registry are bound in
  `src/bot/ui/runtime.ts` (`tr(locale)`, `emoji`), so a screen never reaches
  for a global.

known gap: the login prompt is still rendered in the ops default language —
the auth request does not carry the user's language yet. the en strings exist,
the wiring is a separate change in the auth module.

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
